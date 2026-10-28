package ru.voyahtune.agent.modules;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.policy.KeyboardPolicy;
import ru.voyahtune.agent.runtime.ConfigurationObject;
import ru.voyahtune.agent.runtime.OemAccess;

public final class RussianKeyboardBuilder implements AutoCloseable {
    public interface Platform {
        Object template(String reference) throws Exception;
        Object keyboard(int resourceId, Object template, int width, int height) throws Exception;
        Object key(boolean toggle) throws Exception;
        Object drawable(String reference) throws Exception;
        Object toggleIcon(int code, int state) throws Exception;
    }

    public static final int CACHE_ID = 999999;
    private final ConfigurationObject definition;
    private final Platform platform;
    private final Map<Object, Object> created = new IdentityHashMap<>();

    public RussianKeyboardBuilder(ConfigurationObject configuration, Platform platform) {
        definition = configuration.object("keyboard");
        this.platform = platform;
        ConfigurationObject attributes = definition.object("attrs");
        if (attributes.decimal("width", 0) <= 0 || attributes.decimal("height", 0) <= 0
                || definition.objects("rows").isEmpty()) {
            throw new IllegalArgumentException("Keyboard dimensions and rows required");
        }
    }

    public synchronized Object get(Object pool, int resourceId, int width, int height) throws Exception {
        created.entrySet().removeIf(entry -> ((List<?>) entry.getValue()).stream()
                .noneMatch(keyboard -> keyboard == entry.getKey()));
        List<?> cache = (List<?>) OemAccess.read(pool, "mSoftKeyboards");
        for (Object keyboard : cache) {
            if (((Number) OemAccess.call(keyboard, "getCacheId")).intValue() == CACHE_ID) {
                OemAccess.call(keyboard, "setSkbCoreSize", width, height);
                OemAccess.call(keyboard, "setNewlyLoadedFlag", false);
                return keyboard;
            }
        }
        Object keyboard = build(resourceId, width, height);
        if (keyboard != null) {
            OemAccess.call(keyboard, "setCacheId", CACHE_ID);
            OemAccess.call(cache, "add", keyboard);
            created.put(keyboard, cache);
        }
        return keyboard;
    }

    private Object build(int resourceId, int width, int height) throws Exception {
        ConfigurationObject attributes = definition.object("attrs");
        Object template = platform.template(attributes.string("skb_template", null));
        if (template == null) return null;
        Object keyboard = platform.keyboard(resourceId, template, width, height);
        OemAccess.call(keyboard, "setFlags", attributes.bool("skb_cache_flag", false), attributes.bool("skb_sticky_flag", true),
                attributes.bool("qwerty", false), attributes.bool("qwerty_uppercase", false));
        float horizontalMargin = attributes.decimal("key_xmargin", 0);
        float verticalMargin = attributes.decimal("key_ymargin", 0);
        OemAccess.call(keyboard, "setKeyMargins", horizontalMargin, verticalMargin);
        float y = 0;
        for (ConfigurationObject row : definition.objects("rows")) {
            float x = row.decimal("start_pos_x", 0);
            y = row.decimal("start_pos_y", y);
            OemAccess.call(keyboard, "beginNewRow", row.integer("row_id", -1), y);
            for (ConfigurationObject key : row.objects("keys")) {
                float keyWidth = key.decimal("width", attributes.decimal("width", 0));
                float keyHeight = attributes.decimal("height", 0);
                Object softKey = createKey(key, attributes, template);
                if (softKey == null || keyWidth < 2 * horizontalMargin || keyHeight < 2 * verticalMargin) continue;
                int code = key.integer("code", 0);
                OemAccess.call(softKey, "setKeyAttribute", code, key.string("label", null),
                        key.bool("repeat", attributes.bool("repeat", false)), key.bool("balloon", attributes.bool("balloon", false)));
                Object icon = code == -7 ? platform.toggleIcon(-7, 0) : null;
                if (icon == null) icon = platform.drawable(key.string("icon", null));
                if (icon == null) icon = OemAccess.call(template, "getDefaultKeyIcon", code);
                Object popup = platform.drawable(key.string("icon_popup", null));
                if (popup == null) popup = OemAccess.call(template, "getDefaultKeyIconPopup", code);
                OemAccess.call(softKey, "setPopupSkbId", 0);
                OemAccess.call(softKey, "setKeyType", OemAccess.call(template, "getKeyType",
                        key.integer("key_type", attributes.integer("key_type", 0))), icon, popup);
                OemAccess.call(softKey, "setKeyDimensions", x, y, x + keyWidth, y + keyHeight);
                OemAccess.call(softKey, "setSkbCoreSize", width, height);
                OemAccess.call(softKey, "changeCase", false);
                x += keyWidth;
                if (!(Boolean) OemAccess.call(keyboard, "addSoftKey", softKey)) {
                    throw new IllegalStateException("OEM keyboard rejected a configured key");
                }
            }
            y += attributes.decimal("height", 0);
        }
        OemAccess.call(keyboard, "setSkbCoreSize", width, height);
        return keyboard;
    }

    private Object createKey(ConfigurationObject key, ConfigurationObject attributes, Object template) throws Exception {
        if (key.has("id")) return OemAccess.call(template, "getDefaultKey", key.integer("id", 0));
        Object softKey = platform.key(key.has("toggle_states"));
        if (!key.has("toggle_states")) return softKey;
        Object first = null;
        Object previous = null;
        for (ConfigurationObject state : key.objects("toggle_states")) {
            Object toggle = OemAccess.call(softKey, "createToggleState");
            int stateId = state.integer("state_id", 0);
            OemAccess.call(toggle, "setStateId", stateId);
            OemAccess.write(toggle, "mKeyCode", state.integer("code", 0));
            OemAccess.write(toggle, "mKeyLabel", state.string("label", null));
            Object icon = platform.toggleIcon(key.integer("code", 0), stateId);
            if (icon == null) icon = platform.drawable(state.string("icon", null));
            if (icon != null) OemAccess.write(toggle, "mKeyIcon", icon);
            Object popup = platform.drawable(state.string("icon_popup", null));
            if (popup != null) OemAccess.write(toggle, "mKeyIconPopup", popup);
            if (state.has("key_type")) OemAccess.write(toggle, "mKeyType", OemAccess.call(template, "getKeyType", state.integer("key_type", 0)));
            OemAccess.call(toggle, "setStateFlags", state.bool("repeat", attributes.bool("repeat", false)),
                    state.bool("balloon", attributes.bool("balloon", false)));
            if (previous == null) first = toggle;
            else OemAccess.write(previous, "mNextState", toggle);
            previous = toggle;
        }
        if (first != null) OemAccess.call(softKey, "setToggleStates", first);
        return softKey;
    }

    public static void changeCase(Object keyboard, boolean uppercase) throws Exception {
        for (Object row : (List<?>) OemAccess.read(keyboard, "mKeyRows")) {
            for (Object key : (List<?>) OemAccess.read(row, "mSoftKeys")) {
                if (KeyboardPolicy.letter(((Number) OemAccess.call(key, "getKeyCode")).intValue())) {
                    OemAccess.call(key, "changeCase", uppercase);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        for (Map.Entry<Object, Object> entry : created.entrySet()) {
            List<?> cache = (List<?>) entry.getValue();
            cache.removeIf(keyboard -> keyboard == entry.getKey());
        }
        created.clear();
    }
}

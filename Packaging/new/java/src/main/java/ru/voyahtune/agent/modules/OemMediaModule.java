package ru.voyahtune.agent.modules;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.OemNowPlaying;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Connects the OEM media SDK to Native without changing the global audio policy. */
public final class OemMediaModule implements ModuleRuntime.Module {
    private static final String TAG = "VoyahOemMedia";
    private static final String ENUM = "com.qinggan.media.helper.MediaEnum";
    private static final String CONFIG = "com.qinggan.media.helper.MediaConfig";
    private static final String MANAGER = "com.qinggan.app.mediaCentre.MediaManager";
    private static final String INFO = "com.qinggan.media.helper.base.bean.QinMediaInfo";
    private static final String POLICY = "com.qinggan.media.helper.AudioPolicyHelper";
    private static final String NATIVE = "ru.big.town.anative";
    private static final Uri SNAPSHOT = Uri.parse("content://" + NATIVE + ".nowplaying/oem");

    private final Context context;
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<TextView, CharSequence> originalTitles = new WeakHashMap<>();
    private final Map<View, Integer> originalVisibility = new WeakHashMap<>();
    private final Map<ImageView, Drawable> originalIcons = new WeakHashMap<>();
    private final Map<Object, Set<Object>> extendedWidgetSources = new WeakHashMap<>();
    private final Map<Object, List<Object>> originalSourceMenus = new WeakHashMap<>();
    private volatile OemNowPlaying current;
    private Object source;
    private Object sourceMenuEntry;
    private Object manager;
    private Object control;
    private Object fallback;
    private Object originalService;
    private Object originalPackage;
    private String iconPackage;
    private Drawable.ConstantState iconState;
    private boolean selected;
    private boolean replayPending;

    public OemMediaModule(Context context, MethodAccess methods, HookRegistry hooks) {
        this.context = context;
        this.methods = methods;
        this.hooks = hooks;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        // Reuse the online-music slot only when the OEM does not expose Kuwo.
        source = OemAccess.read(methods.type(ENUM), "KW_MUSIC");
        Object config = OemAccess.call(methods.type(CONFIG), "getInstance");
        if (OemAccess.read(methods.type("com.qinggan.app.service.utils.ContextUtils"), "context")
                == null) {
            OemAccess.call(
                    config,
                    "init",
                    context,
                    OemAccess.construct(
                            methods.type("com.qinggan.media.helper.impl.AsgardMediaConfigImpl")));
        }
        if (((Set<?>) OemAccess.call(config, "getSupportMedias")).contains(source)) {
            throw new UnsupportedOperationException("OEM Kuwo slot is already in use");
        }
        methods.require(MANAGER, "setCurMediaEnum", ENUM, "java.lang.Object");
        methods.require(MANAGER, "onMediaInfoChange", ENUM, INFO, "boolean", "java.lang.Object");
        methods.require(
                MANAGER,
                "onMediaStateChange",
                ENUM,
                "boolean",
                INFO,
                "boolean",
                "java.lang.Object");
        methods.require(CONFIG, "getSupportMedias");
        methods.require(CONFIG, "getControlMedias");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (String name : List.of("getSupportMedias", "getControlMedias")) {
            hook(
                    scope,
                    CONFIG,
                    name,
                    invocation -> {
                        Set<Object> supported = new LinkedHashSet<>((Set<?>) invocation.proceed());
                        supported.add(source);
                        return supported;
                    });
        }
        for (String name : List.of("setCurMediaEnum", "onMediaTypeChange")) {
            hook(
                    scope,
                    MANAGER,
                    name,
                    invocation -> {
                        if (active() && invocation.arguments()[0] != source) {
                            fallback = invocation.arguments()[0];
                            invocation.arguments()[0] = source;
                        }
                        return invocation.proceed();
                    },
                    ENUM,
                    "java.lang.Object");
        }
        hook(
                scope,
                MANAGER,
                "getCurMediaType",
                invocation -> active() ? source : invocation.proceed(),
                "java.lang.Object");
        hook(
                scope,
                POLICY,
                "getMediaEnumByInfo",
                invocation -> active() ? source : invocation.proceed(),
                "com.qinggan.audiopolicy.AudioPolicyInfo");
        hook(
                scope,
                MANAGER,
                "onMediaInfoChange",
                invocation ->
                        invocation.arguments()[0] == source && !active()
                                ? null
                                : invocation.proceed(),
                ENUM,
                INFO,
                "boolean",
                "java.lang.Object");
        hook(
                scope,
                MANAGER,
                "onMediaStateChange",
                invocation ->
                        invocation.arguments()[0] == source && !active()
                                ? null
                                : invocation.proceed(),
                ENUM,
                "boolean",
                INFO,
                "boolean",
                "java.lang.Object");
        hook(
                scope,
                "com.qinggan.app.mediaCentre.widget.BaseWidgetHelper",
                "updateMediaEnum",
                invocation -> {
                    if (active() && invocation.arguments()[0] == source) {
                        @SuppressWarnings("unchecked")
                        Set<Object> supported =
                                (Set<Object>) OemAccess.read(invocation.receiver(), "supportSet");
                        // The private membership check can be AOT-inlined; update its actual
                        // backing set.
                        if (supported.add(source)) {
                            extendedWidgetSources.put(invocation.receiver(), supported);
                        }
                    }
                    return invocation.proceed();
                },
                ENUM);
        installViewSupport(scope);
        installSourceMenu(scope);
        installPopupArtwork(scope);
        installNavigation(scope);
        scope.own(() -> AndroidMainThread.run(this::restore));
        AndroidMainThread.run(
                () -> {
                    originalService = OemAccess.read(source, "serviceName");
                    originalPackage = OemAccess.read(source, "servicePageName");
                    OemAccess.write(source, "serviceName", NATIVE + ".OemMediaBrowserService");
                    OemAccess.write(source, "servicePageName", NATIVE);
                });
    }

    private void installViewSupport(ModuleScope scope) throws Exception {
        for (String type :
                List.of(
                        "com.qinggan.app.mediaCentre.view.BaseMediaView",
                        "com.pateo.voyah.mediaCard.home.view.HomeBaseView",
                        "com.qinggan.cardview.panel.media.MusicBaseView")) {
            Method method = optional(type, "getMediaEnums");
            if (method == null) {
                continue;
            }
            scope.own(
                    hooks.install(
                            "oem-media",
                            method,
                            scope.callback(
                                    invocation -> {
                                        List<Object> supported =
                                                new ArrayList<>((List<?>) invocation.proceed());
                                        if (!supported.contains(source)) {
                                            supported.add(source);
                                        }
                                        return supported;
                                    })));
        }
        Method title =
                optional("com.qinggan.cardview.panel.media.MusicBaseView", "changeTitle", ENUM);
        if (title != null) {
            scope.own(
                    hooks.install(
                            "oem-media",
                            title,
                            scope.callback(
                                    invocation -> {
                                        Object result = invocation.proceed();
                                        updateHeader(
                                                (TextView)
                                                        OemAccess.read(
                                                                invocation.receiver(), "mTvTitle"),
                                                (View)
                                                        OemAccess.read(
                                                                invocation.receiver(), "mIvType"),
                                                invocation.arguments()[0] == source);
                                        return result;
                                    })));
        }
        for (String type :
                List.of(
                        "com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2",
                        "com.pateo.voyah.mediaCard.home.h97c.BigMediaView97cV1",
                        "com.pateo.voyah.mediaCard.home.view.HomeBaseView")) {
            boolean baseView = type.endsWith("HomeBaseView");
            List<Method> updates;
            try {
                updates = methods.overloads(type, baseView ? "changeMediaType" : "updateTitleUI");
            } catch (ClassNotFoundException absent) {
                continue;
            }
            for (Method update : updates) {
                if (update.getParameterCount() != 1) {
                    continue;
                }
                scope.own(
                        hooks.install(
                                "oem-media",
                                update,
                                scope.callback(
                                        invocation -> {
                                            Object result = invocation.proceed();
                                            Object owner =
                                                    baseView
                                                            ? invocation.receiver()
                                                            : OemAccess.read(
                                                                    invocation.receiver(),
                                                                    "binding");
                                            updateHeader(
                                                    (TextView)
                                                            OemAccess.read(
                                                                    owner,
                                                                    baseView
                                                                            ? "mediaName"
                                                                            : "tvMediaName"),
                                                    (View) OemAccess.read(owner, "mediaIcon"),
                                                    invocation.arguments()[0] != null
                                                            && OemAccess.call(
                                                                            invocation
                                                                                    .arguments()[0],
                                                                            "getMediaEnum")
                                                                    == source);
                                            return result;
                                        })));
            }
        }
    }

    private void updateHeader(TextView title, View icon, boolean mirrored) {
        if (active() && mirrored) {
            if (title != null) {
                originalTitles.put(title, title.getText());
                title.setText(current.appLabel);
            }
            if (icon != null) {
                originalVisibility.putIfAbsent(icon, icon.getVisibility());
                if (icon instanceof ImageView) {
                    ImageView image = (ImageView) icon;
                    originalIcons.put(image, image.getDrawable());
                    image.setImageDrawable(mediaSourceIcon());
                }
                icon.setVisibility(View.VISIBLE);
            }
        } else {
            originalTitles.remove(title);
            originalIcons.remove(icon);
            Integer visibility = originalVisibility.remove(icon);
            if (visibility != null) {
                icon.setVisibility(visibility);
            }
        }
    }

    private void restoreHeaders() {
        originalTitles.forEach(TextView::setText);
        originalIcons.forEach(ImageView::setImageDrawable);
        originalVisibility.forEach(View::setVisibility);
        originalTitles.clear();
        originalIcons.clear();
        originalVisibility.clear();
    }

    private Drawable mediaSourceIcon() {
        String packageName = current.packageName;
        if (packageName.equals(iconPackage) && iconState != null) {
            return iconState.newDrawable(context.getResources()).mutate();
        }
        PackageManager packages = context.getPackageManager();
        Drawable drawable;
        try {
            drawable = packages.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException absent) {
            drawable = packages.getDefaultActivityIcon();
        }
        iconPackage = packageName;
        iconState = drawable.getConstantState();
        return drawable.mutate();
    }

    private void installSourceMenu(ModuleScope scope) throws Exception {
        for (String type :
                List.of(
                        "com.pateo.voyah.mediaCard.home.h97c.BigMediaView97cV1",
                        "com.pateo.voyah.mediaCard.home.view.BigMediaCard",
                        "com.pateo.voyah.mediaCard.home.h97cV2.BigMediaView97cV2")) {
            Method show = optional(type, "showSrcTab", "boolean");
            if (show == null) {
                continue;
            }
            scope.own(
                    hooks.install(
                            "oem-media",
                            show,
                            scope.callback(
                                    invocation -> {
                                        if (Boolean.TRUE.equals(invocation.arguments()[0])) {
                                            Object binding =
                                                    OemAccess.read(
                                                            invocation.receiver(), "binding");
                                            Object menu = OemAccess.read(binding, "mediaTab");
                                            Object adapter =
                                                    OemAccess.read(menu, "mediaTabAdapter");
                                            List<Object> entries =
                                                    new ArrayList<>(
                                                            (List<?>)
                                                                    OemAccess.read(
                                                                            adapter, "data"));
                                            entries.removeIf(entry -> entry == sourceMenuEntry);
                                            originalSourceMenus.put(
                                                    adapter, new ArrayList<>(entries));
                                            if (active()) {
                                                if (sourceMenuEntry == null) {
                                                    sourceMenuEntry =
                                                            OemAccess.read(
                                                                    methods.type(
                                                                            "com.pateo.voyah.mediaCard.home.enums.MediaResEnum"),
                                                                    "KW_MUSIC");
                                                }
                                                if (!entries.contains(sourceMenuEntry)) {
                                                    entries.add(0, sourceMenuEntry);
                                                }
                                            }
                                            OemAccess.call(adapter, "fillData", entries);
                                        }
                                        return invocation.proceed();
                                    })));
            Method click =
                    optional(
                            type,
                            "onItemClick",
                            "com.pateo.voyah.mediaCard.home.inter.MediaBeanInter");
            if (click != null) {
                scope.own(
                        hooks.install(
                                "oem-media",
                                click,
                                scope.callback(
                                        invocation -> {
                                            if (active()
                                                    && OemAccess.call(
                                                                    invocation.arguments()[0],
                                                                    "getMediaEnum")
                                                            == source) {
                                                OemAccess.call(
                                                        invocation.receiver(), "showSrcTab", false);
                                                OemAccess.call(
                                                        manager, "onMediaTypeChange", source, this);
                                                OemAccess.call(manager, "play", source, this);
                                                return null;
                                            }
                                            return invocation.proceed();
                                        })));
            }
        }
        for (String type :
                List.of(
                        "com.pateo.voyah.mediaCard.home.view.mediaTab.MediaTabAdapterV1$MediaTabHolder",
                        "com.pateo.voyah.mediaCard.home.view.mediaTab.MediaTabAdapter$MediaTabHolder")) {
            Method bind = optional(type, "bindView", "int");
            if (bind == null) {
                continue;
            }
            scope.own(
                    hooks.install(
                            "oem-media",
                            bind,
                            scope.callback(
                                    invocation -> {
                                        Object result = invocation.proceed();
                                        Object entry =
                                                OemAccess.read(invocation.receiver(), "mediaBean");
                                        if (active()
                                                && OemAccess.call(entry, "getMediaEnum")
                                                        == source) {
                                            ((TextView)
                                                            OemAccess.read(
                                                                    invocation.receiver(),
                                                                    "tvName"))
                                                    .setText(current.appLabel);
                                            ((ImageView)
                                                            OemAccess.read(
                                                                    invocation.receiver(),
                                                                    "ivIcon"))
                                                    .setImageDrawable(mediaSourceIcon());
                                        }
                                        return result;
                                    })));
        }
    }

    private void installPopupArtwork(ModuleScope scope) throws Exception {
        Method request =
                optional(
                        "com.pateo.voyah.mediaLand.isLand.view.IsLandMediaClient",
                        "reqIsLand",
                        ENUM,
                        INFO);
        if (request == null) {
            return;
        }
        scope.own(
                hooks.install(
                        "oem-media",
                        request,
                        scope.callback(
                                invocation -> {
                                    Object next = invocation.arguments()[1];
                                    if (active()
                                            && current.rememberedSource
                                            && invocation.arguments()[0] == source) {
                                        return null;
                                    }
                                    if (active()
                                            && invocation.arguments()[0] == source
                                            && next != null) {
                                        Object previous =
                                                OemAccess.read(
                                                        invocation.receiver(), "curMediaInfo");
                                        if (previous != null
                                                && Boolean.TRUE.equals(
                                                        OemAccess.call(
                                                                next, "isEqual", source,
                                                                previous))) {
                                            Object artwork = OemAccess.call(next, "getCoverUrl");
                                            if (!Objects.equals(
                                                    artwork,
                                                    OemAccess.call(previous, "getCoverUrl"))) {
                                                // Refresh late artwork without restarting the OEM
                                                // popup's display timeout.
                                                OemAccess.call(
                                                        invocation.receiver(),
                                                        "updateCover",
                                                        artwork,
                                                        source);
                                                OemAccess.write(
                                                        invocation.receiver(),
                                                        "curMediaInfo",
                                                        next);
                                            }
                                        }
                                    }
                                    return invocation.proceed();
                                })));
    }

    private void installNavigation(ModuleScope scope) throws Exception {
        Method open =
                optional("com.pateo.voyah.mediaCard.home.view.HomeBaseView", "toMediaHomePage");
        if (open != null) {
            scope.own(
                    hooks.install(
                            "oem-media",
                            open,
                            scope.callback(
                                    invocation -> {
                                        if (active()
                                                && OemAccess.call(
                                                                invocation.receiver(),
                                                                "getCurMediaEnum")
                                                        == source) {
                                            launchCurrentPlayer();
                                            return null;
                                        }
                                        return invocation.proceed();
                                    })));
        }
        hook(
                scope,
                "com.qinggan.media.helper.app.MediaJumpUtils",
                "toMediaHomePage",
                invocation -> {
                    if (!active() || invocation.arguments()[1] != source) {
                        return invocation.proceed();
                    }
                    launchCurrentPlayer();
                    return null;
                },
                "android.content.Context",
                ENUM,
                "boolean");
        hook(
                scope,
                "com.qinggan.media.helper.utils.SystemUtils",
                "isMediaForegroundByMedia",
                invocation -> {
                    if (active() && invocation.arguments()[1] == source) {
                        return OemAccess.call(
                                methods.type("com.qinggan.media.helper.utils.SystemUtils"),
                                "isAppTop",
                                current.packageName,
                                invocation.arguments()[0]);
                    }
                    return invocation.proceed();
                },
                "java.lang.String",
                ENUM);
    }

    private void launchCurrentPlayer() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(current.packageName);
        if (intent != null) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(0);
            context.startActivity(
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle());
        }
    }

    private void hook(
            ModuleScope scope,
            String owner,
            String name,
            HookRegistry.Callback callback,
            String... arguments)
            throws Exception {
        scope.own(
                hooks.install(
                        "oem-media",
                        methods.require(owner, name, arguments),
                        scope.callback(callback)));
    }

    private Method optional(String owner, String name, String... arguments) throws Exception {
        try {
            return methods.optional(owner, name, arguments);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.schedule(0, () -> refresh(scope));
    }

    private void refresh(ModuleScope scope) {
        OemNowPlaying snapshot = null;
        try (Cursor cursor = context.getContentResolver().query(SNAPSHOT, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                snapshot =
                        new OemNowPlaying(
                                text(cursor, "package"),
                                text(cursor, "title"),
                                text(cursor, "artist"),
                                text(cursor, "appLabel"),
                                cursor.getInt(cursor.getColumnIndexOrThrow("state")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("hasArt")) != 0,
                                cursor.getInt(cursor.getColumnIndexOrThrow("rememberedSource"))
                                        != 0);
            }
        } catch (Exception unavailable) {
            Log.w(TAG, "Native media snapshot unavailable", unavailable);
        }
        OemNowPlaying next = snapshot;
        main.post(
                scope.guard(
                        () -> {
                            try {
                                accept(next);
                            } catch (Exception failure) {
                                Log.w(TAG, "Cannot update OEM media source", failure);
                                scope.requireRestart(failure);
                            }
                        }));
        try {
            scope.schedule(500, () -> refresh(scope));
        } catch (Exception failure) {
            Log.w(TAG, "Cannot schedule media refresh", failure);
        }
    }

    private void accept(OemNowPlaying next) throws Exception {
        if (manager == null) {
            manager = OemAccess.call(methods.type(MANAGER), "getInstance");
            Map<?, ?> controls = (Map<?, ?>) OemAccess.read(manager, "controlMap");
            control = controls.get(source);
            if (control == null) {
                control =
                        OemAccess.construct(
                                methods.type(
                                        "com.qinggan.app.mediaCentre.manager.MediaControlManager"),
                                context,
                                source);
                OemAccess.call(control, "addQinMediaListener", manager);
                OemAccess.call(manager, "addMediaControl", control);
            }
        }
        boolean wasActive = active();
        if (!wasActive && next != null && next.overridesOem()) {
            fallback = OemAccess.call(manager, "getCurMediaType");
        }
        boolean labelChanged =
                current == null
                        || next == null
                        || !current.appLabel.equals(next.appLabel)
                        || !current.packageName.equals(next.packageName);
        current = next;
        if (active()) {
            if (labelChanged) {
                originalTitles.keySet().forEach(title -> title.setText(current.appLabel));
                originalIcons.keySet().forEach(icon -> icon.setImageDrawable(mediaSourceIcon()));
            }
            if (!selected) {
                selected = true;
                replayPending = true;
                OemAccess.call(manager, "onMediaTypeChange", source, this);
            }
            if (replayPending) {
                replayPending = !replay();
            }
        } else if (selected) {
            handBack();
        }
    }

    private boolean replay() throws Exception {
        Object browser = OemAccess.call(control, "getMediaBrowserHelper");
        if (!Boolean.TRUE.equals(OemAccess.call(browser, "isConnected"))) {
            return false;
        }
        Object controller = OemAccess.call(browser, "getMediaController");
        if (controller == null) {
            return false;
        }
        Object callback = OemAccess.read(browser, "mMediaControllerCallback");
        OemAccess.call(callback, "onMetadataChanged", OemAccess.call(controller, "getMetadata"));
        OemAccess.call(
                callback, "onPlaybackStateChanged", OemAccess.call(controller, "getPlaybackState"));
        return true;
    }

    private boolean active() {
        OemNowPlaying snapshot = current;
        return snapshot != null && snapshot.overridesOem();
    }

    private void handBack() throws Exception {
        restoreHeaders();
        extendedWidgetSources.values().forEach(supported -> supported.remove(source));
        extendedWidgetSources.clear();
        selected = false;
        replayPending = false;
        Object target =
                fallback == null || fallback == source
                        ? OemAccess.read(methods.type(ENUM), "NO")
                        : fallback;
        OemAccess.write(
                manager,
                "curMediaInfo",
                OemAccess.call(manager, "getMediaInfoByType", target, this));
        OemAccess.call(manager, "onMediaTypeChange", target, this);
    }

    private void restore() throws Exception {
        current = null;
        restoreHeaders();
        if (manager != null && selected) {
            handBack();
        }
        extendedWidgetSources.values().forEach(supported -> supported.remove(source));
        extendedWidgetSources.clear();
        for (Map.Entry<Object, List<Object>> menu : originalSourceMenus.entrySet()) {
            OemAccess.call(menu.getKey(), "fillData", menu.getValue());
        }
        originalSourceMenus.clear();
        if (control != null) {
            OemAccess.call(OemAccess.call(control, "getMediaBrowserHelper"), "onStop");
            ((Map<?, ?>) OemAccess.read(manager, "controlMap")).remove(source);
        }
        if (originalService != null) {
            OemAccess.write(source, "serviceName", originalService);
            OemAccess.write(source, "servicePageName", originalPackage);
        }
    }

    private static String text(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }
}

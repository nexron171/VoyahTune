package ru.big.town.restoremode.settings.sections.voice;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.CustomVoiceCommandProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsActionPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.voice.commands.VoiceCommandCatalog;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.voice.commands.VoiceCustomCommandStore;

import java.util.List;

final class VoiceCustomCommandsSection {
    private final AppCompatActivity activity;
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final SettingsComponents components;

    VoiceCustomCommandsSection(
            AppCompatActivity activity, SharedPreferences preferences, Runnable changed) {
        this.activity = activity;
        this.preferences = preferences;
        this.changed = changed;
        components = new SettingsComponents(activity);
    }

    View create() {
        TextView description =
                components.text(
                        "Задайте фразу и действия, которые выполнятся по порядку."
                                + " Произносите фразу целиком. Новые фразы учитываются со"
                                + " следующего прослушивания.",
                        17,
                        0xffa2b0c5);
        description.setLineSpacing(0, 1.35f);
        LinearLayout panel = components.card(description);
        Button add = button("Добавить голосовую команду");
        add.setTag("customVoice:add");
        add.setOnClickListener(view -> edit(new VoiceCustomCommandStore.Entry()));
        addBelow(panel, add, 20);
        for (VoiceCustomCommandStore.Entry entry : VoiceCustomCommandStore.load(preferences)) {
            LinearLayout row = horizontal();
            TextView phrase =
                    components.text(
                            "«" + entry.phrase + "»\nДействий: " + entry.actions.size(),
                            20,
                            0xffcedef3);
            LinearLayout.LayoutParams phraseSize = new LinearLayout.LayoutParams(0, -2, 1);
            phraseSize.setMarginEnd(components.dp(20));
            row.addView(phrase, phraseSize);
            Switch enabled = new SettingsToggle(activity);
            enabled.setContentDescription("Включить команду «" + entry.phrase + "»");
            enabled.setChecked(entry.enabled);
            enabled.setOnCheckedChangeListener(
                    (view, checked) -> {
                        entry.enabled = checked;
                        VoiceCustomCommandStore.save(preferences, entry);
                        changed.run();
                    });
            row.addView(enabled);
            Button edit = button("Изменить");
            edit.setTag("customVoice:edit:" + entry.id);
            edit.setOnClickListener(view -> edit(entry));
            addBeside(row, edit, 16);
            ImageButton remove =
                    iconButton(
                            R.drawable.settings_icon_trash,
                            "Удалить команду «" + entry.phrase + "»",
                            true);
            remove.setOnClickListener(
                    view -> {
                        VoiceCustomCommandStore.remove(preferences, entry.id);
                        changed.run();
                    });
            addBeside(row, remove, 12);
            addBelow(panel, row, 24);
        }
        return panel;
    }

    private void edit(VoiceCustomCommandStore.Entry original) {
        VoiceCustomCommandStore.Entry draft = new VoiceCustomCommandStore.Entry();
        draft.id = original.id;
        draft.phrase = original.phrase;
        draft.enabled = original.enabled;
        draft.actions.addAll(original.actions);
        List<VoiceCommandCatalog.Command> catalog = VoiceCommands.load(activity);
        List<VoiceCustomCommandStore.Entry> entries = VoiceCustomCommandStore.load(preferences);

        LinearLayout content = vertical();
        content.setPadding(
                components.dp(24), components.dp(24), components.dp(24), components.dp(24));
        content.addView(components.text("Голосовая фраза", 20, 0xffcedef3));
        EditText phrase = new EditText(activity);
        phrase.setTag("customVoice:phrase");
        phrase.setHint("Например, «едем домой»");
        phrase.setContentDescription("Собственная голосовая фраза");
        phrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        phrase.setFilters(
                new InputFilter[] {
                    new InputFilter.LengthFilter(VoiceCustomCommandStore.MAX_PHRASE_LENGTH)
                });
        phrase.setText(draft.phrase);
        LinearLayout.LayoutParams phraseSize = new LinearLayout.LayoutParams(-1, -2);
        phraseSize.topMargin = components.dp(12);
        content.addView(phrase, phraseSize);
        addBelow(content, components.text("Последовательность действий", 20, 0xffcedef3), 28);
        LinearLayout actions = vertical();
        addBelow(content, actions, 16);
        Button add = button("Добавить действие");
        add.setTag("customVoice:addAction");
        addBelow(content, add, 16);
        TextView error = components.text("", 17, 0xffff8a80);
        error.setTag("customVoice:error");
        addBelow(content, error, 12);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog =
                new MaterialAlertDialogBuilder(activity, R.style.SettingsDialog)
                        .setTitle("Собственная голосовая команда")
                        .setView(scroll)
                        .setPositiveButton("Сохранить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    Runnable validate =
                            () -> {
                                draft.phrase = phrase.getText().toString().trim();
                                String message =
                                        VoiceCustomCommandStore.validate(draft, entries, catalog);
                                error.setText(message == null ? "" : message);
                                error.setVisibility(message == null ? View.GONE : View.VISIBLE);
                                save.setEnabled(message == null);
                                add.setEnabled(
                                        draft.actions.size()
                                                < CustomVoiceCommandProtocol.MAX_ACTIONS);
                            };
                    Runnable[] render = new Runnable[1];
                    render[0] =
                            () -> {
                                actions.removeAllViews();
                                actions.setVisibility(
                                        draft.actions.isEmpty() ? View.GONE : View.VISIBLE);
                                for (int index = 0; index < draft.actions.size(); index++) {
                                    int at = index;
                                    LinearLayout row = horizontal();
                                    row.setBackgroundResource(R.drawable.settings_sequence);
                                    row.setPadding(
                                            components.dp(12),
                                            components.dp(12),
                                            components.dp(12),
                                            components.dp(12));
                                    row.addView(
                                            components.text(
                                                    (at + 1)
                                                            + ". "
                                                            + SettingsActionPicker.label(
                                                                    activity,
                                                                    preferences,
                                                                    draft.actions.get(at)),
                                                    18,
                                                    0xffcedef3),
                                            new LinearLayout.LayoutParams(0, -2, 1));
                                    ImageButton up =
                                            iconButton(
                                                    R.drawable.settings_icon_move_up,
                                                    "Поднять действие " + (at + 1),
                                                    false);
                                    up.setEnabled(at > 0);
                                    up.setOnClickListener(
                                            view -> {
                                                java.util.Collections.swap(
                                                        draft.actions, at, at - 1);
                                                render[0].run();
                                            });
                                    addBeside(row, up, 16);
                                    ImageButton down =
                                            iconButton(
                                                    R.drawable.settings_icon_move_down,
                                                    "Опустить действие " + (at + 1),
                                                    false);
                                    down.setEnabled(at < draft.actions.size() - 1);
                                    down.setOnClickListener(
                                            view -> {
                                                java.util.Collections.swap(
                                                        draft.actions, at, at + 1);
                                                render[0].run();
                                            });
                                    addBeside(row, down, 12);
                                    ImageButton remove =
                                            iconButton(
                                                    R.drawable.settings_icon_trash,
                                                    "Удалить действие " + (at + 1),
                                                    true);
                                    remove.setOnClickListener(
                                            view -> {
                                                draft.actions.remove(at);
                                                render[0].run();
                                            });
                                    addBeside(row, remove, 12);
                                    addBelow(actions, row, at == 0 ? 0 : 12);
                                }
                                validate.run();
                            };
                    phrase.addTextChangedListener(
                            new TextWatcher() {
                                public void beforeTextChanged(
                                        CharSequence text, int start, int count, int after) {}

                                public void onTextChanged(
                                        CharSequence text, int start, int before, int count) {}

                                public void afterTextChanged(Editable text) {
                                    validate.run();
                                }
                            });
                    add.setOnClickListener(
                            view ->
                                    SettingsActionPicker.show(
                                            activity,
                                            preferences,
                                            content,
                                            action -> {
                                                draft.actions.add(action);
                                                render[0].run();
                                            }));
                    save.setOnClickListener(
                            view -> {
                                String message =
                                        VoiceCustomCommandStore.validate(
                                                draft,
                                                VoiceCustomCommandStore.load(preferences),
                                                catalog);
                                if (message != null) {
                                    error.setText(message);
                                    return;
                                }
                                VoiceCustomCommandStore.save(preferences, draft);
                                dialog.dismiss();
                                changed.run();
                            });
                    render[0].run();
                });
        dialog.show();
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private Button button(String title) {
        MaterialButton button = new MaterialButton(activity);
        button.setText(title);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private ImageButton iconButton(int icon, String description, boolean destructive) {
        AppCompatImageButton button = new AppCompatImageButton(activity);
        button.setContentDescription(description);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(
                components.dp(12), components.dp(12), components.dp(12), components.dp(12));
        button.setLayoutParams(new LinearLayout.LayoutParams(components.dp(56), components.dp(56)));
        button.setImageTintList(
                new ColorStateList(
                        new int[][] {{-android.R.attr.state_enabled}, {}},
                        new int[] {0xff7a899e, destructive ? 0xffffb5ad : 0xffd6e7ff}));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[] {-android.R.attr.state_enabled}, circle(0xff394454, 0xff4a586c));
        surface.addState(
                new int[] {},
                circle(
                        destructive ? 0xff543b42 : 0xff354d6c,
                        destructive ? 0xff94626a : 0xff617fa7));
        button.setBackgroundTintList(null);
        button.setSupportBackgroundTintList(null);
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x30ffffff),
                        surface,
                        circle(0xffffffff, 0xffffffff)));
        return button;
    }

    private GradientDrawable circle(int fill, int stroke) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(fill);
        circle.setStroke(components.dp(1), stroke);
        return circle;
    }

    private void addBelow(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size =
                child.getLayoutParams() instanceof LinearLayout.LayoutParams
                        ? (LinearLayout.LayoutParams) child.getLayoutParams()
                        : new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = components.dp(gap);
        parent.addView(child, size);
    }

    private void addBeside(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) child.getLayoutParams();
        size.setMarginStart(components.dp(gap));
        parent.addView(child, size);
    }
}

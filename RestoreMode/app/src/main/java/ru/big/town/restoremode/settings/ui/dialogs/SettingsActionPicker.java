package ru.big.town.restoremode.settings.ui.dialogs;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.vehicle.steering.SteeringActions;
import ru.big.town.restoremode.vehicle.steering.SteeringCanCommandPolicy;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;

import java.util.List;
import java.util.function.Consumer;

public final class SettingsActionPicker {
    private final Context context;
    private final SharedPreferences preferences;
    private final View anchor;
    private final Consumer<String> onPicked;

    private SettingsActionPicker(
            Context context,
            SharedPreferences preferences,
            View anchor,
            Consumer<String> onPicked) {
        this.context = context;
        this.preferences = preferences;
        this.anchor = anchor;
        this.onPicked = onPicked;
    }

    public static void show(
            Context context,
            SharedPreferences preferences,
            View anchor,
            Consumer<String> onPicked) {
        new SettingsActionPicker(context, preferences, anchor, onPicked).pick();
    }

    private void pick() {
        final int staticCount = SteeringActions.ALL.size();
        final CharSequence[] labels = new CharSequence[staticCount + 4];
        for (int i = 0; i < staticCount; i++) {
            labels[i] = SteeringActions.ALL.get(i).label;
        }
        labels[staticCount] = "Открыть сплит…";
        labels[staticCount + 1] = "Открыть приложение…";
        labels[staticCount + 2] = "Набрать номер…";
        labels[staticCount + 3] = "Своя CAN-команда…";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        context, R.style.SettingsDialog)
                .setTitle("Добавить действие")
                .setItems(
                        labels,
                        (d, which) -> {
                            if (which < staticCount) {
                                onPicked.accept(SteeringActions.ALL.get(which).id);
                            } else if (which == staticCount) {
                                pickSplit();
                            } else if (which == staticCount + 1) {
                                pickApp();
                            } else if (which == staticCount + 2) {
                                pickDial();
                            } else {
                                pickCanCommand();
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickDial() {
        List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(
                            context,
                            "Сначала создайте карточку набора номера",
                            android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            DialWidgetStore.Entry entry = entries.get(i);
            labels[i] = (entry.name.isEmpty() ? "Без имени" : entry.name) + " — " + entry.number;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        context, R.style.SettingsDialog)
                .setTitle("Выбрать номер")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            String number = entries.get(which).number.replaceAll("[^0-9]", "");
                            if (number.length() >= 4 && number.length() <= 10) {
                                if (number.length() == 10) {
                                    number = "8" + number;
                                }
                                onPicked.accept("call:" + number);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSplit() {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset preset = all.get(i);
            if (preset.ready()) {
                readyIdx.add(i);
                labels.add(
                        (preset.ll.isEmpty() ? preset.l : preset.ll)
                                + "  /  "
                                + (preset.rl.isEmpty() ? preset.r : preset.rl));
            }
        }
        if (readyIdx.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(
                            anchor,
                            "Нет готовых сплитов — сначала настройте сплит в «Приложения и "
                                    + "разделение экрана»",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        context, R.style.SettingsDialog)
                .setTitle("Открыть сплит")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (d, which) -> onPicked.accept("split:" + readyIdx.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickApp() {
        SettingsAppPicker.show(
                context,
                "Открыть приложение",
                (packageName, label) -> onPicked.accept("app:" + packageName));
    }

    private void pickCanCommand() {
        View content =
                LayoutInflater.from(context)
                        .inflate(R.layout.dialog_steering_can_command, null, false);
        EditText editor = content.findViewById(R.id.steerCanCommandInput);
        TextView error = content.findViewById(R.id.steerCanCommandError);
        AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                context, R.style.SettingsDialog)
                        .setTitle("Своя CAN-команда")
                        .setView(content)
                        .setPositiveButton("Добавить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    Button add = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    TextWatcher watcher =
                            new TextWatcher() {
                                private boolean formatting;

                                @Override
                                public void beforeTextChanged(
                                        CharSequence s, int start, int count, int after) {}

                                @Override
                                public void onTextChanged(
                                        CharSequence s, int start, int before, int count) {
                                    if (formatting) {
                                        return;
                                    }
                                    String formatted =
                                            SteeringCanCommandPolicy.format(s.toString());
                                    if (!formatted.contentEquals(s)) {
                                        formatting = true;
                                        editor.setText(formatted);
                                        editor.setSelection(formatted.length());
                                        formatting = false;
                                    }
                                    updateCustomCanValidation(editor, error, add);
                                }

                                @Override
                                public void afterTextChanged(Editable s) {}
                            };
                    editor.addTextChangedListener(watcher);
                    updateCustomCanValidation(editor, error, add);
                    add.setOnClickListener(
                            v -> {
                                if (!SteeringCanCommandPolicy.isValid(
                                        editor.getText().toString())) {
                                    return;
                                }
                                onPicked.accept(
                                        SteeringCanCommandPolicy.actionId(
                                                editor.getText().toString()));
                                dialog.dismiss();
                            });
                    editor.requestFocus();
                });
        dialog.show();
    }

    private void updateCustomCanValidation(EditText editor, TextView error, Button add) {
        String compact = SteeringCanCommandPolicy.compact(editor.getText().toString());
        boolean valid = compact.length() == SteeringCanCommandPolicy.HEX_LENGTH;
        editor.setBackgroundColor(valid ? Color.WHITE : 0xffffafaf);
        error.setText(
                valid
                        ? "Команда готова"
                        : "Нужно 20 hex-символов (10 байт). Сейчас: " + compact.length());
        error.setTextColor(valid ? 0xff8bc9a3 : 0xffff8a80);
        add.setEnabled(valid);
        add.setAlpha(valid ? 1f : 0.4f);
    }

    public static String label(Context context, SharedPreferences preferences, String id) {
        if (id == null || id.isEmpty() || "none".equals(id)) {
            return "Не менять";
        }
        for (SteeringActions.Action action : SteeringActions.ALL) {
            if (action.id.equals(id)) {
                return action.label;
            }
        }
        if (id.startsWith("split:")) {
            try {
                int n = Integer.parseInt(id.substring("split:".length()));
                java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
                if (n >= 0 && n < all.size()) {
                    SplitStore.Preset preset = all.get(n);
                    return "Сплит: "
                            + (preset.ll.isEmpty() ? preset.l : preset.ll)
                            + " / "
                            + (preset.rl.isEmpty() ? preset.r : preset.rl);
                }
            } catch (Exception ignored) {
            }
            return "Сплит (не найден)";
        }
        if (id.startsWith("app:")) {
            String packageName = id.substring("app:".length());
            try {
                android.content.pm.PackageManager packageManager = context.getPackageManager();
                return "Приложение: "
                        + packageManager
                                .getApplicationLabel(
                                        packageManager.getApplicationInfo(packageName, 0))
                                .toString();
            } catch (Exception e) {
                return "Приложение: " + packageName;
            }
        }
        if (id.startsWith("call:")) {
            return "Набрать номер: " + id.substring("call:".length());
        }
        if (id.startsWith("can:")) {
            String command = id.substring("can:".length());
            return command.length() == SteeringCanCommandPolicy.HEX_LENGTH
                    ? "Своя команда: " + SteeringCanCommandPolicy.format(command)
                    : "Своя команда (неверный формат)";
        }
        return "Неизвестное действие: " + id;
    }
}

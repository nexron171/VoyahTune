package ru.big.town.restoremode.settings.sections.can;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.vehicle.steering.SteeringCanCommandPolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CanSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.CAN;
    }

    private EditText canCommandsEditor;

    private NumberPicker pickerCustomCommandCount;

    private final List<ImageButton> deleteButtons = new ArrayList<>();

    private void onButtonClickClean(View v) {
        commands.setText("");
        if (canCommandsEditor != null) {
            canCommandsEditor.setText("");
        }
    }

    private void refreshCanStatus() {
        TextView status = settingView(R.id.settingsCanStatus);
        if (status == null || canCommandsEditor == null) {
            return;
        }
        boolean valid =
                java.util.Arrays.stream(commands.text().split("\\n"))
                        .allMatch(
                                line ->
                                        line.trim().isEmpty()
                                                || SteeringCanCommandPolicy.isValid(line));
        status.setText(
                commands.text().trim().isEmpty()
                        ? "Пока нет команд"
                        : valid
                                ? "Формат корректен"
                                : "Проверьте строки: в каждой должно быть ровно 10 байт");
        status.setTextColor(valid ? 0xffa3d3bb : 0xfff4b5b5);
    }

    private void buildExampleButtons() {
        LinearLayout container = settingView(R.id.examplesContainer);
        if (container == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (String[] pair : CanCommandExamples.COMMANDS) {
            final String hex = pair[0];
            final String label = pair[1];
            View row = inflater.inflate(R.layout.item_command, container, false);
            Button commandButton = row.findViewById(R.id.cmdButton);
            ImageButton deleteButton = row.findViewById(R.id.cmdDelete);
            android.text.SpannableString example =
                    new android.text.SpannableString(label + "    ＋\n" + hex);
            int hexStart = example.toString().indexOf('\n') + 1;
            example.setSpan(
                    new android.text.style.AbsoluteSizeSpan(12, true),
                    hexStart,
                    example.length(),
                    0);
            example.setSpan(
                    new android.text.style.ForegroundColorSpan(0xff8b9fb9),
                    hexStart,
                    example.length(),
                    0);
            example.setSpan(
                    new android.text.style.TypefaceSpan("monospace"),
                    hexStart,
                    example.length(),
                    0);
            commandButton.setText(example);
            commandButton.setOnClickListener(v -> insertCommand(hex));
            deleteButton.setTag(hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase());
            deleteButton.setOnClickListener(v -> removeCommand(hex));
            deleteButtons.add(deleteButton);
            SettingsDesign.styleTree(row);
            container.addView(row);
        }
        updateDeleteButtons();
    }

    private void updateDeleteButtons() {
        if (canCommandsEditor == null) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (String line : commands.text().split("\n")) {
            String normalizedCommand = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (!normalizedCommand.isEmpty()) {
                present.add(normalizedCommand);
            }
        }
        for (ImageButton deleteButton : deleteButtons) {
            String target = (String) deleteButton.getTag();
            boolean enabled = target != null && present.contains(target);
            deleteButton.setEnabled(enabled);
            deleteButton.setAlpha(enabled ? 1f : 0.3f);
        }
    }

    private void insertCommand(String hex) {
        String commandLines = commands.text();
        if (commandLines.length() > 0 && !commandLines.endsWith("\n")) {
            commandLines = commandLines + "\n";
        }
        canCommandsEditor.setText(commandLines + hex + "\n");
        canCommandsEditor.setSelection(canCommandsEditor.getText().length());
    }

    private void removeCommand(String hex) {
        String target = hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String[] lines = commands.text().split("\n");
        StringBuilder builder = new StringBuilder();
        boolean removed = false;
        for (String line : lines) {
            String normalizedCommand = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (normalizedCommand.isEmpty()) {
                continue;
            }
            if (!removed && normalizedCommand.equals(target)) {
                removed = true;
                continue;
            }
            builder.append(normalizedCommand).append("\n");
        }
        canCommandsEditor.setText(builder.toString());
    }

    private void bindCommandEditor() {
        canCommandsEditor = settingView(R.id.rawCanCodes);
        pickerCustomCommandCount = settingView(R.id.pickerCustomCommandCount);
        pickerCustomCommandCount.setMinValue(1);
        pickerCustomCommandCount.setMaxValue(10);
        pickerCustomCommandCount.setTextColor(0xffffffff);
        pickerCustomCommandCount.setValue(commands.count());
        pickerCustomCommandCount.setOnValueChangedListener(
                (picker, before, after) -> commands.setCount(after));
        canCommandsEditor.setText(commands.text());
        canCommandsEditor.addTextChangedListener(
                new TextWatcher() {
                    private boolean formatting;

                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable value) {
                        if (formatting) {
                            return;
                        }
                        String formatted = formatCommandLines(value.toString());
                        commands.setText(formatted);
                        if (!formatted.contentEquals(value)) {
                            formatting = true;
                            value.replace(0, value.length(), formatted);
                            formatting = false;
                        }
                        canCommandsEditor.setBackgroundResource(
                                commands.isValid()
                                        ? R.drawable.settings_code
                                        : R.drawable.settings_code_invalid);
                        refreshCanStatus();
                        updateDeleteButtons();
                    }
                });
        settingView(R.id.settingsValidateCan).setOnClickListener(v -> refreshCanStatus());
        buildExampleButtons();
        refreshCanStatus();
    }

    private static String formatCommandLines(String input) {
        StringBuilder formatted = new StringBuilder();
        String[] lines = input.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (lineIndex > 0) {
                formatted.append('\n');
            }
            String compact = SteeringCanCommandPolicy.compact(lines[lineIndex]);
            for (int start = 0;
                    start < compact.length();
                    start += SteeringCanCommandPolicy.HEX_LENGTH) {
                if (start > 0) {
                    formatted.append('\n');
                }
                int end = Math.min(start + SteeringCanCommandPolicy.HEX_LENGTH, compact.length());
                formatted.append(SteeringCanCommandPolicy.format(compact.substring(start, end)));
            }
        }
        return formatted.toString();
    }

    private CanCommandsViewModel commands;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        commands = new ViewModelProvider(requireActivity()).get(CanCommandsViewModel.class);
    }

    @Override
    protected void bindSettingsRow() {
        if (settingView(R.id.rawCanCodes) != null) {
            bindCommandEditor();
            bindClick(R.id.buttonClean, this::onButtonClickClean);
        }
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, canCommandsEditor)) {
            canCommandsEditor = null;
        }
        if (isDescendant(row, pickerCustomCommandCount)) {
            pickerCustomCommandCount = null;
        }
        deleteButtons.removeIf(button -> isDescendant(row, button));
    }
}

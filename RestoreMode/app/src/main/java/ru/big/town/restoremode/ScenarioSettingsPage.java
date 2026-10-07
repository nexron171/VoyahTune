package ru.big.town.restoremode;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.big.town.common.ScenarioProtocol;

/**
 * Раздел «Сценарии»: список сценариев с чекбоксами и встроенный редактор
 * (триггеры, условия, действия и паузы).
 *
 * <p>Хост владеет боковым меню, заголовком и общей прокруткой — как у раздела голосового
 * управления.</p>
 */
final class ScenarioSettingsPage {
    private static final String EXPANDED_KEY = "scenarioExpandedId";
    /** Начальное значение паузы в диалоге. */
    private static final int DEFAULT_PAUSE_SECONDS = 10;
    /** Крупный шрифт дерева действий: 1.5× от стандартного элемента списка диалога (16sp). */
    private static final int ACTION_ROW_TEXT_SP = 24;

    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final LinearLayout content;
    /** Сценарий раскрытой карточки — его изменения записываются в prefs при сохранении. */
    private ScenarioStore.Scenario current;

    ScenarioSettingsPage(AppCompatActivity activity, LinearLayout content,
                         SharedPreferences prefs, Runnable changed) {
        this.activity = activity;
        this.content = content;
        this.prefs = prefs;
        this.changed = changed;
    }

    void refresh() {
        current = null;
        content.removeAllViews();
        content.addView(text("Сценарий запускается по событию (селектор, дверь, освещённость), плиткой "
                + "на главном экране или голосовой командой «запусти сценарий <название>». Перед выполнением "
                + "проверяются условия; если хотя бы одно не выполнено, сценарий не запускается. "
                + "Действия выполняются по порядку, между ними можно вставить паузу.", 20, 0xffaaaaaa));
        content.addView(text("Сценарии с ⚠ выполняются без дополнительного подтверждения, в отличие от "
                + "голосовых команд: перезагрузка, закрытие приложений и своя CAN-команда сработают сразу.",
                20, 0xffaaaaaa));

        MaterialButton add = actionButton("Добавить сценарий");
        add.setOnClickListener(v -> {
            ScenarioStore.Scenario created = ScenarioStore.create(prefs, "");
            if (created == null) {
                Toast.makeText(activity, "Достигнут предел " + ScenarioProtocol.MAX_SCENARIOS + " сценариев",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(EXPANDED_KEY, created.id).apply();
            current = null;
            save();
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(64));
        addParams.setMargins(0, dp(8), 0, dp(12));
        content.addView(add, addParams);

        String expandedId = prefs.getString(EXPANDED_KEY, "");
        List<ScenarioStore.Scenario> scenarios = ScenarioStore.load(prefs);
        if (scenarios.isEmpty()) {
            content.addView(text("Сценариев пока нет.", 22, 0xffffffff));
            return;
        }
        for (ScenarioStore.Scenario scenario : scenarios) {
            content.addView(card(scenario, scenario.id.equals(expandedId)));
        }
    }

    // ------------------------------------------------------------------
    // Карточка сценария
    // ------------------------------------------------------------------

    private View card(ScenarioStore.Scenario scenario, boolean expanded) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.layout_category_bg);
        card.setPadding(dp(16), dp(10), dp(16), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox enabled = new CheckBox(activity);
        enabled.setContentDescription("Включить сценарий");
        enabled.setChecked(scenario.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            scenario.enabled = checked;
            // Чекбокс есть и у свёрнутой карточки, поэтому пишем сценарий явно.
            ScenarioStore.update(prefs, scenario);
            changed.run();
            refresh();
        });
        header.addView(enabled);
        TextView name = text((expanded ? "▾  " : "▸  ") + scenario.name
                + (scenario.showTile ? "  ▦" : ""), 26, 0xffffffff);
        name.setTypeface(null, Typeface.BOLD);
        name.setContentDescription(expanded ? "Свернуть сценарий" : "Раскрыть сценарий");
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        header.addView(name, nameParams);
        MaterialButton delete = actionButton("Удалить");
        delete.setOnClickListener(v -> {
            ScenarioStore.remove(prefs, scenario.id);
            if (scenario.id.equals(prefs.getString(EXPANDED_KEY, ""))) {
                prefs.edit().remove(EXPANDED_KEY).apply();
            }
            current = null;
            save();
        });
        header.addView(delete);
        card.addView(header);

        View.OnClickListener toggle = v -> {
            String current = prefs.getString(EXPANDED_KEY, "");
            prefs.edit().putString(EXPANDED_KEY, scenario.id.equals(current) ? "" : scenario.id).apply();
            refresh();
        };
        name.setOnClickListener(toggle);

        if (expanded) {
            current = scenario;
            if (!scenario.enabled) {
                card.addView(text("Сценарий выключен: события и плитка не запускают его, "
                        + "голосовая команда тоже недоступна.", 20, 0xffffb0b0));
            }
            card.addView(nameEditor(scenario));
            card.addView(tileSwitch(scenario));
            card.addView(testRow(scenario));
            card.addView(triggerSection(scenario));
            card.addView(conditionSection(scenario));
            card.addView(stepSection(scenario));
        }
        return card;
    }

    private View nameEditor(ScenarioStore.Scenario scenario) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(activity);
        input.setText(scenario.name);
        input.setHint("Название");
        input.setTextColor(0xffffffff);
        input.setHintTextColor(0xff888888);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton saveName = actionButton("Сохранить");
        row.addView(saveName);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        saveName.setOnClickListener(v -> {
            String error = ScenarioStore.validateName(
                    ScenarioStore.load(prefs), input.getText().toString(), scenario.id);
            if (error != null) {
                Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                return;
            }
            scenario.name = input.getText().toString().trim();
            save();
        });
        return row;
    }

    private View tileSwitch(ScenarioStore.Scenario scenario) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        row.addView(text("Плитка на главном экране", 24, 0xffffffff),
                new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setChecked(scenario.showTile);
        toggle.setContentDescription("Показывать плитку сценария на главном экране");
        toggle.setOnCheckedChangeListener((button, checked) -> {
            scenario.showTile = checked;
            save();
        });
        row.addView(toggle);
        return row;
    }

    private View testRow(ScenarioStore.Scenario scenario) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        MaterialButton test = actionButton("Тест");
        test.setOnClickListener(v -> testScenario(scenario));
        row.addView(test);
        row.addView(text("Проверить условия и выполнить сейчас", 20, 0xffaaaaaa),
                new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void testScenario(ScenarioStore.Scenario scenario) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Toast.makeText(activity, "Сервис не готов", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Message message = Message.obtain(null, ScenarioProtocol.MSG_RUN);
            Bundle data = new Bundle();
            data.putString(ScenarioProtocol.EXTRA_ID, scenario.id);
            data.putBoolean(ScenarioProtocol.EXTRA_TEST, true);
            message.setData(data);
            GlobalVars.serviceMessenger.send(message);
            Toast.makeText(activity, "Проверяю условия…", Toast.LENGTH_SHORT).show();
        } catch (RemoteException e) {
            Toast.makeText(activity, "Не удалось запустить тест", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Триггеры
    // ------------------------------------------------------------------

    private View triggerSection(ScenarioStore.Scenario scenario) {
        LinearLayout block = section("Запуск по событию"
                + (scenario.triggers.isEmpty() ? " (не задан: только плитка и голос)" : ""));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton gear = actionButton("Селектор");
        gear.setOnClickListener(v -> pickOne("Положение селектора",
                new String[]{"P — парковка", "R — задний ход", "N — нейтраль", "D — движение"},
                index -> {
                    String[] values = {ScenarioProtocol.GEAR_P, ScenarioProtocol.GEAR_R,
                            ScenarioProtocol.GEAR_N, ScenarioProtocol.GEAR_D};
                    scenario.triggers.add(new ScenarioStore.Trigger(
                            ScenarioProtocol.TRIGGER_GEAR, values[index], ""));
                    save();
                }));
        buttons.addView(gear);
        MaterialButton door = actionButton("Дверь");
        door.setOnClickListener(v -> pickDoor(scenario));
        buttons.addView(door);
        MaterialButton light = actionButton("Освещённость");
        light.setOnClickListener(v -> pickOne("Освещённость",
                new String[]{"Стало темно", "Стало светло"}, index -> {
                    scenario.triggers.add(new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_LIGHT,
                            index == 0 ? ScenarioProtocol.LIGHT_DARK : ScenarioProtocol.LIGHT_BRIGHT, ""));
                    save();
                }));
        buttons.addView(light);
        block.addView(buttons);
        for (ScenarioStore.Trigger trigger : scenario.triggers) {
            block.addView(removableRow(describe(trigger), () -> {
                scenario.triggers.remove(trigger);
                save();
            }));
        }
        if (scenario.triggers.isEmpty()) {
            block.addView(text("Сценарий запускается только вручную.", 20, 0xffaaaaaa));
        }
        return block;
    }

    private void pickDoor(ScenarioStore.Scenario scenario) {
        String[] labels = {"Водительская", "Переднего пассажира", "Задняя левая",
                "Задняя правая", "Багажник", "Капот"};
        String[] values = {ScenarioProtocol.DOOR_DRIVER, ScenarioProtocol.DOOR_PASSENGER,
                ScenarioProtocol.DOOR_REAR_LEFT, ScenarioProtocol.DOOR_REAR_RIGHT,
                ScenarioProtocol.DOOR_BOOT, ScenarioProtocol.DOOR_HOOD};
        pickOne("Дверь", labels, index -> pickOne("Событие", new String[]{"Открытие", "Закрытие"},
                edge -> {
                    scenario.triggers.add(new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_DOOR,
                            values[index], edge == 0 ? ScenarioProtocol.EDGE_OPEN : ScenarioProtocol.EDGE_CLOSE));
                    save();
                }));
    }

    private static String describe(ScenarioStore.Trigger trigger) {
        if (ScenarioProtocol.TRIGGER_GEAR.equals(trigger.type)) {
            return "Селектор: " + trigger.value;
        }
        if (ScenarioProtocol.TRIGGER_LIGHT.equals(trigger.type)) {
            return ScenarioProtocol.LIGHT_DARK.equals(trigger.value)
                    ? "Стало темно" : "Стало светло";
        }
        String door;
        switch (trigger.value) {
            case ScenarioProtocol.DOOR_DRIVER: door = "водительская"; break;
            case ScenarioProtocol.DOOR_PASSENGER: door = "переднего пассажира"; break;
            case ScenarioProtocol.DOOR_REAR_LEFT: door = "задняя левая"; break;
            case ScenarioProtocol.DOOR_REAR_RIGHT: door = "задняя правая"; break;
            case ScenarioProtocol.DOOR_BOOT: door = "багажник"; break;
            default: door = "капот"; break;
        }
        return "Дверь: " + door + (ScenarioProtocol.EDGE_OPEN.equals(trigger.edge) ? " — открытие" : " — закрытие");
    }

    // ------------------------------------------------------------------
    // Условия
    // ------------------------------------------------------------------

    private View conditionSection(ScenarioStore.Scenario scenario) {
        LinearLayout block = section("Условия"
                + (scenario.conditions.isEmpty() ? " (нет: запуск без проверок)" : ""));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton light = actionButton("Освещённость");
        light.setOnClickListener(v -> addNumberCondition(scenario,
                ScenarioProtocol.CONDITION_LIGHT, "Освещённость (0–7)",
                ScenarioProtocol.LIGHT_MIN, ScenarioProtocol.LIGHT_MAX, 1, 0));
        buttons.addView(light);
        MaterialButton temp = actionButton("Уличная t°");
        temp.setOnClickListener(v -> addNumberCondition(scenario,
                ScenarioProtocol.CONDITION_TEMP_OUT, "Уличная температура, °C", -30, 40, 1, 0));
        buttons.addView(temp);
        MaterialButton soc = actionButton("Заряд");
        soc.setOnClickListener(v -> addNumberCondition(scenario,
                ScenarioProtocol.CONDITION_SOC, "Уровень заряда, %", 5, 100, 5, 50));
        buttons.addView(soc);
        block.addView(buttons);

        LinearLayout buttons2 = new LinearLayout(activity);
        buttons2.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton mode = actionButton("Режим движения");
        mode.setOnClickListener(v -> pickOne("Режим движения", ScenarioProtocol.DRIVE_MODES.toArray(new String[0]),
                index -> {
                    ScenarioStore.Condition condition = new ScenarioStore.Condition();
                    condition.type = ScenarioProtocol.CONDITION_DRIVE_MODE;
                    condition.mode = ScenarioProtocol.DRIVE_MODES.get(index);
                    scenario.conditions.add(condition);
                    save();
                }));
        buttons2.addView(mode);
        MaterialButton time = actionButton("Время и дни");
        time.setOnClickListener(v -> addTimeCondition(scenario));
        buttons2.addView(time);
        block.addView(buttons2);

        for (ScenarioStore.Condition condition : scenario.conditions) {
            block.addView(removableRow(describe(condition), () -> {
                scenario.conditions.remove(condition);
                save();
            }));
        }
        return block;
    }

    private void addNumberCondition(ScenarioStore.Scenario scenario, String type, String title,
                                    int min, int max, int step, int initial) {
        String[] ops = {"не более (≤)", "не менее (≥)", "равно (=)"};
        String[] opsValues = {ScenarioProtocol.OP_LE, ScenarioProtocol.OP_GE, ScenarioProtocol.OP_EQ};
        pickOne(title + ": сравнение", ops, opIndex -> numberPicker(title, min, max,
                Math.max(min, Math.min(max, initial)), step, value -> {
                    ScenarioStore.Condition condition = new ScenarioStore.Condition();
                    condition.type = type;
                    condition.op = opsValues[opIndex];
                    condition.value = value;
                    scenario.conditions.add(condition);
                    save();
                }));
    }

    private void addTimeCondition(ScenarioStore.Scenario scenario) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        NumberPicker from = hourPicker(7);
        NumberPicker to = hourPicker(22);
        row.addView(text("С", 24, 0xffffffff));
        row.addView(from);
        row.addView(text("ДО", 24, 0xffffffff));
        row.addView(to);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle("Интервал времени")
                .setView(row)
                .setPositiveButton("Далее", (dialog, which) -> pickDays((days) -> {
                    ScenarioStore.Condition condition = new ScenarioStore.Condition();
                    condition.type = ScenarioProtocol.CONDITION_TIME;
                    condition.from = String.format(java.util.Locale.ROOT, "%02d:00", from.getValue());
                    condition.to = String.format(java.util.Locale.ROOT, "%02d:59", to.getValue());
                    condition.days = days;
                    scenario.conditions.add(condition);
                    save();
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickDays(java.util.function.Consumer<String> onPicked) {
        String[] labels = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
        boolean[] checked = new boolean[labels.length];
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle("Дни недели")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Добавить", (dialog, which) -> {
                    StringBuilder days = new StringBuilder();
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) continue;
                        if (days.length() > 0) days.append(',');
                        days.append(i + 1);
                    }
                    onPicked.accept(days.toString());
                })
                .setNeutralButton("Каждый день", (dialog, which) -> onPicked.accept(""))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String describe(ScenarioStore.Condition condition) {
        if (ScenarioProtocol.CONDITION_DRIVE_MODE.equals(condition.type)) {
            return "Режим движения: " + condition.mode;
        }
        if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
            String days = condition.days == null || condition.days.isEmpty()
                    ? "ежедневно" : "дни " + condition.days;
            return "Время: " + condition.from + "–" + condition.to + ", " + days;
        }
        String name;
        if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) name = "Освещённость";
        else if (ScenarioProtocol.CONDITION_TEMP_OUT.equals(condition.type)) name = "Уличная t°";
        else name = "Заряд";
        String op;
        if (ScenarioProtocol.OP_LE.equals(condition.op)) op = "≤";
        else if (ScenarioProtocol.OP_GE.equals(condition.op)) op = "≥";
        else op = "=";
        return name + " " + op + " " + condition.value;
    }

    // ------------------------------------------------------------------
    // Действия и паузы
    // ------------------------------------------------------------------

    private View stepSection(ScenarioStore.Scenario scenario) {
        LinearLayout block = section("Действия по порядку");
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton addAction = actionButton("Добавить действие");
        addAction.setOnClickListener(v -> pickAction(action -> {
            ScenarioStore.Step step = new ScenarioStore.Step();
            step.type = ScenarioProtocol.STEP_ACTION;
            step.value = action;
            scenario.steps.add(step);
            save();
        }));
        buttons.addView(addAction);
        MaterialButton addPause = actionButton("Добавить паузу");
        addPause.setOnClickListener(v -> pauseDialog(seconds -> {
            ScenarioStore.Step step = new ScenarioStore.Step();
            step.type = ScenarioProtocol.STEP_PAUSE;
            step.seconds = seconds;
            scenario.steps.add(step);
            save();
        }));
        buttons.addView(addPause);
        block.addView(buttons);

        for (int i = 0; i < scenario.steps.size(); i++) {
            final int index = i;
            ScenarioStore.Step step = scenario.steps.get(i);
            String description = ScenarioProtocol.STEP_PAUSE.equals(step.type)
                    ? "Пауза " + ScenarioProtocol.clampPauseSeconds(step.seconds) + " с"
                    : (i + 1) + ". " + actionTitle(step.value);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(description, 22, 0xffdddddd), new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton up = actionButton("↑");
            up.setEnabled(index > 0);
            up.setOnClickListener(v -> {
                java.util.Collections.swap(scenario.steps, index, index - 1);
                save();
            });
            row.addView(up);
            MaterialButton down = actionButton("↓");
            down.setEnabled(index < scenario.steps.size() - 1);
            down.setOnClickListener(v -> {
                java.util.Collections.swap(scenario.steps, index, index + 1);
                save();
            });
            row.addView(down);
            MaterialButton remove = actionButton("×");
            remove.setOnClickListener(v -> {
                scenario.steps.remove(index);
                save();
            });
            row.addView(remove);
            block.addView(row);
        }
        if (scenario.steps.isEmpty()) {
            block.addView(text("Действий нет: сценарий ничего не делает.", 20, 0xffffb0b0));
        }
        return block;
    }

    /**
     * Выбор действия — дерево «раздел → подраздел → действие» вместо плоского списка.
     * Разделы совпадают со справочником голосовых команд ({@link VoiceCommandGroups}).
     * Запуск сценария изнутри сценария не предлагается: это дало бы рекурсию.
     */
    private void pickAction(java.util.function.Consumer<String> onPicked) {
        List<ActionNode> sections = buildActionTree();
        if (sections.isEmpty()) {
            Toast.makeText(activity, "Нет доступных действий", Toast.LENGTH_SHORT).show();
            return;
        }
        showActionNode(new ActionNode("Действие", null, sections), null, onPicked);
    }

    /** Узел дерева действий: раздел (action == null) или конкретное действие. */
    private static final class ActionNode {
        final String label;
        final String action;
        final List<ActionNode> children;

        ActionNode(String label, String action, List<ActionNode> children) {
            this.label = label;
            this.action = action;
            this.children = children;
        }
    }

    private List<ActionNode> buildActionTree() {
        Map<VoiceCommandGroups.Group, Map<String, List<ActionNode>>> grouped = new LinkedHashMap<>();
        for (VoiceCommandGroups.Group group : VoiceCommandGroups.Group.values()) {
            grouped.put(group, new LinkedHashMap<>());
        }
        Set<String> seen = new HashSet<>();
        for (VoiceCommandCatalog.Command command : VoiceCommands.load(activity)) {
            if (command.action.startsWith(ScenarioProtocol.ACTION_RUN_PREFIX)) continue;
            if (!seen.add(command.action)) continue;
            String label = command.title + (command.confirm ? "  ⚠" : "");
            for (VoiceCommandGroups.Group group : VoiceCommandGroups.forAction(command.action)) {
                grouped.get(group)
                        .computeIfAbsent(subGroupFor(group, command.action), key -> new ArrayList<>())
                        .add(new ActionNode(label, command.action, null));
            }
        }

        List<ActionNode> sections = new ArrayList<>();
        for (VoiceCommandGroups.Group group : VoiceCommandGroups.Group.values()) {
            Map<String, List<ActionNode>> subsections = grouped.get(group);
            int total = 0;
            for (List<ActionNode> actions : subsections.values()) total += actions.size();
            if (total == 0) continue;
            List<ActionNode> children = new ArrayList<>();
            if (subsections.size() == 1) {
                // Единственный подраздел: промежуточный уровень только добавил бы лишний клик.
                children.addAll(subsections.values().iterator().next());
            } else {
                for (Map.Entry<String, List<ActionNode>> entry : subsections.entrySet()) {
                    if (entry.getKey() == null) {
                        children.addAll(entry.getValue());
                        continue;
                    }
                    children.add(new ActionNode(entry.getKey() + " (" + entry.getValue().size() + ")",
                            null, entry.getValue()));
                }
            }
            sections.add(new ActionNode(group.title + " (" + total + ")", null, children));
        }
        return sections;
    }

    /** Показывает узел дерева; {@code back} возвращает на предыдущий уровень. */
    private void showActionNode(ActionNode node, Runnable back,
                                java.util.function.Consumer<String> onPicked) {
        if (node.action != null) {
            onPicked.accept(node.action);
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding / 2, padding, padding / 2);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                        .setTitle(node.label)
                        .setView(scroll(content))
                        .setNegativeButton("Отмена", null);
        if (back != null) builder.setNeutralButton("Назад", (dialog, which) -> back.run());
        androidx.appcompat.app.AlertDialog dialog = builder.create();

        // Собственные строки вместо setItems: крупный шрифт и единый отступ для всех уровней дерева.
        for (ActionNode child : node.children) {
            TextView row = text(child.label, ACTION_ROW_TEXT_SP, 0xffffffff);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, ACTION_ROW_TEXT_SP);
            row.setPadding(dp(12), dp(22), dp(12), dp(22));
            row.setBackgroundResource(R.drawable.row_dark_ripple);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                showActionNode(child, () -> showActionNode(node, back, onPicked), onPicked);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(8);
            content.addView(row, params);
        }
        dialog.show();
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }

    /** Пауза задаётся числом секунд (по умолчанию 10), а не спиннером. */
    private void pauseDialog(java.util.function.IntConsumer onPicked) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(DEFAULT_PAUSE_SECONDS));
        input.setSelectAllOnFocus(true);
        input.setTextColor(0xffffffff);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        input.setContentDescription("Пауза в секундах");
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(24);
        wrapper.setPadding(padding, dp(8), padding, dp(8));
        wrapper.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        wrapper.addView(text("сек.", 24, 0xffffffff));

        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                        .setTitle("Пауза")
                        .setView(wrapper)
                        .setPositiveButton("Добавить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Integer seconds = parsePauseSeconds(input.getText().toString());
                    if (seconds == null) {
                        Toast.makeText(activity, "Введите секунды от "
                                + ScenarioProtocol.MIN_PAUSE_SECONDS + " до "
                                + ScenarioProtocol.MAX_PAUSE_SECONDS, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    onPicked.accept(seconds);
                }));
        dialog.show();
    }

    /** Секунды в допустимых границах или {@code null}, если введено не число/вне диапазона. */
    static Integer parsePauseSeconds(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        try {
            int seconds = Integer.parseInt(trimmed);
            if (seconds < ScenarioProtocol.MIN_PAUSE_SECONDS
                    || seconds > ScenarioProtocol.MAX_PAUSE_SECONDS) return null;
            return seconds;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Подраздел внутри раздела; {@code null} — действия показываются прямо в разделе. */
    static String subGroupFor(VoiceCommandGroups.Group group, String action) {
        switch (group) {
            case SEATS:
                if (action.contains(":passenger:")) return "Пассажир";
                if (action.contains(":driver:")) return "Водитель";
                return null;
            case WINDOWS:
                return action.startsWith("sunroof:") || action.startsWith("sunshade:")
                        ? "Люк и шторка" : "Окна";
            case CAR:
                if (action.startsWith("drive:")) return "Режим движения";
                if (action.startsWith("energy:") || action.startsWith("recycle:")
                        || action.startsWith(VoiceFuelCommand.PREFIX)) return "Энергия и заряд";
                if (action.startsWith("headlights:") || action.startsWith("auto_light:")
                        || action.startsWith("toggle_headlights")) return "Свет";
                if (action.startsWith("forced_ev:") || action.startsWith("pedestrian:")
                        || action.startsWith("suspension_maintenance:")) return "Тумблеры";
                return "Сервис и лючки";
            case HEATING:
                return null;
            default:
                if (action.startsWith("app:")) return "Приложения";
                if (action.startsWith("split:")) return "Сплиты и экраны";
                if (action.startsWith("call:")) return "Звонки";
                if (action.startsWith("can:")) return "Свои команды";
                return "Система";
        }
    }

    private String actionTitle(String action) {
        for (VoiceCommandCatalog.Command command : VoiceCommands.load(activity)) {
            if (command.action.equals(action)) return command.title;
        }
        return action;
    }

    // ------------------------------------------------------------------
    // Вспомогательные диалоги и виджеты
    // ------------------------------------------------------------------

    private void pickOne(String title, String[] labels, java.util.function.IntConsumer onPicked) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> onPicked.accept(which))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void numberPicker(String title, int min, int max, int value, int step,
                              java.util.function.IntConsumer onPicked) {
        NumberPicker picker = new NumberPicker(activity);
        picker.setMinValue(0);
        picker.setMaxValue((max - min) / step);
        picker.setValue(Math.max(0, (value - min) / step));
        picker.setFormatter(index -> String.valueOf(min + index * step));
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setGravity(Gravity.CENTER);
        wrapper.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrapper.addView(picker);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle(title)
                .setView(wrapper)
                .setPositiveButton("Добавить", (dialog, which) -> onPicked.accept(min + picker.getValue() * step))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private NumberPicker hourPicker(int value) {
        NumberPicker picker = new NumberPicker(activity);
        picker.setMinValue(0);
        picker.setMaxValue(23);
        picker.setValue(value);
        picker.setFormatter(index -> String.format(java.util.Locale.ROOT, "%02d:00", index));
        return picker;
    }

    private View removableRow(String label, Runnable onRemove) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        row.setLayoutParams(params);
        row.addView(text(label, 22, 0xffdddddd), new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton remove = actionButton("×");
        remove.setContentDescription("Удалить");
        remove.setOnClickListener(v -> onRemove.run());
        row.addView(remove);
        return row;
    }

    private LinearLayout section(String title) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        block.setLayoutParams(params);
        TextView header = text(title, 24, 0xffffffff);
        header.setTypeface(null, Typeface.BOLD);
        block.addView(header);
        return block;
    }

    private MaterialButton actionButton(String label) {
        MaterialButton button = new MaterialButton(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        button.setTextColor(0xffffffff);
        button.setMinHeight(dp(52));
        button.setCornerRadius(dp(12));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff373f4a));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Сохраняет изменения, публикует снимок в Native и перестраивает список. */
    private void save() {
        if (current != null) ScenarioStore.update(prefs, current);
        changed.run();
        refresh();
    }
}

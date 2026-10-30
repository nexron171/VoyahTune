import assert from "node:assert/strict";
import test from "node:test";
import { getOperationProgress } from "../desktop/src/installation-progress.ts";

const installStepIds = [
  "preflight",
  "root",
  "updater-prepare",
  "system",
  "third-party-loaders",
  "backup",
  "signing-reset",
  "runtime",
  "apollo-migration",
  "files",
  "migration",
  "boot-hooks",
  "native",
  "dns",
  "updater-bootstrap",
  "packages",
  "reboot",
  "verify",
];
const steps = (ids) => ids.map((id) => ({ id, title: id }));
const installSteps = steps(installStepIds);

test("full installation has four stages and advances within a stage", () => {
  const initial = getOperationProgress(
    installSteps,
    "install",
    [],
    "preflight",
  );
  assert.deepEqual(
    initial.stages.map((stage) => stage.title),
    [
      "Подготовка автомобиля",
      "Установка компонентов",
      "Настройка системы",
      "Перезапуск и проверка",
    ],
  );
  assert.equal(initial.percent, 0);

  const partial = getOperationProgress(
    installSteps,
    "install",
    ["preflight"],
    "root",
  );
  assert.equal(partial.percent, 5);
  assert.equal(partial.stages[0].completed, false);
  assert.equal(partial.stages[0].active, true);

  const prepared = getOperationProgress(
    installSteps,
    "install",
    installStepIds.slice(0, 6),
    "signing-reset",
  );
  assert.equal(prepared.percent, 33);
  assert.equal(prepared.stages[0].completed, true);
  assert.equal(prepared.stages[1].active, true);
});

test("duplicate and unrelated completions do not inflate progress", () => {
  const progress = getOperationProgress(
    installSteps,
    "install",
    ["root", "root", "other-operation"],
    "system",
  );
  assert.equal(progress.percent, 5);
  assert.equal(progress.stages[0].completed, false);
});

test("verification retry stays below 100 until the final step completes", () => {
  const progress = getOperationProgress(
    installSteps,
    "install",
    installStepIds.slice(0, -1),
    "verify",
  );
  assert.equal(progress.percent, 94);
  assert.deepEqual(
    progress.stages.map((stage) => stage.completed),
    [true, true, true, false],
  );
  assert.equal(progress.stages[3].active, true);

  const finished = getOperationProgress(
    installSteps,
    "install",
    installStepIds,
    "verify",
  );
  assert.equal(finished.percent, 100);
  assert.ok(finished.stages.every((stage) => stage.completed && !stage.active));
});

test("both RestoreMode installation orders stay in the settings stage", () => {
  for (const settings of [
    ["packages", "dns", "updater-bootstrap"],
    ["dns", "updater-bootstrap", "packages"],
  ]) {
    const progress = getOperationProgress(
      steps(["native", ...settings, "reboot"]),
      "install",
      ["native"],
      "packages",
    );
    assert.deepEqual(
      progress.stages.map((stage) => stage.title),
      ["Установка компонентов", "Настройка системы", "Перезапуск и проверка"],
    );
    assert.equal(progress.stages[1].active, true);
  }
});

test("removal includes optional Cunba restoration in the total", () => {
  const removalStepIds = [
    "preflight",
    "root",
    "updater-prepare",
    "system",
    "deactivate",
    "dns",
    "migration",
    "runtime",
    "files",
    "packages",
    "reboot",
    "settings",
  ];
  const removed = getOperationProgress(
    steps(removalStepIds),
    "remove",
    removalStepIds,
  );
  assert.equal(removed.percent, 100);
  assert.equal(removed.stages.length, 3);

  const cleaning = getOperationProgress(
    steps(removalStepIds),
    "remove",
    removalStepIds.filter((id) => id !== "settings"),
    "settings",
  );
  assert.equal(cleaning.percent, 91);
  assert.deepEqual(cleaning.stages.at(-1), {
    title: "Перезапуск и очистка настроек",
    completed: false,
    active: true,
  });

  const restoring = getOperationProgress(
    steps([...removalStepIds, "cunba-restore"]),
    "remove",
    removalStepIds,
    "cunba-restore",
  );
  assert.equal(restoring.percent, 92);
  assert.deepEqual(restoring.stages.at(-1), {
    title: "Восстановление Cunba",
    completed: false,
    active: true,
  });
});

test("empty plans stay at zero and unknown steps remain visible and count", () => {
  assert.deepEqual(getOperationProgress([], "install", ["preflight"]), {
    percent: 0,
    stages: [],
  });
  const progress = getOperationProgress(
    steps(["preflight", "future-step", "files"]),
    "install",
    ["preflight"],
    "future-step",
  );
  assert.equal(progress.percent, 33);
  assert.deepEqual(
    progress.stages.map((stage) => stage.title),
    ["Подготовка автомобиля", "future-step", "Установка компонентов"],
  );
  assert.equal(progress.stages[1].active, true);
});

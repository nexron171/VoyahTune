import type { Action, Plan } from "./api";

interface StageDefinition {
  id: string;
  title: string;
  stepIds: readonly string[];
}

const preparation: StageDefinition = {
  id: "preparation",
  title: "Подготовка автомобиля",
  stepIds: [
    "preflight",
    "root",
    "updater-prepare",
    "permission",
    "system",
    "third-party-loaders",
    "backup",
  ],
};

const installationStages: StageDefinition[] = [
  preparation,
  {
    id: "components",
    title: "Установка компонентов",
    stepIds: [
      "signing-reset",
      "runtime",
      "apollo-migration",
      "files",
      "migration",
      "boot-hooks",
      "native",
    ],
  },
  {
    id: "settings",
    title: "Настройка системы",
    // RestoreMode may be installed before or after DNS and OTA preparation.
    stepIds: ["packages", "dns", "updater-bootstrap"],
  },
  {
    id: "verification",
    title: "Перезапуск и проверка",
    stepIds: ["reboot", "verify"],
  },
];

const removalStages: StageDefinition[] = [
  preparation,
  {
    id: "removal",
    title: "Удаление VoyahTune",
    stepIds: [
      "deactivate",
      "dns",
      "migration",
      "runtime",
      "files",
      "packages",
    ],
  },
  {
    id: "restart",
    title: "Перезапуск и очистка настроек",
    stepIds: ["reboot", "settings", "verify"],
  },
  {
    id: "cunba",
    title: "Восстановление Cunba",
    stepIds: ["cunba-restore"],
  },
];

export function getOperationProgress(
  steps: Plan["steps"],
  action: Action,
  completedStepIds: readonly string[],
  currentStepId?: string,
) {
  const definitions = action === "remove" ? removalStages : installationStages;
  const groups: StageDefinition[] = [];
  for (const step of steps) {
    const definition = definitions.find((stage) =>
      stage.stepIds.includes(step.id),
    );
    const id = definition?.id ?? `step:${step.id}`;
    const previous = groups[groups.length - 1];
    if (previous?.id === id) {
      previous.stepIds = [...previous.stepIds, step.id];
    } else {
      groups.push({
        id,
        title: definition?.title ?? step.title,
        stepIds: [step.id],
      });
    }
  }

  const completed = new Set(completedStepIds);
  const completedCount = steps.filter((step) => completed.has(step.id)).length;
  return {
    percent: steps.length
      ? Math.floor((100 * completedCount) / steps.length)
      : 0,
    stages: groups.map((group) => {
      const finished = group.stepIds.every((stepId) => completed.has(stepId));
      return {
        title: group.title,
        completed: finished,
        active:
          !finished && group.stepIds.some((stepId) => stepId === currentStepId),
      };
    }),
  };
}

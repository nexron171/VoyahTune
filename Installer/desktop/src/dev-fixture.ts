// Excluded from production by import.meta.env.DEV. Browser-only UI verification.
import type {
  Event,
  Plan,
  Action,
  Dns,
  PayloadProgress,
  CachedPayload,
} from "./api";
const listeners = new Set<(event: Event) => void>();
let sequence = 0,
  cancel = false;
let journal: Event[] = [];
let canbusAnswer: ((approved: boolean) => void) | undefined;
const progressListeners = new Set<(event: PayloadProgress) => void>();
export function onPayloadProgress(callback: (event: PayloadProgress) => void) {
  progressListeners.add(callback);
  return () => {
    progressListeners.delete(callback);
  };
}
const scenario = new URLSearchParams(location.search).get("fixture");
export function subscribe(callback: (event: Event) => void) {
  if (scenario === "events-error") throw Error("core:event:allow-listen denied");
  listeners.add(callback);
  return () => {
    listeners.delete(callback);
  };
}
const emit = (
  type: string,
  stepId: string | undefined,
  message: string,
  data = {},
) => {
  const event = {
    operationId: "browser-fixture",
    sequence: ++sequence,
    timestamp: new Date().toISOString(),
    type,
    stepId,
    message,
    data,
  };
  journal.push(event);
  for (const listener of listeners) listener(event);
};
const versions =
  scenario === "catalog-alias"
    ? ["4.1.0-od", "4.0.0-od"]
    : scenario === "profiles"
      ? ["3.22.0-od", "3.22.0-pi", "3.21.0"]
      : ["3.13.0", "3.12.0", "3.11.0", "3.10.0", "3.9.0", "3.8.0"];
const digest = (version: string) =>
  (scenario === "catalog-alias" ? "4.0.0-od" : version)
    .replaceAll(".", "")
    .padEnd(64, "a");
const cachePath = (version: string) => "/demo/payloads/" + digest(version);
let cached: CachedPayload[] =
  scenario === "catalog-alias"
    ? [{ version: "4.0.0-od", path: cachePath("4.0.0-od"), deletable: true }]
    : ["ready", "offline"].includes(scenario || "")
      ? [{ version: "3.12.0", path: cachePath("3.12.0"), deletable: true }]
      : [];
let selectedPath = "";
let catalogUrl =
  sessionStorage.getItem("fixture-catalog-url") ||
  "https://example.org/index.json";
export async function command(
  name: string,
  args: Record<string, unknown>,
): Promise<unknown> {
  if (name === "catalog_settings")
    return {
      catalogUrl,
      defaultCatalogUrl: "https://example.org/index.json",
    };
  if (name === "save_catalog_url") {
    const value = String(args.url).trim();
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.hash ||
      /\s/.test(value)
    ) {
      throw Error(
        "Укажите HTTPS URL каталога без логина, пароля и фрагмента (#)",
      );
    }
    catalogUrl = value;
    sessionStorage.setItem("fixture-catalog-url", catalogUrl);
    return { catalogUrl };
  }
  if (name === "open_release_link") return;
  if (name === "engineering_code") {
    const date = String(
      args.date ||
        new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10),
    );
    return {
      date,
      code: [...date.slice(0, 4)]
        .map(
          (d, i) =>
            Number(d) + Number((date.slice(5, 7) + date.slice(8, 10))[i]),
        )
        .join(""),
    };
  }
  if (name === "devices")
    return {
      devices:
        scenario === "none"
          ? []
          : scenario === "multiple"
            ? [
                { serial: "CAR-001", state: "device", model: "Voyah Free" },
                { serial: "PHONE", state: "unauthorized" },
              ]
            : [
                {
                  serial: "CAR-001",
                  state: scenario === "unauthorized" ? "unauthorized" : "device",
                  model: "Voyah Free",
                },
              ],
    };
  if (name === "release_catalog")
    return {
      installerVersion: scenario === "catalog-alias" ? "1.6.0" : "1.0.3",
      catalog: {
        generatedAt: "2026-09-27",
        releases: ["offline", "empty"].includes(scenario || "")
          ? []
          : versions.map((version, i) => ({
              version,
              publishedAt: `2026-09-${26 - i}`,
              channel: version.includes("-") ? "prerelease" : "stable",
              notesUrl: "https://github.com/nexron171/VoyahTune/releases",
              compatible:
                scenario !== "incompatible" &&
                !(scenario === "catalog-alias" && version === "4.1.0-od"),
              incompatibility:
                scenario === "catalog-alias"
                  ? "Требуется установщик 1.7.0"
                  : "Требуется установщик 2.0.0",
              requirements: {
                minInstallerVersion:
                  scenario === "catalog-alias" ? "1.7.0" : "2.0.0",
              },
              payload: { size: 150000000, sha256: digest(version) },
            })),
        installerDownloads: [],
      },
      cached: cached.map((c) => ({ ...c })),
      warning: scenario === "offline" ? "Сеть недоступна" : null,
    };
  if (name === "delete_payload") {
    if (scenario === "delete-error")
      throw {
        code: "CACHE_BUSY",
        message: "Другой экземпляр установщика обновляет кэш",
        detail: "",
      };
    cached = cached.filter((c) => c.path !== args.path);
    const deselected = selectedPath === args.path;
    if (deselected) selectedPath = "";
    return { deselected };
  }
  if (name === "download_payload") {
    if (scenario === "download-error")
      throw {
        code: "DOWNLOAD_HASH",
        message: "Архив не прошёл проверку SHA-256",
        detail: "Тестовый повреждённый ZIP",
      };
    if (scenario === "downloading") {
      cancel = false;
      for (let n = 1; n <= 10; n++) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        if (cancel)
          throw { code: "CANCELLED", message: "Загрузка отменена", detail: "" };
        for (const listener of progressListeners)
          listener({
            stage: "download",
            bytes: n * 15000000,
            total: 150000000,
          });
      }
    }
    const version = String(args.version);
    selectedPath = cachePath(version);
    if (!cached.some((c) => c.path === selectedPath))
      cached.push({ version, path: selectedPath, deletable: true });
    return { manifest: { releaseVersion: version }, payloadRoot: selectedPath };
  }
  if (name === "release_info") {
    selectedPath = String(args.path);
    return {
      manifest: {
        releaseVersion:
          cached.find((c) => c.path === selectedPath)?.version || "3.13.0",
      },
      payloadRoot: selectedPath,
    };
  }
  if (name === "plan")
    return {
      request: {
        action: args.action as Action,
        dns: args.dns as Dns,
        serial: String(args.serial),
        inventoryToken: "fixture-token",
        confirmed: false,
      },
      operation: args.action === "remove" ? "remove" : "install",
      warnings: ["Проверка интерфейса: реальный автомобиль не используется."],
      inventory: {
        serial: "CAR-001",
        model: "Voyah Free",
        fingerprint: "fixture",
        state: scenario === "unknown-mode" ? "unknown" : "absent",
        sdk: 30,
        abi: "arm64-v8a",
        files: {},
        packages: {},
      },
      steps: [
        { id: "preflight", title: "Повторная проверка автомобиля и файлов" },
        { id: "root", title: "Получение системного доступа" },
        ...(args.action === "remove"
          ? []
          : [{ id: "permission", title: "Проверка владельца CAN-разрешения" }]),
        { id: "files", title: "Установка компонентов" },
        { id: "reboot", title: "Перезагрузка автомобиля" },
        { id: "verify", title: "Проверка результата" },
      ],
    } satisfies Plan;
  if (name === "operation_events") return journal;
  if (name === "cancel") {
    cancel = true;
    canbusAnswer?.(false);
    return;
  }
  if (name === "resolve_canbus_conflict") {
    canbusAnswer?.(args.approved === true);
    return;
  }
  if (name === "apply") {
    cancel = false;
    journal = [];
    const plan = (await command(
      "plan",
      args.request as unknown as Record<string, unknown>,
    )) as Plan;
    for (const step of plan.steps) {
      emit("step-started", step.id, step.title);
      await new Promise((resolve) =>
        setTimeout(resolve, scenario === "progress" ? 5000 : 350),
      );
      if (scenario === "root-error" && step.id === "root") {
        const error = {
          code: "ROOT_UNAVAILABLE",
          message: "Прошивка не разрешает системный доступ через ADB",
          detail: "adbd cannot run as root in production builds",
          nextAction: "Проверьте настройки отладки и повторите проверку.",
          retryable: true,
        };
        emit("operation-failed", step.id, error.message, { report: { error } });
        return;
      }
      if (
        scenario === "canbus-conflict" &&
        step.id === "permission" &&
        plan.request.action !== "remove"
      ) {
        const approved = await new Promise<boolean>((resolve) => {
          canbusAnswer = resolve;
          emit(
            "canbus-conflict",
            step.id,
            "Обнаружено конфликтующее приложение com.voyah.hl.service (VoyahHlCTRL): оно установлено для пользователя 0 или владеет разрешением WRITE_CANBUS. Установщик сохранит копию системной папки на компьютере, удалит приложение для пользователя 0 и системную папку, очистит кэш пакетов и перезагрузит автомобиль. Функции приложения и его пользовательские данные будут удалены.",
            { awaitingConfirmation: true },
          );
        });
        canbusAnswer = undefined;
        if (!approved) {
          emit("operation-cancelled", step.id, "Установка отменена", {
            report: {
              error: {
                code: "CANCELLED",
                message:
                  "Удаление com.voyah.hl.service отменено. Установка не продолжена.",
              },
            },
          });
          return;
        }
        emit(
          "canbus-removal-started",
          step.id,
          "Сохраняем VoyahHlCTRL перед удалением",
        );
        emit(
          "canbus-removal-reboot",
          step.id,
          "Перезагрузка для освобождения WRITE_CANBUS",
        );
        await new Promise((resolve) => setTimeout(resolve, 350));
        emit(
          "canbus-conflict-resolved",
          step.id,
          "Конфликт устранён. Продолжаем установку.",
        );
      }
      emit("step-completed", step.id, step.title);
      if (cancel) {
        emit("operation-cancelled", step.id, "Остановлено", {
          report: {
            error: {
              code: "CANCELLED",
              message: "Операция остановлена между шагами",
              detail: "",
              nextAction: "Выполните новую проверку.",
              retryable: true,
            },
          },
        });
        return;
      }
    }
    emit("operation-completed", undefined, "Результат проверен");
    return;
  }
  if (name === "save_report") return "Тестовый отчёт · браузерная проверка";
  throw Error(`Unknown fixture command: ${name}`);
}

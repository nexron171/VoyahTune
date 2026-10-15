import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

export type Action = 'install' | 'remove';
export type Dns = 'keep' | 'on' | 'off';
export interface Failure {
  code: string;
  message: string;
  detail: string;
  nextAction: string;
  retryable: boolean;
}
export interface Device {
  serial: string;
  state: string;
  model?: string;
  product?: string;
}
export interface Request {
  action: Action;
  dns: Dns;
  serial: string;
  inventoryToken: string;
  confirmed: boolean;
}
export interface Plan {
  releaseVersion?: string;
  manifestSha256?: string;
  request: Request;
  operation: string;
  warnings: string[];
  steps: { id: string; title: string }[];
  inventory: {
    serial: string;
    model: string;
    fingerprint: string;
    state: string;
    version?: string;
    sdk: number;
    abi: string;
    files: Record<string, string>;
    packages: Record<string, { path: string; sha256: string; build: unknown }>;
  };
}
export interface Event {
  operationId: string;
  sequence: number;
  timestamp: string;
  type: string;
  stepId?: string;
  message: string;
  data: Record<string, any>;
}
export interface PayloadProgress { stage: string; bytes: number; total: number }
export interface Release {
  installerUpdates?: { version: string; platform: string; url: string }[];
  version: string;
  publishedAt: string;
  channel: string;
  notesUrl: string;
  compatible?: boolean;
  incompatibility?: string;
  payload: { size: number; sha256: string };
  requirements: { minInstallerVersion: string };
}
export interface CachedPayload { version: string; path: string; deletable?: boolean }
export interface CatalogState {
  catalogUrl?: string;
  catalog: { generatedAt: string; releases: Release[]; installerDownloads: { version: string; platform: string; url: string }[] };
  cached: CachedPayload[];
  warning?: string;
  installerVersion: string;
}

const fixture = () => import.meta.env.DEV && new URLSearchParams(location.search).has('fixture');
let sessionId: string | null = null;
let auditQueue: Promise<void> = Promise.resolve();

export function failure(error: unknown): Failure {
  if (typeof error === 'object' && error && 'message' in error) return error as Failure;
  return { code: 'DESKTOP_ERROR', message: String(error), detail: '', nextAction: 'Сохраните отчёт и повторите проверку.', retryable: true };
}

async function raw<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  if (fixture()) return (await import('./dev-fixture')).command(name, args) as Promise<T>;
  if (!isTauri()) throw {
    code: 'NATIVE_REQUIRED', message: 'Откройте нативное приложение VoyahTune',
    detail: 'Браузерная сборка не имеет доступа к USB.',
    nextAction: 'Запустите собранный установщик или npm run tauri dev.', retryable: false,
  };
  return invoke<T>(name, args);
}

function audit(kind: string, name: string, data: unknown, selectedRelease?: unknown, dns?: Dns): Promise<void> {
  if (!sessionId) return Promise.resolve();
  const recordedSessionId = sessionId;
  auditQueue = auditQueue.then(() => raw<void>('log_session_record', { sessionId: recordedSessionId, kind, name, data, selectedRelease: selectedRelease === null ? { cleared: true } : selectedRelease, dns }))
    .catch(error => {
      window.dispatchEvent(new CustomEvent('installation-log-error', { detail: failure(error).message }));
    });
  return auditQueue;
}

export function recordStep(name: string, data: unknown) {
  return audit('step', name, data);
}

export function recordChoice(name: string, data: unknown, selectedRelease?: unknown, dns?: Dns) {
  return audit('user_choice', name, data, selectedRelease, dns);
}

export async function beginInstallationSession(): Promise<string> {
  if (fixture()) return 'browser-preview-session';
  if (sessionId) return sessionId;
  const session = await raw<{ sessionId: string }>('log_session_begin');
  sessionId = session.sessionId;
  return sessionId;
}

export async function finishInstallationSession(status: 'success' | 'error', reason: unknown = null) {
  if (!sessionId) return;
  await auditQueue;
  await raw('log_session_finish', { sessionId, status, reason });
  sessionId = null;
}

export async function command<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  const tracked = !['operation_events', 'save_report'].includes(name);
  if (tracked) await audit('step', `${name}-started`, { arguments: args });
  try {
    const result = await raw<T>(name, args);
    const value = result as any;
    const release = ['release_info', 'download_payload'].includes(name) && value?.manifest
      ? { version: value.manifest.releaseVersion, infrastructure: value.manifest.infrastructure } : undefined;
    if (tracked) await audit('step', `${name}-completed`, {
      result: name === 'plan' || name === 'devices' ? result : undefined,
    }, release);
    return result;
  } catch (error) {
    if (tracked) await audit('step', `${name}-failed`, { error: failure(error) });
    throw error;
  }
}

export async function subscribe(callback: (event: Event) => void) {
  if (fixture()) return (await import('./dev-fixture')).subscribe(callback);
  return isTauri() ? listen<Event>('installer-event', event => callback(event.payload)) : () => {};
}
export async function onCloseBlocked(callback: () => void) {
  return isTauri() ? listen('installer-close-blocked', callback) : () => {};
}
export async function onPayloadProgress(callback: (event: PayloadProgress) => void) {
  if (fixture()) return (await import('./dev-fixture')).onPayloadProgress(callback);
  return isTauri() ? listen<PayloadProgress>('payload-progress', event => callback(event.payload)) : () => {};
}

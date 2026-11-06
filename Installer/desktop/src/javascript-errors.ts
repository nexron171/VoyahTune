import { invoke, isTauri } from '@tauri-apps/api/core';

type ExceptionSource = 'window' | 'promise' | 'render' | 'caught';
let pendingWrite: Promise<void> = Promise.resolve();

export function exceptionMessage(reason: unknown): string {
  if (typeof reason === 'string') return reason;
  if (reason && typeof reason === 'object') {
    if ('message' in reason && typeof reason.message === 'string') return reason.message;
    try {
      return JSON.stringify(reason) ?? String(reason);
    } catch {
      return Object.prototype.toString.call(reason);
    }
  }
  return String(reason);
}

export function logJavascriptException(
  source: ExceptionSource,
  reason: unknown,
  location?: { file: string; line: number; column: number },
) {
  const entry = {
    timestamp: new Date().toISOString(),
    source,
    name: reason instanceof Error ? reason.name : undefined,
    message: exceptionMessage(reason).slice(0, 8192),
    userAgent: navigator.userAgent,
    stack: reason instanceof Error ? reason.stack?.slice(0, 32768) : undefined,
    step: document.querySelector('[aria-current="step"]')?.textContent?.trim(),
    ...location,
  };

  if (!isTauri()) {
    console.error('VoyahTune GUI:', entry);
    return;
  }
  pendingWrite = pendingWrite
    .then(() => invoke<void>('log_javascript_exception', { entry }))
    .catch(error => console.error('Failed to write JavaScript exception:', error, entry));
}

export function flushJavascriptExceptions() {
  return pendingWrite;
}

export function listenForJavascriptExceptions() {
  window.addEventListener('error', event => {
    logJavascriptException('window', event.error ?? event.message, {
      file: event.filename,
      line: event.lineno,
      column: event.colno,
    });
  });
  window.addEventListener('unhandledrejection', event => {
    logJavascriptException('promise', event.reason);
  });
}

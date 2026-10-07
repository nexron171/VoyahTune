package ru.big.town.updater.ui;

/** Only the instrumentation APK can select a mock endpoint. */
public final class TestUpdaterActivity extends UpdaterActivity {
    static UpdateService service;
    @Override protected UpdateService createService() { return service; }
    @Override protected long pollIntervalMillis() { return 100; }
}

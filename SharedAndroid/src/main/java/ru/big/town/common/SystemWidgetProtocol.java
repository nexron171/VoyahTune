package ru.big.town.common;

/** Memory cleanup only, over the existing signature-protected SetModesService Messenger. */
public final class SystemWidgetProtocol {
    private SystemWidgetProtocol() { }
    public static final int CLEAR = 27, CLEAR_RESULT = 113;
    public static final int VERSION = 1;
    public static final long TIMEOUT_MS = 15_000;
    public static final String SCHEMA = "schema", SUCCEEDED = "succeeded", FAILED = "failed", ERROR = "error";
    // CLEAR reuses the existing close-all command (27). CLEAR_RESULT echoes arg1 (request ID).
    // SUCCEEDED counts accepted force-stop calls, not proven running/killed processes or freed RAM.
}

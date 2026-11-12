package ru.big.town.anative;

/** The accepted command and its resolved target, not a physical-state confirmation. */
final class ActionResult {
    final boolean accepted;
    final String action;
    final String error;

    private ActionResult(boolean accepted, String action, String error) {
        this.accepted = accepted;
        this.action = action;
        this.error = error;
    }

    static ActionResult sent(String action) {
        return new ActionResult(true, action, "");
    }

    static ActionResult failed(String action, String error) {
        return new ActionResult(
                false,
                action,
                error == null || error.isEmpty() ? "Не удалось отправить команду" : error);
    }

    static ActionResult from(String action, String error) {
        return error == null || error.isEmpty() ? sent(action) : failed(action, error);
    }
}

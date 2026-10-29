package ru.voyahtune.agent.policy;

import java.util.regex.Pattern;

public final class PhoneNumberPolicy {
    private static final Pattern VISUAL_SEPARATORS = Pattern.compile(
            "[" + TextSeparators.WHITESPACE + "()\\-]");
    private static final Pattern LOST_PREFIX = Pattern.compile(
            "^(7\\d{10}|375\\d{9})([,;pPwW].*)?$");

    private PhoneNumberPolicy() {}

    public static String normalize(String number) {
        if (number == null) {
            return "";
        }
        String compact = VISUAL_SEPARATORS.matcher(number).replaceAll("");
        return LOST_PREFIX.matcher(compact).matches() ? "+" + compact : compact;
    }
}

package ru.voyahtune.agent.policy;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IntegerSetting {
    private static final Pattern PREFIX = Pattern.compile("^[\\s\\p{Z}\\uFEFF]*([+-]?[0-9]+)");
    private IntegerSetting() {}
    public static boolean matches(String value, int expected) {
        if (value == null) return false;
        Matcher prefix = PREFIX.matcher(value);
        return prefix.find() && new BigInteger(prefix.group(1)).equals(BigInteger.valueOf(expected));
    }
}

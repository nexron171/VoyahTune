package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigurationObject {
    private final Map<String, Object> values;

    public ConfigurationObject(Map<?, ?> source) {
        this(source, 0);
    }

    private ConfigurationObject(Map<?, ?> source, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("Configuration nesting limit");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("Configuration key must be a string");
            }
            copy.put((String) entry.getKey(), immutable(entry.getValue(), depth + 1));
        }
        values = Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("Configuration nesting limit");
        }
        if (value instanceof Map<?, ?>) {
            return new ConfigurationObject((Map<?, ?>) value, depth);
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(immutable(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        throw new IllegalArgumentException("Invalid configuration value");
    }

    public boolean has(String key) {
        return values.containsKey(key) && values.get(key) != null;
    }

    public ConfigurationObject object(String key) {
        Object value = values.get(key);
        if (!(value instanceof ConfigurationObject)) {
            throw new IllegalArgumentException("Configuration object required: " + key);
        }
        return (ConfigurationObject) value;
    }

    public List<ConfigurationObject> objects(String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Configuration array required: " + key);
        }
        List<ConfigurationObject> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof ConfigurationObject)) {
                throw new IllegalArgumentException("Configuration array member must be an object: " + key);
            }
            result.add((ConfigurationObject) item);
        }
        return List.copyOf(result);
    }

    public String string(String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String)) throw new IllegalArgumentException("String required: " + key);
        return (String) value;
    }

    public boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Boolean required: " + key);
        return (Boolean) value;
    }

    public float decimal(String key, float fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number) || !Float.isFinite(((Number) value).floatValue())) {
            throw new IllegalArgumentException("Finite number required: " + key);
        }
        return ((Number) value).floatValue();
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException("Integer required: " + key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer out of range: " + key);
        }
        return (int) number;
    }
}

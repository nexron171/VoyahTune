package ru.voyahtune.agent.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AndroidConfiguration {
    private AndroidConfiguration() {}

    public static ConfigurationObject read(Path path, int maximumBytes) throws Exception {
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximumBytes) {
                    throw new IllegalArgumentException("Configuration file is too large");
                }
                output.write(buffer, 0, count);
            }
            JSONObject document = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            return new ConfigurationObject(object(document, 0));
        }
    }

    private static Map<String, Object> object(JSONObject value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("Configuration nesting limit");
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> names = value.keys();
        while (names.hasNext()) {
            String name = names.next();
            result.put(name, convert(value.get(name), depth + 1));
        }
        return result;
    }

    private static Object convert(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("Configuration nesting limit");
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return object((JSONObject) value, depth);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                values.add(convert(array.get(index), depth + 1));
            }
            return values;
        }
        return value;
    }
}

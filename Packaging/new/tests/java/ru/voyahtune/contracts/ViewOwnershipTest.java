package ru.voyahtune.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.ViewOwnership;

public final class ViewOwnershipTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("ViewOwnershipTest: passed");
    }
    static void verify() throws Exception {
        View view = new View();
        ViewOwnership owned = new ViewOwnership(new ViewOwnership.Access() {
            @Override public Object read(Object raw, String property) { return ((View) raw).properties.get(property); }
            @Override public void write(Object raw, String property, Object value) {
                View view = (View) raw;
                if (view.fail && value.equals("theme two")) throw new IllegalStateException("Vendor field write failed");
                view.properties.put(property, value);
            }
            @Override public AutoCloseable retain(Object raw, Object state) {
                View view = (View) raw;
                view.anchors.add(state);
                return () -> view.anchors.remove(state);
            }
        });
        view.properties.put("background", "theme one");
        view.properties.put("visible", 0);
        owned.write(view, "background", "custom");
        owned.write(view, "visible", 8);
        require(view.anchors.size() == 1, "Each property added a duplicate anchor");
        view.properties.put("background", "theme two");
        owned.write(view, "background", "custom");
        view.fail = true;
        Throwable failure = null;
        try { owned.close(); }
        catch (Exception caught) { failure = caught; }
        require(failure != null && view.properties.get("visible").equals(0), "Failed field blocked unrelated restoration or reported clean");
        view.fail = false;
        owned.close();
        require(view.properties.get("background").equals("theme two") && view.anchors.isEmpty(), "Cleanup lost later OEM theme or left anchor");
        owned.close();
        failure = null;
        try { owned.write(view, "background", "custom"); }
        catch (Exception caught) { failure = caught; }
        require(failure instanceof IllegalStateException, "Closed ownership accepted new writes");
    }
    static final class View {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<Object> anchors = new ArrayList<>();
        boolean fail;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

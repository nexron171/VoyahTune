package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import ru.voyahtune.agent.policy.LauncherCatalog;

final class LauncherCatalogContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        List<Bean> entries = new ArrayList<>();
        for (JsonElement name : input.getAsJsonArray("existing")) entries.add(new Bean(name.getAsString()));
        for (int index = 0; index < DriveResetContractDriver.integer(input, "legacyCarPlayEntries", 0); index++) {
            Bean bean = new Bean(LauncherCatalog.RESTORE);
            bean.subtype = LauncherCatalog.PREFIX + LauncherCatalog.CARPLAY;
            entries.add(1, bean);
        }
        List<String> installed = new ArrayList<>(List.of(LauncherCatalog.RESTORE));
        for (JsonElement name : input.getAsJsonArray("installed")) installed.add(name.getAsString());
        LauncherCatalog<Bean> catalog = new LauncherCatalog<>(new Access(), new LauncherCatalog.Source() {
            @Override public List<String> installed() { return installed; }
            @Override public boolean carPlayAvailable() { return input.get("carPlayEnabled").getAsBoolean(); }
            @Override public LauncherCatalog.Template fallback() { return new LauncherCatalog.Template(10, 20); }
        });
        for (int count = 0; count < input.get("reconcileCount").getAsInt(); count++) catalog.reconcile(entries, input.get("display").getAsInt());
        JsonArray packages = new JsonArray();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Bean bean : entries) { String target = catalog.target(bean); packages.add(target); unique.add(target); }
        JsonObject result = new JsonObject();
        result.add("packages", packages);
        result.addProperty("duplicateEntries", entries.size() - unique.size());
        catalog.close();
        return result;
    }

    static final class Access implements LauncherCatalog.Entries<Bean> {
        @Override public String packageName(Bean bean) { return bean.name; }
        @Override public String subtype(Bean bean) { return bean.subtype; }
        @Override public void subtype(Bean bean, String subtype) { bean.subtype = subtype; }
        @Override public LauncherCatalog.Template template(Bean bean) { return new LauncherCatalog.Template(bean.icon, bean.label); }
        @Override public Bean create(LauncherCatalog.Template template, String name) {
            Bean bean = new Bean(name);
            bean.icon = template.icon;
            bean.label = template.label;
            return bean;
        }
    }
    static final class Bean {
        final String name;
        String subtype = "";
        int icon = 10;
        int label = 20;
        Bean(String name) { this.name = name; }
    }
}

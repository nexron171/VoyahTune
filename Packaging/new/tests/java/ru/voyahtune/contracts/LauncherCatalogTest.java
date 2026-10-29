package ru.voyahtune.contracts;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.policy.LauncherCatalog;

public final class LauncherCatalogTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("LauncherCatalogTest: passed");
    }

    static void verify() throws Exception {
        LauncherCatalogContractDriver.Bean oem = new LauncherCatalogContractDriver.Bean("oem.app");
        LauncherCatalogContractDriver.Bean restore = new LauncherCatalogContractDriver.Bean(LauncherCatalog.RESTORE);
        LauncherCatalogContractDriver.Bean later = new LauncherCatalogContractDriver.Bean("later.oem.app");
        List<LauncherCatalogContractDriver.Bean> list = new ArrayList<>(List.of(oem, restore));
        boolean[] carplay = {true};
        LauncherCatalog<LauncherCatalogContractDriver.Bean> catalog = new LauncherCatalog<>(new LauncherCatalogContractDriver.Access(), new LauncherCatalog.Source() {
            @Override public List<String> installed() { return List.of(LauncherCatalog.RESTORE, "app.music"); }
            @Override public boolean carPlayAvailable() { return carplay[0]; }
            @Override public LauncherCatalog.Template fallback() { return null; }
        });
        catalog.reconcile(list, 1);
        require(!list.contains(restore) && list.size() == 4, "Passenger catalog did not replace driver shortcut");
        carplay[0] = false;
        catalog.reconcile(list, 1);
        for (LauncherCatalogContractDriver.Bean bean : list) require(!catalog.target(bean).equals(LauncherCatalog.CARPLAY), "Disabled CarPlay remained in catalog");
        list.add(later);
        List<LauncherCatalogContractDriver.Bean> empty = new ArrayList<>();
        catalog.reconcile(empty, 0);
        require(empty.get(0).icon == 10 && empty.get(0).label == 20, "Valid OEM resource template was not retained");
        catalog.close();
        require(list.equals(List.of(oem, restore, later)) && restore.subtype.isEmpty() && empty.isEmpty(), "Cleanup lost OEM entry or overwrote later OEM mutation");
        catalog.close();
        require(list.equals(List.of(oem, restore, later)), "Catalog cleanup was not idempotent");
        Throwable failure = null;
        try { catalog.reconcile(empty, 0); }
        catch (Exception caught) { failure = caught; }
        require(failure instanceof IllegalStateException && empty.isEmpty(), "Closed catalog performed new work");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

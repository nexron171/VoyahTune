package ru.voyahtune.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.WindowGeometryModule;
import ru.voyahtune.agent.modules.WindowMemoryProtectionModule;
import ru.voyahtune.agent.modules.WindowPrivilegesModule;
import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class AndroidWindowAbiTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !arguments[0].equals("--native-library")) throw new IllegalArgumentException("Native bridge required");
        NativeTestBootstrap.load(arguments[1]);
        MethodAccess methods = new MethodAccess(name -> Class.forName(name, false, ClassLoader.getSystemClassLoader()));
        HookRegistry hooks = new HookRegistry(HookBridge.backend());
        new WindowPrivilegesModule(methods, hooks, new WindowPrivilegesModule.Platform() {
            @Override public int nativeUid() { return -1; }
            @Override public int callingUid() { return -1; }
            @Override public long clearIdentity() { throw new AssertionError("Preflight mutated identity"); }
            @Override public void restoreIdentity(long identity) { throw new AssertionError("Preflight mutated identity"); }
            @Override public void error(Exception failure) { throw new AssertionError(failure); }
        }).prepare(Map.of());
        new WindowGeometryModule(methods, hooks, new WindowGeometryModule.Platform() {
            @Override public WindowPolicy policy() throws Exception { return WindowPolicy.read(Map.<String, String>of()::get, 2); }
            @Override public boolean interactive() { return true; }
            @Override public long now() { return 0; }
            @Override public int lift() { return 2; }
            @Override public void watch(ResourceGroup resources, WindowGeometryModule.Events events) { throw new AssertionError("Preflight registered receiver"); }
            @Override public void withWindowManager(AutoCloseable action) { throw new AssertionError("Preflight mutated WindowManager"); }
            @Override public void traversal() { throw new AssertionError("Preflight requested traversal"); }
            @Override public void error(Exception failure) { throw new AssertionError(failure); }
        }).prepare(Map.of());
        List<Exception> optional = new ArrayList<>();
        new WindowMemoryProtectionModule(methods, hooks, new WindowMemoryProtectionModule.Platform() {
            @Override public void recompute() { throw new AssertionError("Preflight changed process priority"); }
            @Override public void error(Exception failure) { optional.add(failure); }
        }).prepare(Map.of());
        if (!optional.isEmpty()) throw new IllegalStateException("Memory ABI unavailable", optional.get(0));
        hooks.close();
        System.out.println("AndroidWindowAbiTest: required geometry/permissions and optional memory ABI passed; no system hooks installed");
    }
}

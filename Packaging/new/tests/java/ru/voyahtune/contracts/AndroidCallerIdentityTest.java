package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.runtime.DexCallGraph;
import ru.voyahtune.agent.runtime.ReflectionAccess;

public final class AndroidCallerIdentityTest {
    public static void main(String[] arguments) throws Exception {
        NativeTestBootstrap.load(arguments[0]);
        verify(arguments[1]);
        System.out.println("AndroidCallerIdentityTest: DEX method identities passed");
    }

    static void verify(String dexPath) throws Exception {
        Class<?>[] owners = {DexCallerFixtures.class, DexCallerFixtures.Base.class};
        for (int kind : new int[] {0, 2}) {
            Object[][] groups = HookBridge.declaredMembersBatch(owners, kind);
            if (groups.length != owners.length)
                throw new AssertionError("Incomplete member groups");
            for (int group = 0; group < groups.length; group++) {
                if (groups[group].length == 0)
                    throw new AssertionError("Empty required member group");
                for (Object member : groups[group]) {
                    if (((java.lang.reflect.Executable) member).getDeclaringClass() != owners[group])
                        throw new AssertionError("Batch changed the defining class");
                }
            }
        }
        DexCallGraph graph = new DexCallGraph();
        graph.add(Files.readAllBytes(Paths.get(dexPath)));
        for (String name : new String[] {"leaf", "middle", "outer", "unrelated"}) {
            Method method = DexCallerFixtures.class.getDeclaredMethod(name, String.class);
            int index = HookBridge.dexMethodIndex(method);
            DexCallGraph.Reference reference = new DexCallGraph.Reference(
                "Lru/voyahtune/contracts/DexCallerFixtures;", name, "(Ljava/lang/String;)Ljava/lang/String;");
            if (!graph.matchesDefinition(reference, index) || graph.matchesDefinition(reference, index + 1)
                || graph.matchesDefinition(reference, -1)) {
                throw new AssertionError("Incorrect DEX identity for " + name + ": " + index);
            }
            Method reflected = null;
            for (Method candidate : ReflectionAccess.allMethods(DexCallerFixtures.class)) {
                if (candidate.getName().equals(name))
                    reflected = candidate;
            }
            if (reflected == null || HookBridge.dexMethodIndex(reflected) != index) {
                throw new AssertionError("Unchecked reflection changed the method identity");
            }
        }
    }
}

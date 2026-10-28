package ru.voyahtune.contracts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import ru.voyahtune.agent.runtime.DexCallGraph;

public final class DexCallGraphTest {
    public static void main(String[] arguments) throws Exception {
        verify(java.nio.file.Paths.get(arguments[0]));
        System.out.println("DexCallGraphTest: passed");
    }
    static void verify(Path path) throws Exception {
        DexCallGraph graph = new DexCallGraph();
        graph.add(Files.readAllBytes(path));
        Set<String> names = graph
                                .callers(new DexCallGraph.Reference("Lru/voyahtune/contracts/DexCallerFixtures;",
                                    "leaf", "(Ljava/lang/String;)Ljava/lang/String;"))
                                .stream()
                                .filter(method -> method.owner.startsWith("Lru/voyahtune/contracts/DexCallerFixtures"))
                                .map(method -> method.name)
                                .collect(Collectors.toSet());
        if (!names.containsAll(Set.of("middle", "outer", "run", "dispatch", "factory", "guarded"))
            || names.contains("unrelated") || names.contains("unrelatedFactory") || names.contains("outsideGuard")
            || names.contains("leaf"))
            throw new AssertionError("Caller closure incorrect: " + names);
        for (String[] inherited : new String[][] {{"value", "inherited"}, {"shared", "inheritedStatic"}}) {
            Set<String> callers =
                graph
                    .callers(new DexCallGraph.Reference(
                        "Lru/voyahtune/contracts/DexCallerFixtures$Base;", inherited[0], "()Ljava/lang/String;"))
                    .stream()
                    .map(method -> method.name)
                    .collect(Collectors.toSet());
            if (!callers.contains(inherited[1]))
                throw new AssertionError("Inherited invocation missed: " + callers);
        }
        try {
            graph.add(new byte[112]);
            throw new AssertionError("Invalid DEX accepted");
        } catch (IllegalArgumentException expected) { /* Invalid metadata cannot authorize partial caller coverage. */
        }
    }
}

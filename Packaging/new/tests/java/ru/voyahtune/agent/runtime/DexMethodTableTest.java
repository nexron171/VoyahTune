package ru.voyahtune.agent.runtime;

public final class DexMethodTableTest {
    public static void main(String[] arguments) {
        DexMethodTable table = new DexMethodTable(100_000);
        for (int index = 0; index < 50_000; index++) {
            if (table.intern(reference(index)) != index)
                throw new AssertionError("Unstable method identity");
        }
        for (int index = 49_999; index >= 0; index--) {
            DexCallGraph.Reference expected = reference(index);
            if (table.find(expected) != index || table.intern(expected) != index
                || !table.reference(index).key().equals(expected.key())) {
                throw new AssertionError("Method lost after table growth: " + expected);
            }
        }
        if (table.find(reference(50_001)) != -1 || table.size() != 50_000) {
            throw new AssertionError("Lookup inserted an absent method");
        }
        DexMethodTable bounded = new DexMethodTable(2);
        bounded.intern(reference(0));
        bounded.intern(reference(1));
        try {
            bounded.intern(reference(2));
            throw new AssertionError("Method bound ignored");
        } catch (IllegalArgumentException expected) {
            if (bounded.size() != 2 || bounded.find(reference(1)) != 1)
                throw new AssertionError("Overflow corrupted entries");
        }
        System.out.println("DexMethodTableTest: growth, collisions, identities and limits passed");
    }

    private static DexCallGraph.Reference reference(int index) {
        return new DexCallGraph.Reference("Lexample/Owner" + index % 937 + ";", "method" + index,
            index % 2 == 0 ? "(Ljava/lang/String;)V" : "([I)Ljava/lang/Object;");
    }
}

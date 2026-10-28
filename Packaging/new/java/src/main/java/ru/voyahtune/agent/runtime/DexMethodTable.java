package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DexMethodTable {
    private static final int BLOCK_SIZE = 16_384;
    private static final int FIELDS = 3;
    private final int maximumMethods;
    private final Map<String, Integer> stringIds = new HashMap<>();
    private final List<String> strings = new ArrayList<>();
    private final List<int[]> definitions = new ArrayList<>();
    private int[] slots = new int[32];
    private int size;

    DexMethodTable(int maximumMethods) {
        if (maximumMethods < 1 || maximumMethods > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Bounded DEX method capacity required");
        }
        this.maximumMethods = maximumMethods;
    }

    int size() {
        return size;
    }

    int find(DexCallGraph.Reference method) {
        Integer owner = stringIds.get(method.owner);
        Integer name = stringIds.get(method.name);
        Integer prototype = stringIds.get(method.descriptor);
        if (owner == null || name == null || prototype == null) {
            return -1;
        }
        int slot = locate(owner, name, prototype);
        return slots[slot] - 1;
    }

    int intern(DexCallGraph.Reference method) {
        int existing = find(method);
        if (existing >= 0) {
            return existing;
        }
        if (size == maximumMethods) {
            throw new IllegalArgumentException("DEX method coverage limit exceeded");
        }
        if ((size + 1) * 10 > slots.length * 7) {
            grow();
        }
        int owner = stringId(method.owner);
        int name = stringId(method.name);
        int prototype = stringId(method.descriptor);
        if (size % BLOCK_SIZE == 0) {
            definitions.add(new int[BLOCK_SIZE * FIELDS]);
        }
        int[] block = definitions.get(size / BLOCK_SIZE);
        int offset = size % BLOCK_SIZE * FIELDS;
        block[offset] = owner;
        block[offset + 1] = name;
        block[offset + 2] = prototype;
        slots[locate(owner, name, prototype)] = size + 1;
        return size++;
    }

    DexCallGraph.Reference reference(int id) {
        if (id < 0 || id >= size) {
            throw new IndexOutOfBoundsException("DEX method identity outside table");
        }
        int[] block = definitions.get(id / BLOCK_SIZE);
        int offset = id % BLOCK_SIZE * FIELDS;
        return new DexCallGraph.Reference(
            strings.get(block[offset]), strings.get(block[offset + 1]), strings.get(block[offset + 2]));
    }

    private int stringId(String value) {
        Integer previous = stringIds.get(value);
        if (previous != null) {
            return previous;
        }
        int id = strings.size();
        strings.add(value);
        stringIds.put(value, id);
        return id;
    }

    private int locate(int owner, int name, int prototype) {
        int mask = slots.length - 1;
        int slot = hash(owner, name, prototype) & mask;
        while (slots[slot] != 0) {
            int id = slots[slot] - 1;
            int[] block = definitions.get(id / BLOCK_SIZE);
            int offset = id % BLOCK_SIZE * FIELDS;
            if (block[offset] == owner && block[offset + 1] == name && block[offset + 2] == prototype) {
                break;
            }
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        slots = new int[slots.length * 2];
        for (int id = 0; id < size; id++) {
            int[] block = definitions.get(id / BLOCK_SIZE);
            int offset = id % BLOCK_SIZE * FIELDS;
            slots[locate(block[offset], block[offset + 1], block[offset + 2])] = id + 1;
        }
    }

    private static int hash(int owner, int name, int prototype) {
        long value = owner * 0x9e3779b97f4a7c15L ^ Long.rotateLeft(name * 0xc2b2ae3d27d4eb4fL, 21)
            ^ Long.rotateLeft(prototype * 0x165667b19e3779f9L, 42);
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        return (int) (value ^ (value >>> 33));
    }
}

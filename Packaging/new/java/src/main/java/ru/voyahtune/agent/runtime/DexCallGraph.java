package ru.voyahtune.agent.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DexCallGraph {
    public static final class Reference {
        public final String owner;
        public final String name;
        public final String descriptor;
        private String key;

        public Reference(String owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
        public String key() {
            if (key == null)
                key = owner + "->" + name + descriptor;
            return key;
        }
        @Override
        public String toString() {
            return key();
        }
    }

    private static final int BLOCK_SIZE = 16384;
    private static final int MAX_METHODS = 2 * 1024 * 1024;
    private static final int MAX_EDGES = 8 * 1024 * 1024;
    private final DexMethodTable methods = new DexMethodTable(MAX_METHODS);
    private final List<int[]> heads = new ArrayList<>();
    private final List<int[]> definitionIndices = new ArrayList<>();
    private final Map<Integer, Set<Integer>> alternateIndices = new HashMap<>();
    private final List<int[]> edges = new ArrayList<>();
    private final Map<String, Set<String>> parents = new HashMap<>();
    private final Map<String, Set<String>> children = new HashMap<>();
    private final java.util.BitSet inlineable = new java.util.BitSet();
    private final java.util.BitSet staticMethods = new java.util.BitSet();
    private final java.util.BitSet virtualMethods = new java.util.BitSet();
    private final java.util.BitSet nonInlineable = new java.util.BitSet();
    private final java.util.BitSet directMethods = new java.util.BitSet();
    private int edgeCount;

    public void add(byte[] dex) {
        new Reader(dex).parse(this);
    }

    public boolean matchesDefinition(Reference method, int dexIndex) {
        int id = methods.find(method);
        if (id < 0 || dexIndex < 0)
            return false;
        return definitionIndices.get(id / BLOCK_SIZE)[id % BLOCK_SIZE] == dexIndex + 1
            || alternateIndices.getOrDefault(id, Set.of()).contains(dexIndex);
    }

    private void definition(int id, int dexIndex) {
        int[] block = definitionIndices.get(id / BLOCK_SIZE);
        int previous = block[id % BLOCK_SIZE];
        if (previous == 0)
            block[id % BLOCK_SIZE] = dexIndex + 1;
        else if (previous != dexIndex + 1)
            alternateIndices.computeIfAbsent(id, ignored -> new HashSet<>()).add(dexIndex);
    }

    public List<Reference> callers(Reference target) {
        return callers(target, Set.of());
    }

    public List<Reference> callers(Reference target, Set<String> covered) {
        java.util.BitSet visited = new java.util.BitSet(methods.size());
        java.util.BitSet selected = new java.util.BitSet(methods.size());
        ArrayDeque<Reference> remaining = new ArrayDeque<>();
        remaining.add(target);
        int targetId = methods.find(target);
        while (!remaining.isEmpty()) {
            Reference method = remaining.removeFirst();
            if (covered.contains(method.key()))
                continue;
            int declaration = methods.find(method);
            if (declaration >= 0 && nonInlineable.get(declaration) && !inlineable.get(declaration))
                continue;
            boolean constructor = method.name.startsWith("<");
            boolean direct =
                declaration >= 0 && directMethods.get(declaration) && !virtualMethods.get(declaration);
            Set<String> owners = constructor || direct ? new LinkedHashSet<>(Set.of(method.owner))
                                                       : hierarchy(method.owner, parents);
            if (!constructor && (!direct || staticMethods.get(declaration)))
                owners.addAll(hierarchy(method.owner, children));
            for (String owner : owners) {
                int id = methods.find(owner.equals(method.owner)
                        ? method
                        : new Reference(owner, method.name, method.descriptor));
                if (id < 0 || visited.get(id))
                    continue;
                visited.set(id);
                for (int edge = heads.get(id / BLOCK_SIZE)[id % BLOCK_SIZE]; edge != 0;) {
                    int position = edge - 1;
                    int[] block = edges.get(position / BLOCK_SIZE);
                    int caller = block[position % BLOCK_SIZE * 2];
                    edge = block[position % BLOCK_SIZE * 2 + 1];
                    if ((targetId < 0 || caller != targetId) && !selected.get(caller)) {
                        Reference reference = methods.reference(caller);
                        if (covered.contains(reference.key()))
                            continue;
                        selected.set(caller);
                        remaining.add(reference);
                    }
                }
            }
        }
        List<Reference> result = new ArrayList<>(selected.cardinality());
        for (int id = selected.nextSetBit(0); id >= 0; id = selected.nextSetBit(id + 1))
            result.add(methods.reference(id));
        return List.copyOf(result);
    }

    private Set<String> hierarchy(String owner, Map<String, Set<String>> relations) {
        Set<String> result = new LinkedHashSet<>();
        ArrayDeque<String> remaining = new ArrayDeque<>();
        remaining.add(owner);
        while (!remaining.isEmpty()) {
            String current = remaining.removeFirst();
            if (result.add(current))
                remaining.addAll(relations.getOrDefault(current, Set.of()));
        }
        return result;
    }

    private int method(Reference reference) {
        int previousSize = methods.size();
        int id = methods.intern(reference);
        if (id == previousSize && id % BLOCK_SIZE == 0) {
            heads.add(new int[BLOCK_SIZE]);
            definitionIndices.add(new int[BLOCK_SIZE]);
        }
        return id;
    }

    private void edge(int caller, int callee) {
        if (edgeCount >= MAX_EDGES)
            throw new IllegalArgumentException("DEX invocation coverage limit exceeded");
        if (edgeCount % BLOCK_SIZE == 0)
            edges.add(new int[BLOCK_SIZE * 2]);
        int[] block = edges.get(edgeCount / BLOCK_SIZE);
        int[] head = heads.get(callee / BLOCK_SIZE);
        block[edgeCount % BLOCK_SIZE * 2] = caller;
        block[edgeCount % BLOCK_SIZE * 2 + 1] = head[callee % BLOCK_SIZE];
        head[callee % BLOCK_SIZE] = ++edgeCount;
    }

    private static final class Reader {
        private static final int[] WIDTH = widths();
        private final byte[] bytes;
        private int cursor;
        private String[] strings;
        private String[] types;
        private String[] prototypes;
        private int[] methods;

        Reader(byte[] bytes) {
            if (bytes.length < 112 || bytes.length > 64 * 1024 * 1024 || bytes[0] != 'd' || bytes[1] != 'e'
                || bytes[2] != 'x' || bytes[3] != '\n' || bytes[7] != 0)
                throw new IllegalArgumentException("Supported bounded DEX required");
            this.bytes = bytes;
            if (u4(32) != bytes.length || u4(36) != 112 || u4(40) != 0x12345678)
                throw new IllegalArgumentException("DEX header mismatch");
        }

        void parse(DexCallGraph graph) {
            strings = new String[count(56)];
            int offset = u4(60);
            for (int index = 0; index < strings.length; index++)
                strings[index] = string(u4(offset + index * 4));
            types = new String[count(64)];
            offset = u4(68);
            for (int index = 0; index < types.length; index++)
                types[index] = at(strings, u4(offset + index * 4));
            prototypes = new String[count(72)];
            offset = u4(76);
            for (int index = 0; index < prototypes.length; index++) {
                int entry = offset + index * 12;
                StringBuilder descriptor = new StringBuilder("(");
                for (int parameter : typeList(u4(entry + 8)))
                    descriptor.append(at(types, parameter));
                prototypes[index] = descriptor.append(')').append(at(types, u4(entry + 4))).toString();
            }
            methods = new int[count(88)];
            offset = u4(92);
            for (int index = 0; index < methods.length; index++) {
                int entry = offset + index * 8;
                methods[index] = graph.method(new Reference(
                    at(types, u2(entry)), at(strings, u4(entry + 4)), at(prototypes, u2(entry + 2))));
            }
            int classes = count(96);
            offset = u4(100);
            for (int index = 0; index < classes; index++) {
                int entry = offset + index * 32;
                String owner = at(types, u4(entry));
                Set<String> parents = graph.parents.computeIfAbsent(owner, ignored -> new LinkedHashSet<>());
                int superclass = u4(entry + 8);
                if (superclass != -1)
                    parents.add(at(types, superclass));
                for (int implemented : typeList(u4(entry + 12)))
                    parents.add(at(types, implemented));
                for (String parent : parents)
                    graph.children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(owner);
                int data = u4(entry + 24);
                if (data != 0)
                    classData(data, graph);
            }
        }

        private void classData(int offset, DexCallGraph graph) {
            cursor = offset;
            int staticFields = uleb();
            int instanceFields = uleb();
            int directMethods = uleb();
            int virtualMethods = uleb();
            for (int index = 0; index < staticFields + instanceFields; index++) {
                uleb();
                uleb();
            }
            encodedMethods(directMethods, graph);
            encodedMethods(virtualMethods, graph);
        }

        private void encodedMethods(int count, DexCallGraph graph) {
            int method = 0;
            for (int index = 0; index < count; index++) {
                method = Math.addExact(method, uleb());
                int access = uleb();
                int code = uleb();
                if (method < 0 || method >= methods.length)
                    throw new IllegalArgumentException("DEX method index outside table");
                int id = methods[method];
                graph.definition(id, method);
                if ((access & 0x0a) != 0)
                    graph.directMethods.set(id);
                else
                    graph.virtualMethods.set(id);
                if ((access & 0x08) != 0)
                    graph.staticMethods.set(id);
                // ART 11 refuses to inline bodies with exception handlers.
                if (code != 0 && u2(code + 6) != 0)
                    graph.nonInlineable.set(id);
                else if (code != 0)
                    graph.inlineable.set(id);
                if (code != 0)
                    code(id, code, graph);
            }
        }

        private void code(int caller, int offset, DexCallGraph graph) {
            Set<Integer> invoked = new HashSet<>();
            int units = u4(offset + 12);
            int start = offset + 16;
            range(start, (long) units * 2);
            for (int position = 0; position < units;) {
                int word = u2(start + position * 2);
                int opcode = word & 255;
                int width;
                if (opcode == 0 && word != 0)
                    width = payload(start + position * 2, word);
                else
                    width = WIDTH[opcode];
                if (width < 1 || (long) position + width > units)
                    throw new IllegalArgumentException("DEX instruction boundary invalid");
                if (opcode >= 0x6e && opcode <= 0x72 || opcode >= 0x74 && opcode <= 0x78 || opcode == 0xfa
                    || opcode == 0xfb) {
                    int callee = u2(start + (position + 1) * 2);
                    if (callee >= methods.length)
                        throw new IllegalArgumentException("DEX invocation outside method table");
                    if (invoked.add(callee))
                        graph.edge(caller, methods[callee]);
                }
                position += width;
            }
        }

        private int payload(int offset, int signature) {
            long width;
            switch (signature) {
                case 0x0100:
                    width = 4L + u2(offset + 2) * 2L;
                    break;
                case 0x0200:
                    width = 2L + u2(offset + 2) * 4L;
                    break;
                case 0x0300:
                    width = 4L + ((u4(offset + 4) & 0xffffffffL) * u2(offset + 2) + 1) / 2;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown DEX payload");
            }
            if (width > Integer.MAX_VALUE)
                throw new IllegalArgumentException("DEX payload too large");
            return (int) width;
        }

        private int[] typeList(int offset) {
            if (offset == 0)
                return new int[0];
            int count = u4(offset);
            if (count < 0 || count > 65535)
                throw new IllegalArgumentException("DEX parameter limit exceeded");
            int[] result = new int[count];
            for (int index = 0; index < count; index++)
                result[index] = u2(offset + 4 + index * 2);
            return result;
        }

        private String string(int offset) {
            int saved = cursor;
            cursor = offset;
            int expected = uleb();
            if (expected > 1024 * 1024)
                throw new IllegalArgumentException("DEX string too large");
            StringBuilder value = new StringBuilder();
            while (true) {
                int first = u1(cursor++);
                if (first == 0)
                    break;
                if ((first & 0x80) == 0)
                    value.append((char) first);
                else if ((first & 0xe0) == 0xc0)
                    value.append((char) (((first & 31) << 6) | continuation()));
                else if ((first & 0xf0) == 0xe0)
                    value.append((char) (((first & 15) << 12) | continuation() << 6 | continuation()));
                else
                    throw new IllegalArgumentException("Invalid DEX modified UTF8");
            }
            cursor = saved;
            if (value.length() != expected)
                throw new IllegalArgumentException("DEX string length mismatch");
            return value.toString();
        }

        private int continuation() {
            int value = u1(cursor++);
            if ((value & 0xc0) != 0x80)
                throw new IllegalArgumentException("Invalid DEX UTF8 continuation");
            return value & 63;
        }
        private int count(int offset) {
            int count = u4(offset);
            if (count < 0 || count > 1024 * 1024)
                throw new IllegalArgumentException("DEX table limit exceeded");
            return count;
        }
        private int uleb() {
            int value = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                int next = u1(cursor++);
                if (shift == 28 && (next & 0xf8) != 0)
                    throw new IllegalArgumentException("DEX ULEB overflow");
                value |= (next & 127) << shift;
                if ((next & 128) == 0)
                    return value;
            }
            throw new IllegalArgumentException("Invalid DEX ULEB");
        }
        private int u1(int offset) {
            range(offset, 1);
            return bytes[offset] & 255;
        }
        private int u2(int offset) {
            return u1(offset) | u1(offset + 1) << 8;
        }
        private int u4(int offset) {
            return u2(offset) | u2(offset + 2) << 16;
        }
        private void range(int offset, long count) {
            if (offset < 0 || count < 0 || (long) offset + count > bytes.length)
                throw new IllegalArgumentException("DEX offset outside input");
        }
        private static String at(String[] values, int index) {
            if (index < 0 || index >= values.length)
                throw new IllegalArgumentException("DEX reference outside table");
            return values[index];
        }
        private static int[] widths() {
            int[] width = new int[256];
            Arrays.fill(width, 1);
            for (int opcode : new int[] {0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20,
                     0x22, 0x23, 0x29, 0xfe, 0xff})
                width[opcode] = 2;
            for (int[] interval : new int[][] {{0x2d, 0x3d}, {0x44, 0x6d}, {0x90, 0xaf}, {0xd0, 0xe2}})
                for (int opcode = interval[0]; opcode <= interval[1]; opcode++)
                    width[opcode] = 2;
            for (int opcode : new int[] {
                     0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c, 0xfc, 0xfd})
                width[opcode] = 3;
            for (int opcode = 0x6e; opcode <= 0x78; opcode++)
                if (opcode != 0x73)
                    width[opcode] = 3;
            width[0x18] = 5;
            width[0xfa] = 4;
            width[0xfb] = 4;
            return width;
        }
    }
}

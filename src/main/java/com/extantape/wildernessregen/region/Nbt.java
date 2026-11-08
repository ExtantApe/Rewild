package com.extantape.wildernessregen.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small reader and writer for Minecraft's binary NBT, enough to inspect and edit chunk data offline.
 * Compounds are {@link Compound}, lists are {@link NbtList}, arrays are byte[], int[] and long[], and numbers and strings
 * are their boxed Java types. Reading then writing a tag gives back the same bytes.
 */
public final class Nbt {
    private Nbt() {}

    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Nesting limit, the same as the game's, so a corrupt file can't overflow the stack. */
    private static final int MAX_DEPTH = 512;

    public static final class Compound extends LinkedHashMap<String, Object> {
        public Compound getCompound(String key) {
            return get(key) instanceof Compound c ? c : null;
        }

        public NbtList getList(String key) {
            return get(key) instanceof NbtList l ? l : null;
        }

        public String getString(String key) {
            return get(key) instanceof String s ? s : null;
        }

        public Number getNumber(String key) {
            return get(key) instanceof Number n ? n : null;
        }

        public int getInt(String key, int fallback) {
            return get(key) instanceof Number n ? n.intValue() : fallback;
        }

        public long getLong(String key, long fallback) {
            return get(key) instanceof Number n ? n.longValue() : fallback;
        }
    }

    public record NbtList(int elementType, List<Object> items) {
        public NbtList {
            items = new ArrayList<>(items);
        }

        public List<Compound> compounds() {
            List<Compound> out = new ArrayList<>();
            for (Object o : items) if (o instanceof Compound c) out.add(c);
            return out;
        }
    }

    /** Reads a root tag from uncompressed bytes. The root name is discarded. */
    public static Compound read(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int type = in.readUnsignedByte();
        if (type != COMPOUND) throw new IOException("root tag isn't a compound (type " + type + ")");
        in.readUTF();
        return (Compound) readPayload(in, COMPOUND, 0);
    }

    public static byte[] write(Compound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writePayload(out, root, 0);
        out.flush();
        return bytes.toByteArray();
    }

    private static Object readPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: {
                byte[] a = new byte[checkLength(in.readInt())];
                in.readFully(a);
                return a;
            }
            case STRING: return in.readUTF();
            case LIST: {
                int elementType = in.readUnsignedByte();
                int n = checkLength(in.readInt());
                List<Object> items = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) items.add(readPayload(in, elementType, depth + 1));
                return new NbtList(elementType, items);
            }
            case COMPOUND: {
                Compound c = new Compound();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) return c;
                    String name = in.readUTF();
                    c.put(name, readPayload(in, t, depth + 1));
                }
            }
            case INT_ARRAY: {
                int n = checkLength(in.readInt());
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case LONG_ARRAY: {
                int n = checkLength(in.readInt());
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int checkLength(int n) throws IOException {
        if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("bad NBT length " + n);
        return n;
    }

    static int typeOf(Object o) throws IOException {
        if (o instanceof Byte) return BYTE;
        if (o instanceof Short) return SHORT;
        if (o instanceof Integer) return INT;
        if (o instanceof Long) return LONG;
        if (o instanceof Float) return FLOAT;
        if (o instanceof Double) return DOUBLE;
        if (o instanceof byte[]) return BYTE_ARRAY;
        if (o instanceof String) return STRING;
        if (o instanceof NbtList) return LIST;
        if (o instanceof Compound) return COMPOUND;
        if (o instanceof int[]) return INT_ARRAY;
        if (o instanceof long[]) return LONG_ARRAY;
        throw new IOException("can't write " + (o == null ? "null" : o.getClass().getName()) + " as NBT");
    }

    private static void writePayload(DataOutput out, Object o, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (typeOf(o)) {
            case BYTE -> out.writeByte((Byte) o);
            case SHORT -> out.writeShort((Short) o);
            case INT -> out.writeInt((Integer) o);
            case LONG -> out.writeLong((Long) o);
            case FLOAT -> out.writeFloat((Float) o);
            case DOUBLE -> out.writeDouble((Double) o);
            case BYTE_ARRAY -> {
                byte[] a = (byte[]) o;
                out.writeInt(a.length);
                out.write(a);
            }
            case STRING -> out.writeUTF((String) o);
            case LIST -> {
                NbtList l = (NbtList) o;
                int t = l.items().isEmpty() ? l.elementType() : typeOf(l.items().get(0));
                out.writeByte(t);
                out.writeInt(l.items().size());
                for (Object item : l.items()) writePayload(out, item, depth + 1);
            }
            case COMPOUND -> {
                for (Map.Entry<String, Object> e : ((Compound) o).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue(), depth + 1);
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> {
                int[] a = (int[]) o;
                out.writeInt(a.length);
                for (int v : a) out.writeInt(v);
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) o;
                out.writeInt(a.length);
                for (long v : a) out.writeLong(v);
            }
            default -> throw new IOException("unreachable");
        }
    }
}

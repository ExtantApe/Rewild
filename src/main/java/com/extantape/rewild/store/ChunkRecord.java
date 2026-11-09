package com.extantape.rewild.store;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the tracker knows about one chunk. Times are epoch milliseconds, 0 meaning never.
 *
 * <p>A chunk is <b>known</b> only if it was generated while tracking was running, so every player change to it has been
 * seen. Anything else is unknown, and is never reset automatically.
 */
public final class ChunkRecord {
    public final int x, z;
    public boolean known;
    /** The generator fingerprint epoch the chunk was generated under. */
    public int epoch;
    public long bornAt, lastChange, lastVisit, lastReset;
    /** Player-removed blocks that count as damage, by 16-block section (section Y index, as in chunk data). */
    public final Map<Integer, Integer> removedBySection = new HashMap<>();
    public int explosionBlocks;
    /** Player-placed blocks worth remembering, by packed position: material name. Includes dirt paths and farmland. */
    public final Map<Long, String> placed = new HashMap<>();
    /** Containers a player has put items into, natural or not. */
    public final Set<Long> filledContainers = new HashSet<>();
    /** Last player change and last nearby visit, by section, so a mine under a lived-in house can still be judged. */
    public final Map<Integer, Long> changeBySection = new HashMap<>();
    public final Map<Integer, Long> visitBySection = new HashMap<>();

    public ChunkRecord(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public long key() {
        return key(x, z);
    }

    /** Packs a block position within a chunk: local x and z (0 to 15) and world y. */
    public static long pos(int localX, int y, int localZ) {
        return ((long) y << 8) | ((localX & 15) << 4) | (localZ & 15);
    }

    public static int posX(long p) {
        return (int) ((p >> 4) & 15);
    }

    public static int posZ(long p) {
        return (int) (p & 15);
    }

    public static int posY(long p) {
        return (int) (p >> 8);
    }

    public int removed() {
        int n = 0;
        for (int v : removedBySection.values()) n += v;
        return n;
    }

    /** Latest change or visit time in sections below the given one. */
    public long lastChangeBelowSection(int sectionY) {
        long t = 0;
        for (var e : changeBySection.entrySet()) if (e.getKey() < sectionY) t = Math.max(t, e.getValue());
        return t;
    }

    public long lastVisitBelowSection(int sectionY) {
        long t = 0;
        for (var e : visitBySection.entrySet()) if (e.getKey() < sectionY) t = Math.max(t, e.getValue());
        return t;
    }

    public int removedBelowSection(int sectionY) {
        int n = 0;
        for (var e : removedBySection.entrySet()) if (e.getKey() < sectionY) n += e.getValue();
        return n;
    }

    /** Forget everything after a full reset: the chunk regenerates as new and is known again once it does. */
    public void clearForReset(long now) {
        known = false;
        removedBySection.clear();
        explosionBlocks = 0;
        placed.clear();
        filledContainers.clear();
        changeBySection.clear();
        visitBySection.clear();
        lastReset = now;
        lastChange = 0;
    }

    /** Forget what was below the cut after an underground repair. */
    public void clearBelow(int cutY, long now) {
        removedBySection.keySet().removeIf(s -> s * 16 < cutY);
        placed.keySet().removeIf(p -> posY(p) < cutY);
        filledContainers.removeIf(p -> posY(p) < cutY);
        changeBySection.keySet().removeIf(sec -> sec * 16 < cutY);
        visitBySection.keySet().removeIf(sec -> sec * 16 < cutY);
        explosionBlocks = 0;
        lastReset = now;
    }

    public ChunkRecord copy() {
        ChunkRecord c = new ChunkRecord(x, z);
        c.known = known;
        c.epoch = epoch;
        c.bornAt = bornAt;
        c.lastChange = lastChange;
        c.lastVisit = lastVisit;
        c.lastReset = lastReset;
        c.removedBySection.putAll(removedBySection);
        c.explosionBlocks = explosionBlocks;
        c.placed.putAll(placed);
        c.filledContainers.addAll(filledContainers);
        c.changeBySection.putAll(changeBySection);
        c.visitBySection.putAll(visitBySection);
        return c;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(z);
        out.writeBoolean(known);
        out.writeInt(epoch);
        out.writeLong(bornAt);
        out.writeLong(lastChange);
        out.writeLong(lastVisit);
        out.writeLong(lastReset);
        out.writeInt(explosionBlocks);
        out.writeShort(removedBySection.size());
        for (var e : removedBySection.entrySet()) {
            out.writeShort(e.getKey());
            out.writeInt(e.getValue());
        }
        out.writeInt(placed.size());
        for (var e : placed.entrySet()) {
            out.writeLong(e.getKey());
            out.writeUTF(e.getValue());
        }
        out.writeInt(filledContainers.size());
        for (long p : filledContainers) out.writeLong(p);
        writeTimes(out, changeBySection);
        writeTimes(out, visitBySection);
    }

    private static void writeTimes(DataOutputStream out, Map<Integer, Long> m) throws IOException {
        out.writeShort(m.size());
        for (var e : m.entrySet()) {
            out.writeShort(e.getKey());
            out.writeLong(e.getValue());
        }
    }

    private static void readTimes(DataInputStream in, Map<Integer, Long> m) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) m.put((int) in.readShort(), in.readLong());
    }

    public static ChunkRecord read(DataInputStream in) throws IOException {
        ChunkRecord c = new ChunkRecord(in.readInt(), in.readInt());
        c.known = in.readBoolean();
        c.epoch = in.readInt();
        c.bornAt = in.readLong();
        c.lastChange = in.readLong();
        c.lastVisit = in.readLong();
        c.lastReset = in.readLong();
        c.explosionBlocks = in.readInt();
        int sections = in.readUnsignedShort();
        for (int i = 0; i < sections; i++) c.removedBySection.put((int) in.readShort(), in.readInt());
        int placed = in.readInt();
        if (placed < 0 || placed > 16 * 16 * 4096) throw new IOException("bad placed count " + placed);
        for (int i = 0; i < placed; i++) c.placed.put(in.readLong(), in.readUTF());
        int filled = in.readInt();
        if (filled < 0 || filled > 16 * 16 * 4096) throw new IOException("bad container count " + filled);
        for (int i = 0; i < filled; i++) c.filledContainers.add(in.readLong());
        readTimes(in, c.changeBySection);
        readTimes(in, c.visitBySection);
        return c;
    }
}

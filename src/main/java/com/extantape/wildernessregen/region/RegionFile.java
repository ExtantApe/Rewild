package com.extantape.wildernessregen.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * One Anvil region file (.mca) held in memory: a 4 KiB table of chunk locations, a 4 KiB table of timestamps, then
 * chunk data in 4 KiB sectors. The game uses this format for blocks (region/), entities (entities/) and points of
 * interest (poi/). Edits are made on a copy in memory and written back with {@link #writeAtomically}, so a crash leaves
 * either the old file or the new one, never half of each.
 */
public final class RegionFile {
    public static final int SECTOR = 4096;
    public static final int EXTERNAL_FLAG = 128;

    /** A chunk's stored bytes, exactly as the game wrote them. {@code external} holds a .mcc file's contents, if used. */
    public record RawChunk(int compression, byte[] data, byte[] external, int timestamp) {
        public boolean isExternal() {
            return (compression & EXTERNAL_FLAG) != 0;
        }

        public int compressionType() {
            return compression & ~EXTERNAL_FLAG;
        }
    }

    private final Path path;
    private final RawChunk[] chunks = new RawChunk[1024];

    private RegionFile(Path path) {
        this.path = path;
    }

    public static RegionFile empty(Path path) {
        return new RegionFile(path);
    }

    /** Reads a region file. A missing file reads as empty. Damaged entries throw rather than being skipped. */
    public static RegionFile load(Path path) throws IOException {
        RegionFile r = new RegionFile(path);
        if (!Files.exists(path)) return r;
        byte[] all = Files.readAllBytes(path);
        if (all.length == 0) return r;
        if (all.length < 2 * SECTOR) throw new IOException(path + " is shorter than a region header");
        ByteBuffer b = ByteBuffer.wrap(all);
        for (int i = 0; i < 1024; i++) {
            int loc = b.getInt(i * 4);
            int ts = b.getInt(SECTOR + i * 4);
            if (loc == 0) continue;
            int offset = (loc >>> 8) * SECTOR;
            int sectors = loc & 0xFF;
            if (offset < 2 * SECTOR || offset + 5 > all.length) throw new IOException(path + ": chunk " + i + " points outside the file");
            int length = b.getInt(offset);
            if (length < 1 || offset + 4 + length > all.length || (sectors != 0 && length + 4 > sectors * SECTOR && sectors != 255)) {
                throw new IOException(path + ": chunk " + i + " has a bad length " + length);
            }
            int compression = all[offset + 4] & 0xFF;
            byte[] data = new byte[length - 1];
            System.arraycopy(all, offset + 5, data, 0, data.length);
            byte[] external = null;
            if ((compression & EXTERNAL_FLAG) != 0) {
                Path mcc = externalPath(path, i);
                if (!Files.exists(mcc)) throw new IOException(path + ": chunk " + i + " is stored in " + mcc.getFileName() + ", which is missing");
                external = Files.readAllBytes(mcc);
            }
            r.chunks[i] = new RawChunk(compression, data, external, ts);
        }
        return r;
    }

    /** The same chunks, to be written to another path. */
    public RegionFile withPath(Path other) {
        RegionFile r = new RegionFile(other);
        System.arraycopy(chunks, 0, r.chunks, 0, chunks.length);
        return r;
    }

    public Path path() {
        return path;
    }

    public static int index(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    public static String fileName(int chunkX, int chunkZ) {
        return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
    }

    /** The .mcc file the game uses for a chunk too big for the region file. */
    public static Path externalPath(Path region, int index) {
        String[] parts = region.getFileName().toString().split("\\.");
        int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
        int cx = rx * 32 + (index & 31), cz = rz * 32 + (index >> 5);
        return region.resolveSibling("c." + cx + "." + cz + ".mcc");
    }

    public RawChunk get(int index) {
        return chunks[index];
    }

    public boolean has(int index) {
        return chunks[index] != null;
    }

    public int count() {
        int n = 0;
        for (RawChunk c : chunks) if (c != null) n++;
        return n;
    }

    public void remove(int index) {
        chunks[index] = null;
    }

    public void put(int index, RawChunk chunk) {
        chunks[index] = chunk;
    }

    /** Decompresses and parses a chunk. Returns null if it isn't stored. */
    public Nbt.Compound readNbt(int index) throws IOException {
        RawChunk c = chunks[index];
        if (c == null) return null;
        return Nbt.read(decompress(c));
    }

    public static byte[] decompress(RawChunk c) throws IOException {
        byte[] bytes = c.isExternal() ? c.external() : c.data();
        InputStream in = switch (c.compressionType()) {
            case 1 -> new GZIPInputStream(new ByteArrayInputStream(bytes));
            case 2 -> new InflaterInputStream(new ByteArrayInputStream(bytes));
            case 3 -> new ByteArrayInputStream(bytes);
            case 4 -> lz4(bytes);
            default -> throw new IOException("unsupported chunk compression " + c.compressionType());
        };
        try (in) {
            return in.readAllBytes();
        }
    }

    /** Compresses with zlib, the game's default, for chunks this plugin writes itself. */
    public static RawChunk compress(byte[] nbt, int timestamp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var z = new java.util.zip.DeflaterOutputStream(out)) {
            z.write(nbt);
        }
        byte[] data = out.toByteArray();
        if (data.length + 5 > 255 * SECTOR) return new RawChunk(2 | EXTERNAL_FLAG, new byte[0], data, timestamp);
        return new RawChunk(2, data, null, timestamp);
    }

    /** LZ4 is optional in the game's settings. Its library ships with the server, so it's loaded only if needed. */
    private static InputStream lz4(byte[] bytes) throws IOException {
        try {
            Class<?> cls = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
            return (InputStream) cls.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(bytes));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("chunk uses LZ4 compression, and the LZ4 library isn't available", e);
        }
    }

    /** The bytes of a compact region file holding the current chunks. Returns null if there are none. */
    public byte[] toBytes() throws IOException {
        if (count() == 0) return null;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            RawChunk c = chunks[i];
            if (c == null) continue;
            int length = c.data().length + 1;
            int sectors = (length + 4 + SECTOR - 1) / SECTOR;
            if (sectors > 255) throw new IOException("chunk " + i + " is too big to store inline");
            ByteBuffer entry = ByteBuffer.allocate(sectors * SECTOR);
            entry.putInt(length);
            entry.put((byte) c.compression());
            entry.put(c.data());
            body.write(entry.array());
            header.putInt(i * 4, (sector << 8) | sectors);
            header.putInt(SECTOR + i * 4, c.timestamp());
            sector += sectors;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream(2 * SECTOR + body.size());
        all.write(header.array());
        body.writeTo(all);
        return all.toByteArray();
    }

    /**
     * Writes this region to its path through a temporary file, fsynced and renamed into place. External .mcc files for
     * chunks still present are written first. A region with no chunks left is written as an empty file, which the game
     * reads as empty, rather than deleted.
     */
    public void writeAtomically() throws IOException {
        for (int i = 0; i < 1024; i++) {
            RawChunk c = chunks[i];
            if (c != null && c.isExternal()) writeFileAtomically(externalPath(path, i), c.external());
        }
        byte[] bytes = toBytes();
        writeFileAtomically(path, bytes == null ? new byte[0] : bytes);
    }

    public static void writeFileAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".wr-tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        syncDirectory(target.getParent());
    }

    /** Makes a rename durable. Not every platform allows opening a directory; that's not an error. */
    public static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort.
        }
    }
}

// Offline checks on world files for the acceptance tests. Run with the plugin's classes on the classpath:
//   java -cp build/classes/java/main test/tools/Inspect.java <command> ...
import com.extantape.wildernessregen.region.ChunkFacts;
import com.extantape.wildernessregen.region.Nbt;
import com.extantape.wildernessregen.region.RegionFile;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Inspect {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            // has <dim> <kind> x z: prints present/absent and a hash of the stored bytes
            case "has" -> {
                RegionFile r = RegionFile.load(Path.of(a[1]).resolve(a[2]).resolve(RegionFile.fileName(i(a[3]), i(a[4]))));
                var c = r.get(RegionFile.index(i(a[3]), i(a[4])));
                System.out.println(c == null ? "absent" : "present " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(RegionFile.decompress(c))).substring(0, 16));
            }
            // compare <dimA> <dimB> x z: block-by-block comparison of one chunk in two worlds
            case "compare" -> {
                String[][][] A = blocks(Path.of(a[1]), i(a[3]), i(a[4])), B = blocks(Path.of(a[2]), i(a[3]), i(a[4]));
                int same = 0, total = 0, edgeSame = 0, edgeTotal = 0, terrainSame = 0, terrainTotal = 0;
                int below = Integer.parseInt(a.length > 5 ? a[5] : "100000");
                int belowSame = 0, belowTotal = 0;
                for (int y = 0; y < A.length; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    String p = A[y][x][z], q = B[y][x][z];
                    boolean eq = p.equals(q);
                    total++; if (eq) same++;
                    if (x == 0 || x == 15 || z == 0 || z == 15) { edgeTotal++; if (eq) edgeSame++; }
                    if (isTerrain(p) || isTerrain(q)) { terrainTotal++; if (eq) terrainSame++; }
                    if (y - 64 < below) { belowTotal++; if (eq) belowSame++; }
                }
                // Ground height along the chunk's border columns: what shows as a seam.
                int surfSame = 0, surfTotal = 0;
                java.util.Map<String, Integer> diffs = new java.util.TreeMap<>();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (x == 0 || x == 15 || z == 0 || z == 15) {
                        surfTotal++;
                        if (ground(A, x, z) == ground(B, x, z)) surfSame++;
                    }
                    for (int y = 0; y < A.length; y++) if (!A[y][x][z].equals(B[y][x][z])) diffs.merge(A[y][x][z].replace("minecraft:", "") + ">" + B[y][x][z].replace("minecraft:", ""), 1, Integer::sum);
                }
                // Shape: does each block agree on solid ground versus open space (air, water, plants, trees)?
                int shapeSame = 0;
                for (int y = 0; y < A.length; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (isGround(A[y][x][z]) == isGround(B[y][x][z])) shapeSame++;
                }
                int baseDiff = 0, allDiff = 0;
                for (var e : diffs.entrySet()) {
                    allDiff += e.getValue();
                    String was = e.getKey().substring(0, e.getKey().indexOf('>'));
                    if (was.equals("stone") || was.equals("deepslate")) baseDiff += e.getValue();
                }
                String top = diffs.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(Integer.getInteger("diffs", 4))
                        .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
                System.out.printf("all %.4f edge %.4f terrain %.4f below %.4f surfaceEdge %.4f baseOnly %.4f shape %.5f diffs [%s]%n", same / (double) total, edgeSame / (double) edgeTotal,
                        terrainSame / (double) Math.max(1, terrainTotal), belowSame / (double) Math.max(1, belowTotal), surfSame / (double) surfTotal, allDiff == 0 ? 1.0 : baseDiff / (double) allDiff, shapeSame / (double) total, top);
            }
            // block <dim> x y z: the block name at a world position
            case "block" -> {
                int x = i(a[2]), y = i(a[3]), z = i(a[4]);
                String[][][] B = blocks(Path.of(a[1]), x >> 4, z >> 4);
                System.out.println(B[y + 64][x & 15][z & 15]);
            }
            // remove <dim> x z: drop a chunk from region/, entities/ and poi/, as a reset does (test servers only)
            case "remove" -> {
                for (String kind : new String[]{"region", "entities", "poi"}) {
                    Path f = Path.of(a[1]).resolve(kind).resolve(RegionFile.fileName(i(a[2]), i(a[3])));
                    if (!java.nio.file.Files.exists(f)) continue;
                    RegionFile r = RegionFile.load(f);
                    r.remove(RegionFile.index(i(a[2]), i(a[3])));
                    r.writeAtomically();
                }
                System.out.println("removed");
            }
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static boolean isGround(String n) {
        String b = n.replace("minecraft:", "");
        return !(b.endsWith("air") || b.equals("water") || b.equals("lava") || b.contains("leaves") || b.contains("log")
                || b.contains("grass") && !b.equals("grass_block") || b.contains("seagrass") || b.contains("kelp") || b.contains("litter")
                || b.contains("flower") || b.contains("fern") || b.contains("bush") || b.contains("vine") || b.contains("sculk_vein"));
    }

    /** Height of the highest terrain block in a column. */
    static int ground(String[][][] B, int x, int z) {
        for (int y = B.length - 1; y >= 0; y--) if (isTerrain(B[y][x][z])) return y;
        return -1;
    }

    static boolean isTerrain(String n) {
        return n.contains("stone") || n.contains("dirt") || n.contains("grass_block") || n.contains("deepslate") || n.contains("ore")
                || n.contains("gravel") || n.contains("sand") || n.contains("granite") || n.contains("diorite") || n.contains("andesite")
                || n.contains("tuff") || n.contains("clay");
    }

    static int i(String s) {
        return Integer.parseInt(s);
    }

    /** [y - minY][x][z] for an overworld chunk, y from -64 to 319. */
    static String[][][] blocks(Path dim, int cx, int cz) throws Exception {
        RegionFile r = RegionFile.load(dim.resolve("region").resolve(RegionFile.fileName(cx, cz)));
        Nbt.Compound c = r.readNbt(RegionFile.index(cx, cz));
        if (c == null) throw new IllegalStateException("chunk " + cx + "," + cz + " not in " + dim);
        String[][][] out = new String[384][16][16];
        for (String[][] l : out) for (String[] row : l) java.util.Arrays.fill(row, "minecraft:air");
        for (Nbt.Compound s : c.getList("sections").compounds()) {
            int sy = s.getInt("Y", 0);
            if (sy < -4 || sy > 19) continue;
            Nbt.Compound st = s.getCompound("block_states");
            if (st == null) continue;
            String[] pal = ChunkFacts.palette(st);
            long[] data = st.get("data") instanceof long[] d ? d : null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
            for (int i = 0; i < 4096; i++) {
                int idx = pal.length == 1 || data == null ? 0 : ChunkFacts.paletteIndex(data, bits, i);
                out[(sy + 4) * 16 + i / 256][i % 16][(i / 16) % 16] = pal[idx];
            }
        }
        return out;
    }
}

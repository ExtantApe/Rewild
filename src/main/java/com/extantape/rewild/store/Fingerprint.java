package com.extantape.rewild.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * What decides how a world generates: game version, seed, dimension, generator, and the datapacks with a hash of
 * their files. A reset chunk only blends with its neighbours if this hasn't changed since they were generated.
 *
 * <p>The version and datapack hash can be checked offline at startup; the rest needs the running server.
 */
public record Fingerprint(String version, long seed, String environment, String generator, String packs, String packFiles) {
    public String full() {
        return "version=" + version + ";seed=" + seed + ";environment=" + environment + ";generator=" + generator
                + ";packs=" + packs + ";files=" + packFiles;
    }

    public static Fingerprint parse(String s) {
        if (s == null) return null;
        String version = null, env = null, gen = null, packs = null, files = null;
        long seed = 0;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            switch (k) {
                case "version" -> version = v;
                case "seed" -> seed = Long.parseLong(v);
                case "environment" -> env = v;
                case "generator" -> gen = v;
                case "packs" -> packs = v;
                case "files" -> files = v;
                default -> { }
            }
        }
        return new Fingerprint(version, seed, env, gen, packs, files);
    }

    /** Lists what differs from another fingerprint, in words. */
    public List<String> differences(Fingerprint other) {
        List<String> out = new ArrayList<>();
        if (!eq(version, other.version)) out.add("game version " + other.version + " -> " + version);
        if (seed != other.seed) out.add("seed changed");
        if (!eq(environment, other.environment)) out.add("dimension type changed");
        if (!eq(generator, other.generator)) out.add("world generator " + other.generator + " -> " + generator);
        if (!eq(packs, other.packs)) out.add("enabled datapacks changed");
        if (!eq(packFiles, other.packFiles)) out.add("datapack files changed");
        return out;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** SHA-256 over every file under a datapacks folder, by relative path and content. A missing folder hashes as "none". */
    public static String hashFolder(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return "none";
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        byte[] buf = new byte[1 << 16];
        for (Path f : files) {
            md.update(dir.relativize(f).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) 0);
            try (InputStream in = Files.newInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    /** Finds the level's datapacks folder by walking up from a dimension folder, staying inside the server folder. */
    public static Path findDatapacks(Path dimension, Path serverRoot) {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path p = dimension.toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p != null && p.startsWith(root); i++) {
            if (Files.isDirectory(p.resolve("datapacks"))) return p.resolve("datapacks");
            p = p.getParent();
        }
        return null;
    }
}

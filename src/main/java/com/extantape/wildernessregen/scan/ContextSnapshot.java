package com.extantape.wildernessregen.scan;

import com.extantape.wildernessregen.region.RegionFile;
import com.extantape.wildernessregen.store.ChunkRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a scan's context only the running server can see (claims, respawn points, other plugins' keeps),
 * written at shutdown so the startup step can repeat the full check with the server stopped. If the server didn't
 * shut down cleanly, the snapshot is older than the last tracking heartbeat, and nothing is applied.
 */
public final class ContextSnapshot {
    public static final String FILE = "final-check.txt";

    public long takenAt;
    /** Null if claims were read fine; otherwise why they couldn't be. */
    public String claimsProblem;
    /** Null if other plugins' keeps were read fine. */
    public String externalProblem;
    /** Null if tracking was healthy at shutdown. */
    public String trackerProblem;
    public int spawnX, spawnZ;
    public final List<int[]> claims = new ArrayList<>();
    public final List<Long> respawnChunks = new ArrayList<>();
    public final List<String[]> external = new ArrayList<>(); // x, z, reason

    public void write(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder("# Written at shutdown for the startup check. Don't edit.\n");
        sb.append("taken ").append(takenAt).append('\n');
        if (claimsProblem != null) sb.append("claimsProblem ").append(oneLine(claimsProblem)).append('\n');
        if (externalProblem != null) sb.append("externalProblem ").append(oneLine(externalProblem)).append('\n');
        if (trackerProblem != null) sb.append("trackerProblem ").append(oneLine(trackerProblem)).append('\n');
        sb.append("spawn ").append(spawnX).append(' ').append(spawnZ).append('\n');
        for (int[] c : claims) sb.append("claim ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append(' ').append(c[3]).append('\n');
        for (long r : respawnChunks) sb.append("respawn ").append(ChunkRecord.keyX(r)).append(' ').append(ChunkRecord.keyZ(r)).append('\n');
        for (String[] e : external) sb.append("external ").append(e[0]).append(' ').append(e[1]).append(' ').append(oneLine(e[2])).append('\n');
        sb.append("end\n");
        RegionFile.writeFileAtomically(dir.resolve(FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** Reads a snapshot, or returns null if there isn't a complete one. */
    public static ContextSnapshot read(Path dir) throws IOException {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) return null;
        ContextSnapshot c = new ContextSnapshot();
        boolean ended = false;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split(" ", 2);
            String rest = p.length > 1 ? p[1] : "";
            switch (p[0]) {
                case "taken" -> c.takenAt = Long.parseLong(rest);
                case "claimsProblem" -> c.claimsProblem = rest;
                case "externalProblem" -> c.externalProblem = rest;
                case "trackerProblem" -> c.trackerProblem = rest;
                case "spawn" -> {
                    String[] s = rest.split(" ");
                    c.spawnX = Integer.parseInt(s[0]);
                    c.spawnZ = Integer.parseInt(s[1]);
                }
                case "claim" -> {
                    String[] s = rest.split(" ");
                    c.claims.add(new int[]{Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3])});
                }
                case "respawn" -> {
                    String[] s = rest.split(" ");
                    c.respawnChunks.add(ChunkRecord.key(Integer.parseInt(s[0]), Integer.parseInt(s[1])));
                }
                case "external" -> {
                    String[] s = rest.split(" ", 3);
                    c.external.add(new String[]{s[0], s[1], s.length > 2 ? s[2] : "kept by another plugin"});
                }
                case "end" -> ended = true;
                default -> throw new IOException(f + " has an unknown line: " + line);
            }
        }
        return ended ? c : null;
    }

    /** Fills a scan context with this snapshot's contents. */
    public void fill(Scanner.Context ctx) {
        ctx.spawnX = spawnX;
        ctx.spawnZ = spawnZ;
        ctx.claims.addAll(claims);
        ctx.respawnChunks.addAll(respawnChunks);
        for (String[] e : external) {
            long k = ChunkRecord.key(Integer.parseInt(e[0]), Integer.parseInt(e[1]));
            ctx.external.computeIfAbsent(k, kk -> new ArrayList<>()).add(e[2]);
        }
    }
}

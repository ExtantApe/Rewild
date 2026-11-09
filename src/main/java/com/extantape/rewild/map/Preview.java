package com.extantape.rewild.map;

import com.extantape.rewild.scan.ScanService;
import com.extantape.rewild.scan.Scanner;
import com.extantape.rewild.store.ChunkRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /rewild preview: outlines nearby chunks in coloured particles, only for the admin who asked, for 30 seconds. */
public final class Preview {
    static final int SECONDS = 30;
    private final Plugin plugin;
    private final ScanService scans;
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    public Preview(Plugin plugin, ScanService scans) {
        this.plugin = plugin;
        this.scans = scans;
    }

    public static Color color(Scanner.Kind k) {
        return switch (k) {
            case QUEUED -> Color.fromRGB(230, 40, 40);
            case UNDERGROUND -> Color.fromRGB(255, 150, 0);
            case KEPT -> Color.fromRGB(40, 200, 60);
            case WAITING -> Color.fromRGB(240, 220, 40);
            case UNKNOWN -> Color.fromRGB(150, 150, 150);
            case UNDAMAGED -> null;
        };
    }

    public void show(Player p, int radius) {
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        Set<Long> chunks = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) chunks.add(ChunkRecord.key(cx + dx, cz + dz));
        scans.scan(p.getWorld(), chunks).whenComplete((r, err) -> {
            if (err != null || r.state() == null) {
                p.sendMessage(Component.text("Couldn't preview: " + (err != null ? err.getMessage() : r.problem()), NamedTextColor.RED));
                return;
            }
            BukkitTask old = running.remove(p.getUniqueId());
            if (old != null) old.cancel();
            p.sendMessage(Component.text("Preview for " + SECONDS + "s: ", NamedTextColor.GRAY)
                    .append(Component.text("red resets", NamedTextColor.RED)).append(Component.text(", "))
                    .append(Component.text("orange resets below ground", NamedTextColor.GOLD)).append(Component.text(", "))
                    .append(Component.text("green kept", NamedTextColor.GREEN)).append(Component.text(", "))
                    .append(Component.text("yellow waiting", NamedTextColor.YELLOW)).append(Component.text(", "))
                    .append(Component.text("grey unknown", NamedTextColor.GRAY)).append(Component.text(". /rewild why explains one chunk.", NamedTextColor.GRAY)));
            if (r.problem() != null) p.sendMessage(Component.text("Nothing here resets right now: " + r.problem(), NamedTextColor.RED));
            int[] ticks = {0};
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!p.isOnline() || ticks[0]++ >= SECONDS) {
                    BukkitTask t = running.remove(p.getUniqueId());
                    if (t != null) t.cancel();
                    return;
                }
                double y = p.getLocation().getY() + 1;
                for (Scanner.Verdict v : r.verdicts().values()) {
                    Color c = color(v.kind());
                    if (c == null) continue;
                    Particle.DustOptions dust = new Particle.DustOptions(c, 1.5f);
                    double x0 = v.x() * 16 + 0.5, z0 = v.z() * 16 + 0.5;
                    for (int i = 1; i < 15; i += 2) {
                        p.spawnParticle(Particle.DUST, x0 + i, y, z0 + 0.5, 1, dust);
                        p.spawnParticle(Particle.DUST, x0 + i, y, z0 + 14.5, 1, dust);
                        p.spawnParticle(Particle.DUST, x0 + 0.5, y, z0 + i, 1, dust);
                        p.spawnParticle(Particle.DUST, x0 + 14.5, y, z0 + i, 1, dust);
                    }
                }
            }, 0, 20);
            running.put(p.getUniqueId(), task);
        });
    }

    public void stopAll() {
        running.values().forEach(BukkitTask::cancel);
        running.clear();
    }
}

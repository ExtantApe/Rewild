package com.extantape.wildernessregen.cmd;

import com.extantape.wildernessregen.WildernessRegenPlugin;
import com.extantape.wildernessregen.run.Run;
import com.extantape.wildernessregen.scan.ScanService;
import com.extantape.wildernessregen.scan.Scanner;
import com.extantape.wildernessregen.store.ChunkRecord;
import com.extantape.wildernessregen.store.WorldState;
import com.extantape.wildernessregen.store.WorldStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /wr: admin-only. Players get no commands. */
public final class WrCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("status", "why", "preview", "scan", "approve", "undo", "protect", "reload");
    private final WildernessRegenPlugin plugin;

    public WrCommand(WildernessRegenPlugin plugin) {
        this.plugin = plugin;
    }

    static void say(CommandSender s, NamedTextColor c, String text) {
        s.sendMessage(Component.text(text, c));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wildernessregen.admin")) {
            say(sender, NamedTextColor.RED, "You don't have permission to use WildernessRegen.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "status" -> status(sender);
                case "why" -> why(sender, args);
                case "preview" -> preview(sender, args);
                case "scan" -> scan(sender, args);
                case "approve" -> approve(sender, args);
                case "undo" -> undo(sender, args);
                case "protect" -> protect(sender, args);
                case "reload" -> {
                    List<String> errors = plugin.reload();
                    if (errors.isEmpty()) say(sender, NamedTextColor.GREEN, "Config reloaded. New thresholds apply to the next scan.");
                    else say(sender, NamedTextColor.RED, "Config reloaded with problems, so nothing will reset: " + String.join("; ", errors));
                }
                default -> say(sender, NamedTextColor.GRAY, "/wr status | why [x z] | preview [radius] | scan [world] | approve <run> | approve generator <world> | undo <run> | protect [radius] [note] | protect remove | protect list | reload");
            }
        } catch (IOException e) {
            say(sender, NamedTextColor.RED, "That failed: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "/wr " + sub + " failed", e);
        }
        return true;
    }

    private static String date(long ms) {
        return ms == 0 ? "never" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private void status(CommandSender sender) {
        var cfg = plugin.tracking().config();
        say(sender, NamedTextColor.GOLD, "WildernessRegen " + plugin.getPluginMeta().getVersion() + (cfg.dryRun ? " (dry run: nothing resets)" : ""));
        if (!cfg.healthy()) say(sender, NamedTextColor.RED, "Config problems, so nothing resets: " + String.join("; ", cfg.errors()));
        if (plugin.tracking().states().isEmpty()) say(sender, NamedTextColor.GRAY, "No world is enabled. Add one under worlds: in config.yml.");
        for (WorldState s : plugin.tracking().states()) {
            Map<Long, ChunkRecord> recs = s.store.snapshot();
            int known = 0, unknown = 0;
            for (ChunkRecord r : recs.values()) {
                if (r.known && r.epoch == s.epoch) known++;
                else unknown++;
            }
            say(sender, s.healthy() ? NamedTextColor.GREEN : NamedTextColor.RED, s.name + ": " + known + " tracked chunks, " + unknown
                    + " unknown with changes; tracking since " + date(Long.parseLong(s.store.meta("trackingSince", "0")))
                    + (s.healthy() ? "" : ". Stopped: " + s.problem));
        }
        List<Run> runs = plugin.runs().all();
        int from = Math.max(0, runs.size() - 5);
        for (Run r : runs.subList(from, runs.size())) {
            String extra = r.get("dryRunResult") != null && r.state() == Run.State.APPROVED ? " Last dry run: " + r.get("dryRunResult") + "." : "";
            String check = r.get("lastCheck") != null && r.state() == Run.State.APPROVED ? " Last startup: " + r.get("lastCheck").replaceFirst("^\\d+ ", "") + "." : "";
            say(sender, NamedTextColor.GRAY, "Run " + r.id + " (" + r.get("worldName") + "): " + r.state().name().toLowerCase().replace('_', ' ')
                    + (r.get("note") != null ? ", " + r.get("note") : "") + "." + extra + check);
        }
    }

    private void why(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            say(sender, NamedTextColor.RED, "Run /wr why in game.");
            return;
        }
        int x = p.getLocation().getBlockX() >> 4, z = p.getLocation().getBlockZ() >> 4;
        if (args.length >= 3) {
            try {
                x = Integer.parseInt(args[1]);
                z = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                say(sender, NamedTextColor.RED, "Use chunk coordinates: /wr why <chunkX> <chunkZ>");
                return;
            }
        }
        int fx = x, fz = z;
        plugin.scans().scan(p.getWorld(), Set.of(ChunkRecord.key(x, z))).whenComplete((r, err) -> {
            if (err != null) {
                say(sender, NamedTextColor.RED, "Couldn't check: " + err.getMessage());
                return;
            }
            if (r.state() == null) {
                say(sender, NamedTextColor.GRAY, "Chunk " + fx + ", " + fz + ": " + r.problem());
                return;
            }
            Scanner.Verdict v = r.verdicts().get(ChunkRecord.key(fx, fz));
            NamedTextColor c = switch (v.kind()) {
                case QUEUED -> NamedTextColor.RED;
                case UNDERGROUND -> NamedTextColor.GOLD;
                case KEPT -> NamedTextColor.GREEN;
                case WAITING -> NamedTextColor.YELLOW;
                default -> NamedTextColor.GRAY;
            };
            String what = switch (v.kind()) {
                case QUEUED -> "would reset";
                case UNDERGROUND -> "would reset below y=" + v.cutY();
                case KEPT -> "kept";
                case WAITING -> "waiting";
                case UNKNOWN -> "unknown, never reset automatically";
                case UNDAMAGED -> "not damaged enough to reset";
            };
            say(sender, c, "Chunk " + fx + ", " + fz + ": " + what);
            for (String reason : v.reasons()) say(sender, NamedTextColor.GRAY, " - " + reason);
            if (r.problem() != null) say(sender, NamedTextColor.RED, " Nothing in this world resets right now: " + r.problem());
        });
    }

    private void preview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            say(sender, NamedTextColor.RED, "Run /wr preview in game.");
            return;
        }
        int radius = 4;
        if (args.length >= 2) {
            try {
                radius = Math.max(1, Math.min(8, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                say(sender, NamedTextColor.RED, "Radius is a number of chunks, 1 to 8.");
                return;
            }
        }
        plugin.preview().show(p, radius);
    }

    private void scan(CommandSender sender, String[] args) {
        World w = args.length >= 2 ? Bukkit.getWorld(args[1]) : sender instanceof Player p ? p.getWorld() : null;
        if (w == null) {
            say(sender, NamedTextColor.RED, args.length >= 2 ? "No loaded world called " + args[1] + "." : "Name a world: /wr scan <world>");
            return;
        }
        say(sender, NamedTextColor.GRAY, "Scanning " + w.getName() + "...");
        plugin.scans().scan(w, null).whenComplete((r, err) -> {
            if (err != null) {
                say(sender, NamedTextColor.RED, "Scan failed: " + err.getMessage());
                return;
            }
            if (r.state() == null) {
                say(sender, NamedTextColor.RED, r.problem());
                return;
            }
            plugin.mapLayer().update(w, r);
            int[] counts = new int[Scanner.Kind.values().length];
            for (Scanner.Verdict v : r.verdicts().values()) counts[v.kind().ordinal()]++;
            say(sender, NamedTextColor.GOLD, "Scanned " + w.getName() + " in " + r.elapsedMs() + " ms: " + counts[Scanner.Kind.QUEUED.ordinal()]
                    + " to reset, " + counts[Scanner.Kind.UNDERGROUND.ordinal()] + " to reset below ground, "
                    + counts[Scanner.Kind.KEPT.ordinal()] + " damaged but kept, " + counts[Scanner.Kind.WAITING.ordinal()] + " waiting.");
            if (r.problem() != null) {
                say(sender, NamedTextColor.RED, "Nothing can be approved in this world right now: " + r.problem());
                return;
            }
            try {
                Run run = plugin.runs().propose(w, r);
                if (run == null) say(sender, NamedTextColor.GRAY, "Nothing to reset. Use /wr why where you expected a reset.");
                else say(sender, NamedTextColor.GOLD, "Proposed run " + run.id + ". Check it with /wr preview, then /wr approve " + run.id + ".");
            } catch (IOException e) {
                say(sender, NamedTextColor.RED, "Couldn't save the run: " + e.getMessage());
            }
        });
    }

    private void approve(CommandSender sender, String[] args) throws IOException {
        if (args.length >= 3 && args[1].equalsIgnoreCase("generator")) {
            World w = Bukkit.getWorld(args[2]);
            WorldState s = plugin.tracking().state(w);
            if (s == null) {
                say(sender, NamedTextColor.RED, "No enabled world called " + args[2] + ".");
                return;
            }
            if (s.store.meta("generatorChanged") == null) {
                say(sender, NamedTextColor.GRAY, "The generator hasn't changed in " + s.name + ".");
                return;
            }
            plugin.tracking().acceptGenerator(s);
            say(sender, NamedTextColor.GREEN, "Accepted the new generator for " + s.name + ". Chunks generated before the change stay unknown and won't reset.");
            return;
        }
        if (args.length < 2) {
            say(sender, NamedTextColor.RED, "Usage: /wr approve <run>");
            return;
        }
        Run run = plugin.runs().find(args[1]);
        if (run == null) {
            say(sender, NamedTextColor.RED, "No run called " + args[1] + ".");
            return;
        }
        boolean refs = run.getLong("underground") > 0;
        if (refs) say(sender, NamedTextColor.GRAY, "Generating fresh terrain for the below-ground repairs...");
        plugin.runs().approve(run, sender.getName()).whenComplete((msg, err) -> {
            if (err != null) {
                Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                say(sender, NamedTextColor.RED, "Not approved: " + cause.getMessage());
                return;
            }
            say(sender, NamedTextColor.GREEN, msg);
            plugin.getLogger().info(sender.getName() + " approved run " + run.id);
            if (plugin.tracking().config().warnPlayers) {
                World w = Bukkit.getWorld(java.util.UUID.fromString(run.get("world")));
                if (w != null) {
                    Component warn = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(plugin.tracking().config().warnMessage);
                    for (Player p : w.getPlayers()) p.sendMessage(warn);
                }
            }
        });
    }

    private void undo(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) {
            say(sender, NamedTextColor.RED, "Usage: /wr undo <run>");
            return;
        }
        Run run = plugin.runs().find(args[1]);
        if (run == null) {
            say(sender, NamedTextColor.RED, "No run called " + args[1] + ".");
            return;
        }
        say(sender, NamedTextColor.GOLD, plugin.runs().undo(run));
        plugin.getLogger().info(sender.getName() + " ran /wr undo " + run.id + "; it is now " + run.state());
    }

    private void protect(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player p)) {
            say(sender, NamedTextColor.RED, "Run /wr protect in game.");
            return;
        }
        WorldState s = plugin.tracking().state(p.getWorld());
        if (s == null) {
            say(sender, NamedTextColor.RED, "This world isn't enabled, so nothing here resets anyway.");
            return;
        }
        int x = p.getLocation().getBlockX() >> 4, z = p.getLocation().getBlockZ() >> 4;
        if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
            int n = s.store.removeProtectedAt(x, z);
            say(sender, NamedTextColor.GOLD, n == 0 ? "No protected area covers this chunk." : "Removed " + n + " protected area(s) covering this chunk.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            List<WorldStore.ProtectedArea> areas = s.store.protectedAreas();
            if (areas.isEmpty()) say(sender, NamedTextColor.GRAY, "No protected areas in " + s.name + ".");
            for (WorldStore.ProtectedArea a : areas) {
                say(sender, NamedTextColor.GRAY, "Chunks " + a.minX() + ", " + a.minZ() + " to " + a.maxX() + ", " + a.maxZ()
                        + " (blocks " + a.minX() * 16 + ", " + a.minZ() * 16 + ")" + (a.note().isBlank() ? "" : ": " + a.note()));
            }
            return;
        }
        int radius = 0;
        int noteFrom = 1;
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
                noteFrom = 2;
            } catch (NumberFormatException ignored) {
            }
        }
        if (radius < 0 || radius > 64) {
            say(sender, NamedTextColor.RED, "Radius is 0 to 64 chunks.");
            return;
        }
        List<String> note = new ArrayList<>();
        for (int i = noteFrom; i < args.length; i++) note.add(args[i]);
        s.store.addProtected(new WorldStore.ProtectedArea(x - radius, z - radius, x + radius, z + radius,
                (String.join(" ", note) + " (" + sender.getName() + ", " + date(System.currentTimeMillis()) + ")").trim()));
        say(sender, NamedTextColor.GREEN, "Protected " + (2 * radius + 1) * (2 * radius + 1) + " chunk(s) around you. They'll never reset.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("wildernessregen.admin")) return List.of();
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("approve") || sub.equals("undo"))) {
            List<String> out = new ArrayList<>();
            if (sub.equals("approve")) out.add("generator");
            for (Run r : plugin.runs().all()) {
                Run.State st = r.state();
                if (sub.equals("approve") ? st == Run.State.PROPOSED : st == Run.State.APPLIED || st == Run.State.APPROVED
                        || st == Run.State.PROPOSED || st == Run.State.UNDO_REQUESTED) out.add(r.id);
            }
            return out.stream().filter(s -> s.startsWith(args[1])).toList();
        }
        if (args.length == 2 && sub.equals("scan")) return Bukkit.getWorlds().stream().map(World::getName).filter(n -> n.startsWith(args[1])).toList();
        if (args.length == 3 && sub.equals("approve") && args[1].equalsIgnoreCase("generator")) {
            return plugin.tracking().states().stream().map(s -> s.name).filter(n -> n.startsWith(args[2])).toList();
        }
        if (args.length == 2 && sub.equals("protect")) return List.of("remove", "list", "0", "1", "2");
        return List.of();
    }
}

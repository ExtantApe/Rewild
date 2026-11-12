package com.extantape.rewild.cmd;

import com.extantape.rewild.RewildPlugin;
import com.extantape.rewild.run.Run;
import com.extantape.rewild.scan.ScanService;
import com.extantape.rewild.scan.Scanner;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.WorldState;
import com.extantape.rewild.store.WorldStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /rewild: admin-only, registered through Paper's command API. Players get no commands. */
public final class RewildCommand implements BasicCommand {
    public static final String PERMISSION = "rewild.admin";
    private static final List<String> SUBS = List.of("status", "why", "preview", "scan", "approve", "undo", "protect", "keep", "unkeep", "plugins", "reload");
    private final RewildPlugin plugin;

    public RewildCommand(RewildPlugin plugin) {
        this.plugin = plugin;
    }

    static void say(CommandSender s, NamedTextColor c, String text) {
        s.sendMessage(Component.text(text, c));
    }

    /** A clickable [label] that puts a command in the chat box, ready to check and send. */
    static Component button(String label, String command, String hover) {
        return Component.text("[" + label + "]", NamedTextColor.AQUA)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(hover + "\n" + command, NamedTextColor.GRAY)));
    }

    /** True for admins. */
    public boolean allowed(org.bukkit.permissions.Permissible who) {
        return who.hasPermission(PERMISSION);
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return allowed(sender);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(source.getSender(), args);
    }

    void run(CommandSender sender, String[] args) {
        if (!allowed(sender)) {
            say(sender, NamedTextColor.RED, "You don't have permission to use Rewild.");
            return;
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
                case "keep" -> keep(sender, args);
                case "unkeep" -> unkeep(sender, args);
                case "plugins" -> plugins(sender, args);
                case "reload" -> {
                    List<String> errors = plugin.reload();
                    if (errors.isEmpty()) say(sender, NamedTextColor.GREEN, "Config reloaded. New thresholds apply to the next scan.");
                    else say(sender, NamedTextColor.RED, "Config reloaded with problems, so nothing will reset: " + String.join("; ", errors));
                }
                default -> say(sender, NamedTextColor.GRAY, "/rewild status | why [world] [x z] | preview [radius] | scan [world] | approve <run> | approve generator <world> | undo <run> | protect [radius] [note] | protect remove | protect list | keep <world> <x y z> [<x y z>] [plugin:<Name>] [note] | keep list [world] [--json] | unkeep <id> | plugins [respect|ignore <Name>...] [ignore-rest] [--json] | reload");
            }
        } catch (IOException e) {
            say(sender, NamedTextColor.RED, "That failed: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "/rewild " + sub + " failed", e);
        }
    }

    private static String date(long ms) {
        return ms == 0 ? "never" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private void status(CommandSender sender) {
        var cfg = plugin.tracking().config();
        say(sender, NamedTextColor.GOLD, "Rewild " + plugin.getPluginMeta().getVersion() + (cfg.dryRun ? " (dry run: nothing resets)" : ""));
        if (!cfg.healthy()) say(sender, NamedTextColor.RED, "Config problems, so nothing resets: " + String.join("; ", cfg.errors()));
        String plugins = plugin.pluginsProblem();
        if (plugins != null) sender.sendMessage(Component.text("Nothing resets yet: " + plugins + " ", NamedTextColor.YELLOW)
                .append(button("Answer", "/rewild plugins", "List installed plugins and choose which to respect")));
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
            Component line = Component.text("Run " + r.id + " (" + r.get("worldName") + "): " + r.state().name().toLowerCase().replace('_', ' ')
                    + (r.get("note") != null ? ", " + r.get("note") : "") + "." + extra + check, NamedTextColor.GRAY);
            switch (r.state()) {
                case PROPOSED -> line = line.append(Component.text(" ")).append(button("Approve", "/rewild approve " + r.id, "Apply this run at the next restart"));
                case APPROVED -> line = line.append(Component.text(" ")).append(button("Cancel", "/rewild undo " + r.id, "Cancel before it's applied"));
                case APPLIED -> line = line.append(Component.text(" ")).append(button("Undo", "/rewild undo " + r.id, "Put these chunks back at the next restart"));
                default -> { }
            }
            sender.sendMessage(line);
        }
    }

    private void why(CommandSender sender, String[] args) {
        World world;
        int x, z;
        int at = 1;
        if (args.length >= 4) {
            world = Bukkit.getWorld(args[1]);
            at = 2;
        } else {
            world = sender instanceof Player p ? p.getWorld() : null;
        }
        if (world == null) {
            say(sender, NamedTextColor.RED, "Usage: /rewild why [world] [chunkX chunkZ]");
            return;
        }
        if (args.length >= at + 2) {
            try {
                x = Integer.parseInt(args[at]);
                z = Integer.parseInt(args[at + 1]);
            } catch (NumberFormatException e) {
                say(sender, NamedTextColor.RED, "Use chunk coordinates: /rewild why [world] <chunkX> <chunkZ>");
                return;
            }
        } else if (sender instanceof Player p) {
            x = p.getLocation().getBlockX() >> 4;
            z = p.getLocation().getBlockZ() >> 4;
        } else {
            say(sender, NamedTextColor.RED, "Usage: /rewild why <world> <chunkX> <chunkZ>");
            return;
        }
        int fx = x, fz = z;
        plugin.scans().scan(world, Set.of(ChunkRecord.key(x, z))).whenComplete((r, err) -> {
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
                case QUEUED -> "would rewild; kept blocks stay as they are";
                case UNDERGROUND -> "would rewild below y=" + v.cutY() + " only";
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
            say(sender, NamedTextColor.RED, "Run /rewild preview in game.");
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
            say(sender, NamedTextColor.RED, args.length >= 2 ? "No loaded world called " + args[1] + "." : "Name a world: /rewild scan <world>");
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
                    + " to rewild, " + counts[Scanner.Kind.UNDERGROUND.ordinal()] + " to rewild below ground, "
                    + counts[Scanner.Kind.KEPT.ordinal()] + " damaged but kept, " + counts[Scanner.Kind.WAITING.ordinal()] + " waiting.");
            if (r.problem() != null) {
                say(sender, NamedTextColor.RED, "Nothing can be approved in this world right now: " + r.problem());
                return;
            }
            try {
                Run run = plugin.runs().propose(w, r);
                if (run == null) {
                    say(sender, NamedTextColor.GRAY, "Nothing to reset. Use /rewild why where you expected a reset.");
                } else {
                    say(sender, NamedTextColor.GOLD, "Proposed run " + run.id + ". Check it with /rewild preview, then /rewild approve " + run.id + ".");
                    sender.sendMessage(Component.text("  ")
                            .append(button("Preview", "/rewild preview 6", "Outline the chunks around you"))
                            .append(Component.text(" "))
                            .append(button("Why here", "/rewild why", "Explain the chunk you're standing in"))
                            .append(Component.text(" "))
                            .append(button("Approve " + run.id, "/rewild approve " + run.id, "Apply this run at the next restart")));
                }
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
            say(sender, NamedTextColor.RED, "Usage: /rewild approve <run>");
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
            say(sender, NamedTextColor.RED, "Usage: /rewild undo <run>");
            return;
        }
        Run run = plugin.runs().find(args[1]);
        if (run == null) {
            say(sender, NamedTextColor.RED, "No run called " + args[1] + ".");
            return;
        }
        say(sender, NamedTextColor.GOLD, plugin.runs().undo(run));
        plugin.getLogger().info(sender.getName() + " ran /rewild undo " + run.id + "; it is now " + run.state());
    }

    // ---- keep, unkeep, plugins ----

    /** "plugin:Name" in the arguments, or the admin owner. Removes it from the list. */
    private static String owner(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).toLowerCase(Locale.ROOT).startsWith("plugin:")) {
                String name = words.remove(i).substring(7);
                return name.isBlank() ? null : name;
            }
        }
        return com.extantape.rewild.keep.CommandKeeps.ADMIN;
    }

    /** Parses "world x y z [x y z]" from the front of the list, removing what it used. Null if it doesn't fit. */
    private static com.extantape.rewild.keep.KeepPlan.Area box(List<String> w, String note) {
        if (w.size() < 4 || Bukkit.getWorld(w.get(0)) == null) return null;
        int[] n = new int[6];
        int count = 0;
        for (int i = 1; i < Math.min(w.size(), 7); i++) {
            try {
                n[i - 1] = Integer.parseInt(w.get(i));
                count++;
            } catch (NumberFormatException e) {
                break;
            }
        }
        if (count != 3 && count != 6) return null;
        String world = w.get(0);
        for (int i = 0; i <= count; i++) w.remove(0);
        if (count == 3) return new com.extantape.rewild.keep.KeepPlan.Area(world, n[0], n[1], n[2], n[0], n[1], n[2], note);
        return new com.extantape.rewild.keep.KeepPlan.Area(world, n[0], n[1], n[2], n[3], n[4], n[5], note);
    }

    private void keep(CommandSender sender, String[] args) throws IOException {
        var keeps = plugin.tracking().commandKeeps();
        List<String> words = new ArrayList<>(List.of(args).subList(1, args.length));
        boolean json = words.remove("--json");
        if (!words.isEmpty() && words.get(0).equalsIgnoreCase("list")) {
            String world = words.size() > 1 ? words.get(1) : null;
            List<com.extantape.rewild.keep.CommandKeeps.Entry> list = keeps.all().stream()
                    .filter(e -> world == null || e.area().world().equals(world)).toList();
            if (json) {
                StringBuilder sb = new StringBuilder("[");
                for (var e : list) {
                    var a = e.area();
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"id\":").append(e.id()).append(",\"owner\":").append(Json.str(e.owner()))
                            .append(",\"counts\":").append(e.byAdmin() || plugin.tracking().respect().respected(e.owner()))
                            .append(",\"world\":").append(Json.str(a.world())).append(",\"min\":[").append(a.minX()).append(',')
                            .append(a.minY()).append(',').append(a.minZ()).append("],\"max\":[").append(a.maxX()).append(',')
                            .append(a.maxY()).append(',').append(a.maxZ()).append("],\"note\":").append(Json.str(a.reason())).append('}');
                }
                sender.sendMessage(Component.text(sb.append(']').toString()));
                return;
            }
            if (list.isEmpty()) say(sender, NamedTextColor.GRAY, "Nothing is kept with /rewild keep" + (world == null ? "" : " in " + world) + ".");
            for (var e : list) {
                var a = e.area();
                boolean counts = e.byAdmin() || plugin.tracking().respect().respected(e.owner());
                say(sender, counts ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY, "#" + e.id() + " " + a.world() + " " + a.minX() + " "
                        + a.minY() + " " + a.minZ() + " to " + a.maxX() + " " + a.maxY() + " " + a.maxZ() + ", by " + e.owner()
                        + (a.reason().isBlank() ? "" : ": " + a.reason()) + (counts ? "" : " (not counted: " + e.owner() + " isn't respected)"));
            }
            return;
        }
        String owner = owner(words);
        if (owner == null) {
            say(sender, NamedTextColor.RED, "plugin: needs a plugin name, such as plugin:Homesteader.");
            return;
        }
        List<String> rest = new ArrayList<>(words);
        com.extantape.rewild.keep.KeepPlan.Area a = box(rest, "");
        if (a == null) {
            say(sender, NamedTextColor.RED, "Usage: /rewild keep <world> <x> <y> <z> [<x2> <y2> <z2>] [plugin:<Name>] [note]. "
                    + "The blocks stay exactly as they are when the land around them rewilds.");
            return;
        }
        a = new com.extantape.rewild.keep.KeepPlan.Area(a.world(), a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ(), String.join(" ", rest));
        var e = keeps.add(owner, a);
        boolean counts = e.byAdmin() || plugin.tracking().respect().respected(owner);
        say(sender, counts ? NamedTextColor.GREEN : NamedTextColor.YELLOW, "Kept #" + e.id() + ": " + a.volume() + " block(s) in " + a.world()
                + (counts ? ". They stay as they are when the land around them rewilds."
                : ". Saved, but it doesn't count until " + owner + " is respected (/rewild plugins)."));
        plugin.getLogger().info(sender.getName() + " kept #" + e.id() + " for " + owner + " in " + a.world());
    }

    private void unkeep(CommandSender sender, String[] args) throws IOException {
        var keeps = plugin.tracking().commandKeeps();
        List<String> words = new ArrayList<>(List.of(args).subList(1, args.length));
        if (words.size() == 1) {
            try {
                var e = keeps.remove(Integer.parseInt(words.get(0).replace("#", "")));
                say(sender, e == null ? NamedTextColor.RED : NamedTextColor.GOLD, e == null ? "No keep #" + words.get(0) + "." : "Removed keep #" + e.id() + ".");
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        String owner = owner(words);
        com.extantape.rewild.keep.KeepPlan.Area a = owner == null ? null : box(words, "");
        if (a == null) {
            say(sender, NamedTextColor.RED, "Usage: /rewild unkeep <id>, or /rewild unkeep <world> <x> <y> <z> [<x2> <y2> <z2>] [plugin:<Name>]");
            return;
        }
        int n = keeps.removeExact(owner, a.world(), a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ());
        say(sender, NamedTextColor.GOLD, n == 0 ? "No keep by " + owner + " has exactly those corners." : "Removed " + n + " keep(s) by " + owner + ".");
    }

    private void plugins(CommandSender sender, String[] args) throws IOException {
        var respect = plugin.tracking().respect();
        List<String> words = new ArrayList<>(List.of(args).subList(1, args.length));
        boolean json = words.remove("--json");
        List<String> installed = new ArrayList<>();
        for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) if (!p.getName().equals(plugin.getName())) installed.add(p.getName());
        installed.sort(String.CASE_INSENSITIVE_ORDER);
        if (!words.isEmpty()) {
            String verb = words.remove(0).toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            if (verb.equals("ignore-rest")) {
                names.addAll(respect.unanswered(installed));
                verb = "ignore";
            } else {
                for (String w : words) {
                    String match = installed.stream().filter(n -> n.equalsIgnoreCase(w)).findFirst().orElse(null);
                    if (match == null) {
                        say(sender, NamedTextColor.RED, w + " isn't installed. /rewild plugins lists what is.");
                        return;
                    }
                    names.add(match);
                }
            }
            if (!verb.equals("respect") && !verb.equals("ignore") || names.isEmpty()) {
                say(sender, NamedTextColor.RED, "Usage: /rewild plugins respect <Name>..., /rewild plugins ignore <Name>..., or /rewild plugins ignore-rest");
                return;
            }
            for (String n : names) respect.setRespected(n, verb.equals("respect"));
            respect.save();
            say(sender, NamedTextColor.GREEN, (verb.equals("respect") ? "Respected: " : "Ignored: ") + String.join(", ", names) + ".");
            plugin.getLogger().info(sender.getName() + " set " + String.join(", ", names) + " to " + verb);
        }
        java.util.Set<String> providers = new java.util.HashSet<>();
        for (var reg : Bukkit.getServicesManager().getRegistrations(com.extantape.rewild.api.KeepProvider.class)) providers.add(reg.getPlugin().getName());
        if (json) {
            StringBuilder sb = new StringBuilder("[");
            for (String n : installed) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"plugin\":").append(Json.str(n)).append(",\"answer\":").append(Json.str(respect.respected(n) ? "respect"
                        : respect.ignored(n) ? "ignore" : "none")).append(",\"reportsKeeps\":").append(providers.contains(n)).append('}');
            }
            sender.sendMessage(Component.text(sb.append(']').toString()));
            return;
        }
        List<String> open = respect.unanswered(installed);
        say(sender, NamedTextColor.GOLD, "Which plugins does Rewild respect? Everything a respected plugin reports stays when the land around it rewilds."
                + (open.isEmpty() ? "" : " Nothing resets until every plugin has an answer."));
        for (String n : installed) {
            String state = respect.respected(n) ? "respected" : respect.ignored(n) ? "ignored" : "no answer yet";
            NamedTextColor c = respect.respected(n) ? NamedTextColor.GREEN : respect.ignored(n) ? NamedTextColor.GRAY : NamedTextColor.YELLOW;
            Component line = Component.text(n + ": " + state + (providers.contains(n) ? ", reports what it keeps" : ""), c);
            if (!respect.respected(n)) line = line.append(Component.text(" ")).append(button("Respect", "/rewild plugins respect " + n, "Keep everything " + n + " reports"));
            if (!respect.ignored(n)) line = line.append(Component.text(" ")).append(button("Ignore", "/rewild plugins ignore " + n, "Don't listen to " + n));
            sender.sendMessage(line);
        }
        if (!open.isEmpty()) sender.sendMessage(Component.text("Ignore the " + open.size() + " without an answer: ", NamedTextColor.GRAY)
                .append(button("Ignore the rest", "/rewild plugins ignore-rest", "Answer ignore for every plugin without an answer")));
    }

    private void protect(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player p)) {
            say(sender, NamedTextColor.RED, "Run /rewild protect in game.");
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
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERMISSION)) return List.of();
        // Paper passes an empty last argument while a new word is being typed.
        if (args.length == 0) return SUBS;
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
        if (args.length == 2 && sub.equals("plugins")) return List.of("respect", "ignore", "ignore-rest", "--json").stream().filter(s -> s.startsWith(args[1])).toList();
        if (args.length == 3 && sub.equals("plugins") && (args[1].equalsIgnoreCase("respect") || args[1].equalsIgnoreCase("ignore"))) {
            return java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(org.bukkit.plugin.Plugin::getName)
                    .filter(n -> !n.equals(plugin.getName()) && n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && sub.equals("keep")) {
            List<String> out = new ArrayList<>(List.of("list"));
            Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
            return out.stream().filter(s -> s.startsWith(args[1])).toList();
        }
        return List.of();
    }
}

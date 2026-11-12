package com.extantape.rewild.keep;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which installed plugins Rewild respects: everything a respected plugin reports is kept. An admin answers once per
 * plugin with /rewild plugins, and the answers live in keep-plugins.yml. Until every installed plugin has an answer,
 * nothing resets, so a plugin installed later can't have its builds rewilded before an admin has looked at it.
 */
public final class Respect {
    public static final String FILE = "keep-plugins.yml";

    private final File file;
    private final Set<String> respect = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> ignore = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private String problem;

    public Respect(File dataFolder) {
        this.file = new File(dataFolder, FILE);
        load();
    }

    public void load() {
        respect.clear();
        ignore.clear();
        problem = null;
        if (!file.exists()) return;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (Exception e) {
            problem = FILE + " can't be read (" + e.getMessage() + ")";
            return;
        }
        respect.addAll(y.getStringList("keep-plugins"));
        ignore.addAll(y.getStringList("ignore-plugins"));
        Set<String> both = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        both.addAll(respect);
        both.retainAll(ignore);
        if (!both.isEmpty()) problem = FILE + " lists " + String.join(", ", both) + " as both kept and ignored";
    }

    public void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Which plugins Rewild respects. Everything a respected plugin reports (through Rewild's KeepProvider, or",
                "/rewild keep plugin:<Name>) stays in place when the land around it rewilds. Set with /rewild plugins.",
                "Nothing resets while an installed plugin is in neither list."));
        y.set("keep-plugins", new ArrayList<>(respect));
        y.set("ignore-plugins", new ArrayList<>(ignore));
        File tmp = new File(file.getParentFile(), FILE + ".tmp");
        y.save(tmp);
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Null if the file is fine; otherwise why nothing can reset. */
    public String problem() {
        return problem;
    }

    public boolean respected(String plugin) {
        return respect.contains(plugin);
    }

    public boolean ignored(String plugin) {
        return ignore.contains(plugin);
    }

    public Set<String> respectedNames() {
        return Set.copyOf(respect);
    }

    public void setRespected(String plugin, boolean yes) {
        if (yes) {
            ignore.remove(plugin);
            respect.add(plugin);
        } else {
            respect.remove(plugin);
            ignore.add(plugin);
        }
    }

    /** Installed plugins with no answer yet, sorted. Rewild itself never needs one. */
    public List<String> unanswered(Collection<String> installed) {
        List<String> out = new ArrayList<>();
        for (String p : new TreeSet<>(installed)) {
            if (p.equalsIgnoreCase("Rewild")) continue;
            if (!respect.contains(p) && !ignore.contains(p)) out.add(p);
        }
        return out;
    }

    /** Why nothing can reset because of the answers, or null. */
    public String blocking(Collection<String> installed, java.util.function.Predicate<String> running) {
        if (problem != null) return problem;
        List<String> open = unanswered(installed);
        if (!open.isEmpty()) {
            return "nothing resets until an admin says which plugins to respect: " + String.join(", ", open)
                    + " (/rewild plugins)";
        }
        for (String p : respect) {
            if (!running.test(p)) return p + " is respected but isn't running, so what it keeps can't be read";
        }
        return null;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

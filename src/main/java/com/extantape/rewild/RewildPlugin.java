package com.extantape.rewild;

import com.extantape.rewild.cmd.RewildCommand;
import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.map.BlueMapLayer;
import com.extantape.rewild.map.MapLayer;
import com.extantape.rewild.map.Preview;
import com.extantape.rewild.run.Applier;
import com.extantape.rewild.run.RunService;
import com.extantape.rewild.scan.IntentRules;
import com.extantape.rewild.scan.ScanService;
import com.extantape.rewild.store.WorldStore;
import com.extantape.rewild.track.TrackerListener;
import com.extantape.rewild.track.Tracking;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class RewildPlugin extends JavaPlugin {
    private RegenConfig config;
    private Map<UUID, WorldStore> startupStores = new HashMap<>();
    private Tracking tracking;
    private TrackerListener listener;
    private ScanService scans;
    private RunService runs;
    private Preview preview;
    private MapLayer mapLayer = MapLayer.NONE;
    /** Set when the move from plugins/WildernessRegen failed. Then nothing runs at all, so no data is stranded. */
    private String migrationProblem;

    /** Runs before any world loads: apply or undo approved runs while no chunk is open. */
    @Override
    public void onLoad() {
        Migration.Result moved = Migration.moveIfMissing(getDataFolder().toPath().resolveSibling("WildernessRegen"), getDataFolder().toPath());
        if (moved.failed()) {
            migrationProblem = moved.message();
            getLogger().severe(moved.message());
            return;
        }
        if (moved.message() != null) getLogger().warning(moved.message());
        saveDefaultConfig();
        reloadConfig();
        config = new RegenConfig(getConfig());
        for (String e : config.errors()) getLogger().severe("config.yml: " + e + ". Nothing will reset until it's fixed.");
        Applier applier = new Applier(getDataFolder().toPath(), getLogger(), config, Bukkit.getMinecraftVersion());
        String crashAt = System.getProperty("rewild.test.crashAt");
        if (crashAt != null) {
            // Test servers only: stop the process dead at a named step, like kill -9, to prove recovery.
            applier.setCrashHook(marker -> {
                if (marker.equals(crashAt)) {
                    getLogger().severe("TEST: halting before " + marker);
                    Runtime.getRuntime().halt(137);
                }
                return false;
            });
        }
        try {
            applier.runStartup();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "The startup step failed; world files are as the journal last recorded", e);
        }
        startupStores = applier.stores();
        if (Boolean.getBoolean("rewild.test.haltAfterStartup")) {
            // Test servers only: stop right after the startup step, before the server writes anything, so a test can
            // compare world files and see exactly what this step changed.
            for (WorldStore s : startupStores.values()) {
                try {
                    s.close();
                } catch (java.io.IOException ignored) {
                }
            }
            getLogger().severe("TEST: halting after the startup step");
            Runtime.getRuntime().halt(0);
        }
    }

    @Override
    public void onEnable() {
        if (migrationProblem != null) {
            getLogger().severe("Rewild is turning itself off, so nothing resets until the folder is moved. " + migrationProblem);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        warnUnknownMaterials();
        tracking = new Tracking(this, config);
        tracking.start(startupStores);
        startupStores = new HashMap<>();
        listener = new TrackerListener(this, tracking, IntentRules.from(config));
        getServer().getPluginManager().registerEvents(listener, this);
        scans = new ScanService(this, tracking);
        runs = new RunService(this, tracking);
        preview = new Preview(this, scans);
        if (config.blueMap && Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
            try {
                mapLayer = new BlueMapLayer(getLogger());
            } catch (LinkageError e) {
                getLogger().warning("BlueMap is installed but its API couldn't be loaded; no map layer: " + e);
            }
        }
        RewildCommand wr = new RewildCommand(this);
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> {
                    event.registrar().register("rewild", "Rewild admin tools", wr);
                    // The old names, for one release (0.2.x) after the rename from WildernessRegen.
                    event.registrar().register("wr", "Old name for /rewild. Goes in the next release.", List.of("wildernessregen"), wr.deprecated());
                });
        if (config.dryRun) getLogger().info("Dry run is on: approved runs are checked at startup and nothing resets.");
        if (tracking.states().isEmpty()) getLogger().info("No world is enabled yet. Add one under worlds: in config.yml.");
    }

    @Override
    public void onDisable() {
        if (preview != null) preview.stopAll();
        mapLayer.close();
        if (tracking == null) return;
        var snaps = runs.shutdownSnapshots();
        tracking.stop();
        runs.writeSnapshots(snaps);
    }

    /** Reloads config.yml. Returns its problems; any problem stops resets. */
    public List<String> reload() {
        reloadConfig();
        config = new RegenConfig(getConfig());
        tracking.setConfig(config);
        listener.setIntent(IntentRules.from(config));
        for (var w : Bukkit.getWorlds()) tracking.open(w, null);
        warnUnknownMaterials();
        return config.errors();
    }

    private void warnUnknownMaterials() {
        java.util.List<String> patterns = new java.util.ArrayList<>(config.intentKeep);
        patterns.addAll(config.intentScore.keySet());
        for (String p : patterns) {
            boolean any = false;
            for (Material m : Material.values()) if (!m.isLegacy() && RegenConfig.matches(p, m.name())) any = true;
            if (!any && !p.equals("ITEM_FRAME") && !p.equals("GLOW_ITEM_FRAME") && !p.equals("ARMOR_STAND")) {
                getLogger().info("intent pattern " + p + " matches no block in this version; it's ignored here.");
            }
        }
    }

    public Tracking tracking() {
        return tracking;
    }

    public ScanService scans() {
        return scans;
    }

    public RunService runs() {
        return runs;
    }

    public Preview preview() {
        return preview;
    }

    public MapLayer mapLayer() {
        return mapLayer;
    }
}

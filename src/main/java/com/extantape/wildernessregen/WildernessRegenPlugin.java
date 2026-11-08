package com.extantape.wildernessregen;

import com.extantape.wildernessregen.cmd.WrCommand;
import com.extantape.wildernessregen.config.RegenConfig;
import com.extantape.wildernessregen.map.BlueMapLayer;
import com.extantape.wildernessregen.map.MapLayer;
import com.extantape.wildernessregen.map.Preview;
import com.extantape.wildernessregen.run.Applier;
import com.extantape.wildernessregen.run.RunService;
import com.extantape.wildernessregen.scan.IntentRules;
import com.extantape.wildernessregen.scan.ScanService;
import com.extantape.wildernessregen.store.WorldStore;
import com.extantape.wildernessregen.track.TrackerListener;
import com.extantape.wildernessregen.track.Tracking;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class WildernessRegenPlugin extends JavaPlugin {
    private RegenConfig config;
    private Map<UUID, WorldStore> startupStores = new HashMap<>();
    private Tracking tracking;
    private TrackerListener listener;
    private ScanService scans;
    private RunService runs;
    private Preview preview;
    private MapLayer mapLayer = MapLayer.NONE;

    /** Runs before any world loads: apply or undo approved runs while no chunk is open. */
    @Override
    public void onLoad() {
        saveDefaultConfig();
        reloadConfig();
        config = new RegenConfig(getConfig());
        for (String e : config.errors()) getLogger().severe("config.yml: " + e + ". Nothing will reset until it's fixed.");
        Applier applier = new Applier(getDataFolder().toPath(), getLogger(), config, Bukkit.getMinecraftVersion());
        String crashAt = System.getProperty("wildernessregen.test.crashAt");
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
    }

    @Override
    public void onEnable() {
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
        PluginCommand cmd = getCommand("wr");
        if (cmd != null) {
            WrCommand wr = new WrCommand(this);
            cmd.setExecutor(wr);
            cmd.setTabCompleter(wr);
        }
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

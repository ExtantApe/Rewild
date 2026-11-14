package com.extantape.rewild;

import com.extantape.rewild.cmd.RewildCommand;
import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.map.BlueMapLayer;
import com.extantape.rewild.map.MapLayer;
import com.extantape.rewild.map.Preview;
import com.extantape.rewild.run.Applier;
import com.extantape.rewild.run.RunService;
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
    @Override
    public void onEnable() {
        warnUnknownMaterials();
        tracking = new Tracking(this, config);
        tracking.start(startupStores);
        startupStores = new HashMap<>();
        listener = new TrackerListener(this, tracking);
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
        RewildCommand command = new RewildCommand(this);
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> {
                    event.registrar().register("rewild", "Rewild admin tools", command);
                });
        if (config.dryRun) getLogger().info("Dry run is on: approved runs are checked at startup and nothing resets.");
        if (tracking.states().isEmpty()) getLogger().info("No world is enabled yet. Add one under worlds: in config.yml.");
        // Once every plugin has started: say which still need an answer, so nothing is rewilded behind an admin's back.
        getServer().getScheduler().runTask(this, () -> {
            String p = pluginsProblem();
            if (p != null) getLogger().warning("Nothing resets yet: " + p + ". Answer with /rewild plugins respect <Name>,"
                    + " /rewild plugins ignore <Name>, or /rewild plugins ignore-rest.");
        });
    }

    /** Why the respected-plugin answers stop resets, or null. */
    public String pluginsProblem() {
        java.util.List<String> installed = new java.util.ArrayList<>();
        for (org.bukkit.plugin.Plugin p : getServer().getPluginManager().getPlugins()) installed.add(p.getName());
        return tracking.respect().blocking(installed, n -> {
            org.bukkit.plugin.Plugin p = getServer().getPluginManager().getPlugin(n);
            return p != null && p.isEnabled();
        });
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
        tracking.respect().load();
        tracking.commandKeeps().load();
        for (var w : Bukkit.getWorlds()) tracking.open(w, null);
        warnUnknownMaterials();
        return config.errors();
    }

    private void warnUnknownMaterials() {
        for (String p : config.keepBlocks) {
            boolean any = false;
            for (Material m : Material.values()) if (!m.isLegacy() && m.isBlock() && RegenConfig.matches(p, m.name())) any = true;
            if (!any) getLogger().warning("keep-blocks entry " + p + " matches no block in this version; it keeps nothing.");
        }
        for (String old : config.retired) {
            getLogger().info(old + " in config.yml does nothing since 0.3.0: every player-placed block now stays where it is"
                    + " when the land around it rewilds. It can be deleted.");
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

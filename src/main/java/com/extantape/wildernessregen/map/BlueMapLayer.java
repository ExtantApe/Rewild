package com.extantape.wildernessregen.map;

import com.extantape.wildernessregen.scan.ScanService;
import com.extantape.wildernessregen.scan.Scanner;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.World;

import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A hidden-by-default BlueMap layer showing the last scan: red resets, orange below ground, green kept, yellow
 * waiting, grey unknown. Only loaded when BlueMap is installed.
 */
public final class BlueMapLayer implements MapLayer {
    static final String SET_ID = "wildernessregen";
    private final Logger log;
    private final Consumer<BlueMapAPI> onDisable = api -> { };

    public BlueMapLayer(Logger log) {
        this.log = log;
        BlueMapAPI.onDisable(onDisable);
    }

    @Override
    public void update(World world, ScanService.Result result) {
        BlueMapAPI.getInstance().ifPresent(api -> api.getWorld(world).ifPresent(bw -> {
            MarkerSet set = MarkerSet.builder().label("WildernessRegen").toggleable(true).defaultHidden(true).build();
            int y = world.getSeaLevel();
            for (Scanner.Verdict v : result.verdicts().values()) {
                org.bukkit.Color c = Preview.color(v.kind());
                if (c == null) continue;
                ShapeMarker m = ShapeMarker.builder()
                        .label(v.x() + ", " + v.z() + ": " + v.kind().name().toLowerCase())
                        .detail(String.join("<br>", v.reasons().stream().map(BlueMapLayer::escape).toList()))
                        .shape(Shape.createRect(v.x() * 16, v.z() * 16, v.x() * 16 + 16, v.z() * 16 + 16), y)
                        .fillColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.35f))
                        .lineColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.9f))
                        .lineWidth(1)
                        .depthTestEnabled(false)
                        .build();
                set.getMarkers().put(v.x() + "_" + v.z(), m);
            }
            for (BlueMapMap map : bw.getMaps()) map.getMarkerSets().put(SET_ID, set);
        }));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        BlueMapAPI.unregisterListener(onDisable);
        BlueMapAPI.getInstance().ifPresent(api -> {
            for (var w : api.getWorlds()) for (BlueMapMap map : w.getMaps()) map.getMarkerSets().remove(SET_ID);
        });
    }
}

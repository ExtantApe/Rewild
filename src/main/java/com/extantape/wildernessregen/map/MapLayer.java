package com.extantape.wildernessregen.map;

import com.extantape.wildernessregen.scan.ScanService;
import org.bukkit.World;

/** A web map layer of scan results. Does nothing unless a map plugin is installed. */
public interface MapLayer {
    void update(World world, ScanService.Result result);

    default void close() {}

    MapLayer NONE = (w, r) -> { };
}

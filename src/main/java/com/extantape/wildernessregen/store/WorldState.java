package com.extantape.wildernessregen.store;

import java.nio.file.Path;
import java.util.UUID;

/** A tracked world's store plus what's currently known about its health. */
public final class WorldState {
    public final UUID uid;
    public final String name;
    public final WorldStore store;
    public final Path dimension;
    public final String environment;
    public final int minY;
    /** Null while healthy. Otherwise why nothing in this world may reset. */
    public volatile String problem;
    public volatile int epoch;

    public WorldState(UUID uid, String name, WorldStore store, Path dimension, String environment, int minY) {
        this.uid = uid;
        this.name = name;
        this.store = store;
        this.dimension = dimension;
        this.environment = environment;
        this.minY = minY;
    }

    public boolean healthy() {
        return problem == null;
    }
}

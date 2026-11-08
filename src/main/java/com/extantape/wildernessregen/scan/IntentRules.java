package com.extantape.wildernessregen.scan;

import com.extantape.wildernessregen.config.RegenConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Classifies player-placed materials by name, using the intent lists in the config. */
public final class IntentRules {
    public static final String PATH = "DIRT_PATH";

    /** -1 means the material keeps its chunk on its own; 0 means it doesn't count; more is a score. */
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();
    private final List<String> keep;
    private final Map<String, Integer> score;

    public IntentRules(List<String> keep, Map<String, Integer> score) {
        this.keep = keep;
        this.score = score;
    }

    public static IntentRules from(RegenConfig c) {
        return new IntentRules(c.intentKeep, c.intentScore);
    }

    public int classify(String material) {
        return cache.computeIfAbsent(material, m -> {
            if (RegenConfig.matchesAny(keep, m)) return -1;
            for (var e : score.entrySet()) if (RegenConfig.matches(e.getKey(), m)) return e.getValue();
            return 0;
        });
    }

    public boolean keepsAlone(String material) {
        return classify(material) < 0;
    }

    public int score(String material) {
        return Math.max(0, classify(material));
    }

    /** Whether a placement is worth recording at all. */
    public boolean worthRecording(String material) {
        return PATH.equals(material) || classify(material) != 0;
    }
}

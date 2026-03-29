package com.archivist.detection;

import java.util.Set;

public final class DetectionConstants {
    private DetectionConstants() {}

    public static final Set<String> IGNORED_NAMESPACES = Set.of(
            "minecraft", "fabric", "fabric-api", "fabricloader", "java", "c", "bukkit"
    );
}

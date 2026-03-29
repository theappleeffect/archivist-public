package com.archivist.detection.fingerprint;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A captured server GUI inventory with all items.
 */
public record GuiCapture(
        int syncId,
        String containerType,
        String title,
        String command,
        List<CapturedItem> items,
        String timestamp
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public static GuiCapture capture(int syncId, String containerType, String title,
                                     String command, List<CapturedItem> items) {
        return new GuiCapture(syncId, containerType, title, command, items,
                FORMATTER.format(Instant.now()));
    }
}

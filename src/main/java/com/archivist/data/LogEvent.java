package com.archivist.data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Immutable log event representing a single occurrence during a server session.
 */
public final class LogEvent {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public enum Type {
        CONNECT, DISCONNECT, BRAND, PLUGIN, WORLD,
        GAMEMODE, PACKET, SYSTEM, ERROR, DB_SYNC
    }

    private final Type type;
    private final String message;
    private final String timestamp;

    public LogEvent(Type type, String message) {
        this.type = type;
        this.message = message;
        this.timestamp = LocalDate.now().format(TIME_FORMAT);
    }

    public Type getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + type + ": " + message;
    }
}

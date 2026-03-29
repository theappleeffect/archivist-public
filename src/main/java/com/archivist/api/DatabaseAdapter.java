package com.archivist.api;

import com.archivist.data.ServerLogData;

import java.util.List;

/**
 * Interface for uploading Archivist log data to an external storage backend.
 *
 * <p>Implementations include REST, Discord webhooks, SQLite, PostgreSQL,
 * MongoDB, and user-supplied custom adapters.</p>
 *
 * <p>All methods that perform I/O should be called from a background thread
 * (via {@link com.archivist.util.ArchivistExecutor}).</p>
 */
public interface DatabaseAdapter {

    /**
     * Initializes the connection to the backend.
     *
     * @param connectionString URL, JDBC string, or other connection identifier
     * @param authToken        authentication token or password (may be empty)
     * @throws Exception if the connection cannot be established
     */
    void connect(String connectionString, String authToken) throws Exception;

    /**
     * Cleanly shuts down the connection.
     */
    void disconnect();

    /**
     * Uploads a single log entry.
     */
    void upload(ServerLogData entry) throws Exception;

    /**
     * Uploads multiple log entries. Default implementation loops {@link #upload}.
     */
    default void uploadBatch(List<ServerLogData> entries) throws Exception {
        for (ServerLogData entry : entries) {
            upload(entry);
        }
    }

    /**
     * Returns true if the connection is healthy.
     */
    boolean testConnection();

    /**
     * Human-readable adapter name (for GUI display).
     */
    String displayName();
}

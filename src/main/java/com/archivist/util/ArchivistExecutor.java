package com.archivist.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-threaded executor for background I/O operations.
 */
public final class ArchivistExecutor {

    private static final ExecutorService IO_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Archivist-IO");
                t.setDaemon(true);
                return t;
            });

    private ArchivistExecutor() {}

    public static void execute(Runnable task) {
        IO_EXECUTOR.execute(task);
    }

    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        IO_EXECUTOR.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static void shutdown() {
        IO_EXECUTOR.shutdown();
    }
}

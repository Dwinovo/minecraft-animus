package com.dwinovo.animus.pathing.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A single shared background thread for off-thread world scans — the analogue of
 * Baritone's global pathing executor. Mining's periodic ore rescan reads loaded
 * chunk section palettes; doing it here keeps the server tick free of the
 * {@code (2r)³}-cell sweep. One daemon thread (scans are fast and serialising
 * them across companions avoids piling work on the CPU); results are advisory
 * and re-validated on the main thread before anything is mined.
 */
public final class ScanExecutor {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "animus-scan");
        t.setDaemon(true);
        return t;
    });

    private ScanExecutor() {}

    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, EXEC);
    }
}

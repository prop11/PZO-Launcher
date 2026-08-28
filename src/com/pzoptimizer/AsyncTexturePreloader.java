package com.pzoptimizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class AsyncTexturePreloader {
    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "PZO-AsyncTextureLoader-" + (++count));
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        }
    );

    public static void queuePreload(Runnable task) {
        if (!PRELOAD_EXECUTOR.isShutdown()) {
            PRELOAD_EXECUTOR.submit(task);
        }
    }

    public static void shutdown() {
        PRELOAD_EXECUTOR.shutdownNow();
    }
}

package com.pzoptimizer;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Logs which game methods PZO rewrote, one "[PZO Rewrites] class: method, method" line per class in pzo_engine.log.
 *
 * A crash inside a rewritten game method has no com.pzoptimizer frame in its stack, so the crash diagnoser needs this
 * list to say "possibly PZO". Working out which methods changed means parsing the class twice, so that happens on a
 * background thread and class loading never waits for it.
 */
final class RewriteLog {

    static final String PREFIX = "[PZO Rewrites] ";

    private static final LinkedBlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
    private static Thread worker;

    private RewriteLog() {}

    static void note(String className, byte[] before, byte[] after) {
        if (className == null || before == null || after == null || after == before) return;
        queue.offer(new Object[] {className, before, after});
        synchronized (RewriteLog.class) {
            if (worker != null) return;
            worker = new Thread(RewriteLog::run, "PZO-RewriteLog");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void run() {
        while (true) {
            try {
                Object[] job = queue.take();
                String name = ((String) job[0]).replace('/', '.');
                java.util.Set<String> changed = AsmSupport.changedMethodNames((byte[]) job[1], (byte[]) job[2]);
                if (!changed.isEmpty()) PZOLogger.info(PREFIX + name + ": " + String.join(", ", changed));
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // a class I can't parse just goes unlisted
            }
        }
    }
}

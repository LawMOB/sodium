package net.caffeinemc.mods.sodium.client.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * diagnostic-only utility: does not fix anything by itself. it watches for the main/render thread going
 * silent (no heartbeat) for longer than {@link #STALL_THRESHOLD_MS}, which is what a full freeze
 *
 */
public class HangWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-HangWatchdog");

    // how long without a heartbeat before consider the game "frozen"
    private static final long STALL_THRESHOLD_MS = 4000;
    // how often the watchdog thread checks
    private static final long CHECK_INTERVAL_MS = 1000;
    // minim time between two dumps for the *same* stall episode, so don't spam files
    // if the freeze lasts a long time (one dump right away, then one every 15s while still stuck)
    private static final long REDUMP_INTERVAL_MS = 15000;

    private static final AtomicLong lastHeartbeatMs = new AtomicLong(System.currentTimeMillis());
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile long lastDumpMs = 0;

    /** call this once per frame/tick from the render loop. cheap: just a volatile write */
    public static void heartbeat() {
        lastHeartbeatMs.set(System.currentTimeMillis());
    }

    /** call once during mod init. safe to call multiple times; only starts once */
    public static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Thread watchdog = new Thread(HangWatchdog::run, "Sodium Hang Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();

        LOGGER.info("Hang watchdog started (stall threshold: {}ms)", STALL_THRESHOLD_MS);
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }

            long now = System.currentTimeMillis();
            long sinceHeartbeat = now - lastHeartbeatMs.get();

            if (sinceHeartbeat >= STALL_THRESHOLD_MS) {
                long sinceLastDump = now - lastDumpMs;

                if (lastDumpMs == 0 || sinceLastDump >= REDUMP_INTERVAL_MS) {
                    dumpAllThreads(sinceHeartbeat);
                    lastDumpMs = now;
                }
            } else {
                // heartbeat resumed; allow a fresh immediate dump next time it stalls
                lastDumpMs = 0;
            }
        }
    }

    private static void dumpAllThreads(long stalledForMs) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        File dir = new File("hang-reports");

        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.error("Could not create hang-reports directory");
        }

        File out = new File(dir, "hang-" + timestamp + ".txt");

        try (PrintWriter writer = new PrintWriter(new FileWriter(out))) {
            writer.println("=== Sodium Hang Watchdog Report ===");
            writer.println("Generated: " + timestamp);
            writer.println("No heartbeat for: " + stalledForMs + " ms");
            writer.println();

            Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();

            // print the most relevant threads first
            printThreadIfPresent(writer, allStacks, "Render thread");
            printThreadIfPresent(writer, allStacks, "Client thread");

            writer.println();
            writer.println("=== All threads ===");

            for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
                Thread thread = entry.getKey();
                writer.println();
                writer.println("Thread: \"" + thread.getName() + "\" id=" + thread.getId()
                        + " state=" + thread.getState() + " daemon=" + thread.isDaemon());

                for (StackTraceElement element : entry.getValue()) {
                    writer.println("\tat " + element);
                }
            }

            writer.flush();
            LOGGER.error("Detected a stall (no heartbeat for {} ms). Wrote thread dump to {}",
                    stalledForMs, out.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write hang report", e);
        }
    }

    private static void printThreadIfPresent(PrintWriter writer, Map<Thread, StackTraceElement[]> allStacks, String nameContains) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
            if (entry.getKey().getName().contains(nameContains)) {
                writer.println("=== " + entry.getKey().getName() + " (highlighted) ===");
                writer.println("state=" + entry.getKey().getState());
                for (StackTraceElement element : entry.getValue()) {
                    writer.println("\tat " + element);
                }
                writer.println();
            }
        }
    }
}

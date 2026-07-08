package net.caffeinemc.mods.sodium.client.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * diagnostic-only utility: does not fix anything by itself. it watches for the main/render thread going
 * silent (no heartbeat) for longer than {@link #STALL_THRESHOLD_MS}, which is what a full freeze
 *
 */
public class HangWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-HangWatchdog");

    // How long without a heartbeat before we consider the game "frozen".
    private static final long STALL_THRESHOLD_MS = 4000;
    // How often the watchdog thread checks.
    private static final long CHECK_INTERVAL_MS = 1000;
    // Minimum time between two dumps for the *same* stall episode, so we don't spam files
    // if the freeze lasts a long time (one dump right away, then one every 15s while still stuck).
    private static final long REDUMP_INTERVAL_MS = 15000;

    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private static final AtomicLong lastHeartbeatMs = new AtomicLong(System.currentTimeMillis());
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile long lastDumpMs = 0;

    /** Call this once per frame/tick from the render loop. Cheap: just a volatile write. */
    public static void heartbeat() {
        lastHeartbeatMs.set(System.currentTimeMillis());
    }

    /** Call once during mod init. Safe to call multiple times; only starts once. */
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
                // Heartbeat resumed; allow a fresh immediate dump next time it stalls.
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

            long[] deadlockedIds = THREAD_MX_BEAN.findDeadlockedThreads();
            if (deadlockedIds != null && deadlockedIds.length > 0) {
                writer.println("*** JVM-CONFIRMED DEADLOCK detected among " + deadlockedIds.length + " thread(s) ***");
                writer.println();
            } else {
                writer.println("(No JVM-confirmed deadlock cycle found; may still be blocked on I/O, a native call, or a livelock.)");
                writer.println();
            }

            ThreadInfo[] allThreads = THREAD_MX_BEAN.dumpAllThreads(true, true);

            // Print the most relevant threads first.
            printThreadIfPresent(writer, allThreads, "Render thread");
            printThreadIfPresent(writer, allThreads, "Client thread");

            writer.println();
            writer.println("=== All threads ===");

            for (ThreadInfo info : allThreads) {
                if (info == null) continue;
                writer.println();
                printThreadInfo(writer, info);
            }

            writer.flush();
            LOGGER.error("Detected a stall (no heartbeat for {} ms). Wrote thread dump to {}",
                    stalledForMs, out.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write hang report", e);
        }
    }

    private static void printThreadIfPresent(PrintWriter writer, ThreadInfo[] allThreads, String nameContains) {
        for (ThreadInfo info : allThreads) {
            if (info != null && info.getThreadName().contains(nameContains)) {
                writer.println("=== " + info.getThreadName() + " (highlighted) ===");
                printThreadInfo(writer, info);
                writer.println();
            }
        }
    }

    private static void printThreadInfo(PrintWriter writer, ThreadInfo info) {
        writer.println("Thread: \"" + info.getThreadName() + "\" id=" + info.getThreadId()
                + " state=" + info.getThreadState()
                + (info.isDaemon() ? " daemon" : ""));

        LockInfo lockInfo = info.getLockInfo();
        if (lockInfo != null) {
            writer.println("\t- waiting on: " + lockInfo
                    + (info.getLockOwnerName() != null
                        ? " owned by \"" + info.getLockOwnerName() + "\" (id=" + info.getLockOwnerId() + ")"
                        : " (owner unknown - not a monitor, or owner not tracked)"));
        }

        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] lockedMonitors = info.getLockedMonitors();

        for (int i = 0; i < stack.length; i++) {
            writer.println("\tat " + stack[i]);
            // Show exactly which stack frame is holding which lock - this is what makes it
            // possible to see "thread A holds lock X here, while thread B waits on lock X".
            for (MonitorInfo m : lockedMonitors) {
                if (m.getLockedStackFrame().equals(stack[i])) {
                    writer.println("\t- locked: " + m);
                }
            }
        }

        LockInfo[] lockedSynchronizers = info.getLockedSynchronizers();
        if (lockedSynchronizers.length > 0) {
            writer.println("\tLocked synchronizers (java.util.concurrent):");
            for (LockInfo l : lockedSynchronizers) {
                writer.println("\t- " + l);
            }
        }
    }
}

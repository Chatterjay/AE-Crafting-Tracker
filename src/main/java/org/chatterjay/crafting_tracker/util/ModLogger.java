package org.chatterjay.crafting_tracker.util;

import com.mojang.logging.LogUtils;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModLogger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Long> LAST_DEBUG_LOG_MS = new ConcurrentHashMap<>();

    private ModLogger() {
    }

    public static void info(String message, Object... args) {
        LOGGER.info(message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Object... args) {
        LOGGER.error(message, args);
    }

    public static void debug(String message, Object... args) {
        if (isDebugEnabled()) {
            LOGGER.info("[DEBUG] " + message, args);
        }
    }

    /** Emits a diagnostic message at most once per key and interval. */
    public static void debugThrottled(String key, long intervalTicks, String message, Object... args) {
        if (!isDebugEnabled()) return;
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(50L, intervalTicks * 50L);
        Long previous = LAST_DEBUG_LOG_MS.putIfAbsent(key, now);
        if (previous != null && now - previous < intervalMs) return;
        if (previous != null && !LAST_DEBUG_LOG_MS.replace(key, previous, now)) return;
        if (LAST_DEBUG_LOG_MS.size() > 4096) {
            long staleBefore = now - Math.max(60_000L, intervalMs * 4L);
            LAST_DEBUG_LOG_MS.entrySet().removeIf(entry -> entry.getValue() < staleBefore);
        }
        LOGGER.info("[DEBUG] " + message, args);
    }

    public static boolean isDebugEnabled() {
        try {
            return CTConfig.debugTracking;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}

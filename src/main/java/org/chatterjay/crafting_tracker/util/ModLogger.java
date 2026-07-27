package org.chatterjay.crafting_tracker.util;

import com.mojang.logging.LogUtils;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.slf4j.Logger;

public final class ModLogger {
    private static final Logger LOGGER = LogUtils.getLogger();

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

    public static boolean isDebugEnabled() {
        try {
            return CTConfig.debugTracking;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}

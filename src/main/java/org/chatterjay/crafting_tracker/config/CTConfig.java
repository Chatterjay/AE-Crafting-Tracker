package org.chatterjay.crafting_tracker.config;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

public final class CTConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final String DEFAULT_COLOR_ACTIVE = "#55FF55";
    private static final String DEFAULT_COLOR_STALLED = "#FFFF55";
    private static final String DEFAULT_COLOR_STUCK = "#FF5555";

    // ---- Status thresholds ----
    public static final ModConfigSpec.IntValue STALL_THRESHOLD_SECONDS;
    public static final ModConfigSpec.IntValue STUCK_THRESHOLD_SECONDS;

    // ---- Scan ----
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SCAN_RADIUS;

    // ---- Appearance: colors ----
    public static final ModConfigSpec.ConfigValue<String> COLOR_ACTIVE;
    public static final ModConfigSpec.ConfigValue<String> COLOR_STALLED;
    public static final ModConfigSpec.ConfigValue<String> COLOR_STUCK;

    // ---- Appearance: opacity ----
    public static final ModConfigSpec.IntValue BADGE_BACKGROUND_ALPHA;
    public static final ModConfigSpec.IntValue BADGE_ACCENT_ALPHA;
    public static final ModConfigSpec.IntValue OUTLINE_ALPHA;

    // ---- Diagnostics ----
    public static final ModConfigSpec.BooleanValue DEBUG_TRACKING;
    public static final ModConfigSpec.IntValue DEBUG_LOG_INTERVAL_TICKS;

    static {
        BUILDER.push("status");

        STALL_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a busy provider transitions from active to slow.")
                .translation(Crafting_tracker.MODID + ".config.status.stallThresholdSeconds")
                .defineInRange("stallThresholdSeconds", 5, 1, 60);

        STUCK_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a slow provider transitions to blocked.")
                .translation(Crafting_tracker.MODID + ".config.status.stuckThresholdSeconds")
                .defineInRange("stuckThresholdSeconds", 15, 5, 120);

        BUILDER.pop();
        BUILDER.push("scan");

        SCAN_INTERVAL_TICKS = BUILDER
                .comment("Ticks between full provider scans (20 = 1 second).")
                .translation(Crafting_tracker.MODID + ".config.scan.scanIntervalTicks")
                .defineInRange("scanIntervalTicks", 20, 5, 100);

        SCAN_RADIUS = BUILDER
                .comment("Radius in blocks to scan for pattern providers.")
                .translation(Crafting_tracker.MODID + ".config.scan.scanRadius")
                .defineInRange("scanRadius", 64, 16, 256);

        BUILDER.pop();
        BUILDER.push("appearance");
        BUILDER.push("colors");

        COLOR_ACTIVE = BUILDER
                .comment("Hex RGB color for active providers. Use #RRGGBB or 0xRRGGBB.")
                .translation(Crafting_tracker.MODID + ".config.appearance.colors.active")
                .define("active", DEFAULT_COLOR_ACTIVE, CTConfig::isColorValue);

        COLOR_STALLED = BUILDER
                .comment("Hex RGB color for slow providers. Use #RRGGBB or 0xRRGGBB.")
                .translation(Crafting_tracker.MODID + ".config.appearance.colors.stalled")
                .define("stalled", DEFAULT_COLOR_STALLED, CTConfig::isColorValue);

        COLOR_STUCK = BUILDER
                .comment("Hex RGB color for blocked providers. Use #RRGGBB or 0xRRGGBB.")
                .translation(Crafting_tracker.MODID + ".config.appearance.colors.stuck")
                .define("stuck", DEFAULT_COLOR_STUCK, CTConfig::isColorValue);

        BUILDER.pop();
        BUILDER.push("opacity");

        BADGE_BACKGROUND_ALPHA = BUILDER
                .comment("Opacity for the floating status badge background (0=transparent, 255=solid).")
                .translation(Crafting_tracker.MODID + ".config.appearance.opacity.badgeBackground")
                .defineInRange("badgeBackground", 30, 0, 255);

        BADGE_ACCENT_ALPHA = BUILDER
                .comment("Opacity for the floating status badge accent strip (0=transparent, 255=solid).")
                .translation(Crafting_tracker.MODID + ".config.appearance.opacity.badgeAccent")
                .defineInRange("badgeAccent", 80, 0, 255);

        OUTLINE_ALPHA = BUILDER
                .comment("Opacity for the shape-aware highlight outline (0=transparent, 255=solid).")
                .translation(Crafting_tracker.MODID + ".config.appearance.opacity.outline")
                .defineInRange("outline", 255, 0, 255);

        BUILDER.pop();
        BUILDER.pop();

        BUILDER.push("diagnostics");

        DEBUG_TRACKING = BUILDER
                .comment("Log detailed provider tracking state for diagnosing flicker or missing status updates.")
                .translation(Crafting_tracker.MODID + ".config.diagnostics.debugTracking")
                .define("debugTracking", false);

        DEBUG_LOG_INTERVAL_TICKS = BUILDER
                .comment("Minimum ticks between repeated tracking debug logs for the same provider.")
                .translation(Crafting_tracker.MODID + ".config.diagnostics.debugLogIntervalTicks")
                .defineInRange("debugLogIntervalTicks", 20, 1, 200);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static int stallThresholdSeconds;
    public static int stuckThresholdSeconds;
    public static int scanIntervalTicks;
    public static int scanRadius;
    public static int colorActive;
    public static int colorStalled;
    public static int colorStuck;
    public static int badgeBackgroundAlpha;
    public static int badgeAccentAlpha;
    public static int outlineAlpha;
    public static boolean debugTracking;
    public static int debugLogIntervalTicks;

    private CTConfig() {}

    public static void onLoad(final ModConfigEvent event) {
        if (!Crafting_tracker.MODID.equals(event.getConfig().getModId())) return;
        refreshCache();
        validate();
    }

    public static void onReload(final ModConfigEvent event) {
        if (!Crafting_tracker.MODID.equals(event.getConfig().getModId())) return;
        refreshCache();
        validate();
        LOGGER.info("Configuration reloaded");
    }

    private static void refreshCache() {
        stallThresholdSeconds = STALL_THRESHOLD_SECONDS.get();
        stuckThresholdSeconds = STUCK_THRESHOLD_SECONDS.get();
        scanIntervalTicks = SCAN_INTERVAL_TICKS.get();
        scanRadius = SCAN_RADIUS.get();
        colorActive = parseColor(COLOR_ACTIVE.get(), DEFAULT_COLOR_ACTIVE, "appearance.colors.active");
        colorStalled = parseColor(COLOR_STALLED.get(), DEFAULT_COLOR_STALLED, "appearance.colors.stalled");
        colorStuck = parseColor(COLOR_STUCK.get(), DEFAULT_COLOR_STUCK, "appearance.colors.stuck");
        badgeBackgroundAlpha = BADGE_BACKGROUND_ALPHA.get();
        badgeAccentAlpha = BADGE_ACCENT_ALPHA.get();
        outlineAlpha = OUTLINE_ALPHA.get();
        debugTracking = DEBUG_TRACKING.get();
        debugLogIntervalTicks = DEBUG_LOG_INTERVAL_TICKS.get();
    }

    public static void validate() {
        validateInt(STALL_THRESHOLD_SECONDS, "status.stallThresholdSeconds", 5, 1, 60);
        validateInt(STUCK_THRESHOLD_SECONDS, "status.stuckThresholdSeconds", 15, 5, 120);
        validateInt(SCAN_INTERVAL_TICKS, "scan.scanIntervalTicks", 20, 5, 100);
        validateInt(SCAN_RADIUS, "scan.scanRadius", 64, 16, 256);
        validateColor(COLOR_ACTIVE, "appearance.colors.active", DEFAULT_COLOR_ACTIVE);
        validateColor(COLOR_STALLED, "appearance.colors.stalled", DEFAULT_COLOR_STALLED);
        validateColor(COLOR_STUCK, "appearance.colors.stuck", DEFAULT_COLOR_STUCK);
        validateInt(BADGE_BACKGROUND_ALPHA, "appearance.opacity.badgeBackground", 30, 0, 255);
        validateInt(BADGE_ACCENT_ALPHA, "appearance.opacity.badgeAccent", 80, 0, 255);
        validateInt(OUTLINE_ALPHA, "appearance.opacity.outline", 255, 0, 255);
        validateInt(DEBUG_LOG_INTERVAL_TICKS, "diagnostics.debugLogIntervalTicks", 20, 1, 200);
        refreshCache();
    }

    private static boolean isColorValue(Object value) {
        return value instanceof String text && tryParseColor(text) != null;
    }

    private static void validateColor(ModConfigSpec.ConfigValue<String> value, String path, String fallback) {
        String text = value.get();
        if (tryParseColor(text) == null) {
            LOGGER.warn("[CTConfig] '{}' = '{}' is not a valid hex RGB color, falling back to {}",
                    path, text, fallback);
            value.set(fallback);
        }
    }

    private static int parseColor(String text, String fallback, String path) {
        Integer parsed = tryParseColor(text);
        if (parsed != null) return parsed;

        LOGGER.warn("[CTConfig] '{}' = '{}' is not a valid hex RGB color, using {}",
                path, text, fallback);
        return tryParseColor(fallback);
    }

    private static Integer tryParseColor(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        if (text.length() != 6 || !text.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(text, 16);
    }

    private static void validateInt(ModConfigSpec.IntValue value, String path, int fallback, int min, int max) {
        int v = value.get();
        if (v < min || v > max) {
            LOGGER.warn("[CTConfig] '{}' = {} is out of range [{}, {}], falling back to default {}",
                    path, v, min, max, fallback);
            value.set(fallback);
        }
    }
}

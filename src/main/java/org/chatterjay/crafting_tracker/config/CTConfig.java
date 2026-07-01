package org.chatterjay.crafting_tracker.config;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

public final class CTConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- Highlight ----
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_ENABLED;
    public static final ModConfigSpec.IntValue STALL_THRESHOLD_SECONDS;
    public static final ModConfigSpec.IntValue STUCK_THRESHOLD_SECONDS;

    // ---- Scan ----
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SCAN_RADIUS;

    // ---- Colors ----
    public static final ModConfigSpec.IntValue COLOR_ACTIVE;
    public static final ModConfigSpec.IntValue COLOR_STALLED;
    public static final ModConfigSpec.IntValue COLOR_STUCK;

    // ---- Opacity ----
    public static final ModConfigSpec.IntValue FILL_ALPHA_INNER;
    public static final ModConfigSpec.IntValue FILL_ALPHA_OUTER;
    public static final ModConfigSpec.IntValue OUTLINE_ALPHA;

    static {
        BUILDER.push("highlight");

        HIGHLIGHT_ENABLED = BUILDER
                .comment("Whether crafting highlight is enabled by default for all players")
                .translation(Crafting_tracker.MODID + ".config.highlight.enabled")
                .define("enabled", false);

        STALL_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a busy provider transitions from ACTIVE (green) to STALLED (yellow)")
                .translation(Crafting_tracker.MODID + ".config.highlight.stallThresholdSeconds")
                .defineInRange("stallThresholdSeconds", 5, 1, 60);

        STUCK_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a stalled provider transitions to STUCK (red)")
                .translation(Crafting_tracker.MODID + ".config.highlight.stuckThresholdSeconds")
                .defineInRange("stuckThresholdSeconds", 15, 5, 120);

        BUILDER.pop();
        BUILDER.push("scan");

        SCAN_INTERVAL_TICKS = BUILDER
                .comment("Ticks between provider scans (20 = 1 second)")
                .translation(Crafting_tracker.MODID + ".config.scan.scanIntervalTicks")
                .defineInRange("scanIntervalTicks", 20, 5, 100);

        SCAN_RADIUS = BUILDER
                .comment("Radius in blocks to scan for pattern providers")
                .translation(Crafting_tracker.MODID + ".config.scan.scanRadius")
                .defineInRange("scanRadius", 64, 16, 256);

        BUILDER.pop();
        BUILDER.push("colors");

        COLOR_ACTIVE = BUILDER
                .comment("Highlight color for ACTIVE providers (RGB hex as decimal, e.g. 5635925 = 0x55FF55)")
                .translation(Crafting_tracker.MODID + ".config.colors.colorActive")
                .defineInRange("colorActive", 0x55FF55, 0x000000, 0xFFFFFF);

        COLOR_STALLED = BUILDER
                .comment("Highlight color for STALLED providers")
                .translation(Crafting_tracker.MODID + ".config.colors.colorStalled")
                .defineInRange("colorStalled", 0xFFFF55, 0x000000, 0xFFFFFF);

        COLOR_STUCK = BUILDER
                .comment("Highlight color for STUCK providers")
                .translation(Crafting_tracker.MODID + ".config.colors.colorStuck")
                .defineInRange("colorStuck", 0xFF5555, 0x000000, 0xFFFFFF);

        FILL_ALPHA_INNER = BUILDER
                .comment("Opacity for inner highlight fill (0=transparent, 255=solid)")
                .translation(Crafting_tracker.MODID + ".config.colors.fillAlphaInner")
                .defineInRange("fillAlphaInner", 30, 0, 255);

        FILL_ALPHA_OUTER = BUILDER
                .comment("Opacity for outer highlight glow (0=transparent, 255=solid)")
                .translation(Crafting_tracker.MODID + ".config.colors.fillAlphaOuter")
                .defineInRange("fillAlphaOuter", 80, 0, 255);

        OUTLINE_ALPHA = BUILDER
                .comment("Opacity for highlight outline (0=transparent, 255=solid)")
                .translation(Crafting_tracker.MODID + ".config.colors.outlineAlpha")
                .defineInRange("outlineAlpha", 255, 0, 255);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Cached values for fast access
    public static boolean highlightEnabled;
    public static int stallThresholdSeconds;
    public static int stuckThresholdSeconds;
    public static int scanIntervalTicks;
    public static int scanRadius;
    public static int colorActive;
    public static int colorStalled;
    public static int colorStuck;
    public static int fillAlphaInner;
    public static int fillAlphaOuter;
    public static int outlineAlpha;

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

    /** Re-read all cached values from the ModConfigSpec. */
    private static void refreshCache() {
        highlightEnabled = HIGHLIGHT_ENABLED.get();
        stallThresholdSeconds = STALL_THRESHOLD_SECONDS.get();
        stuckThresholdSeconds = STUCK_THRESHOLD_SECONDS.get();
        scanIntervalTicks = SCAN_INTERVAL_TICKS.get();
        scanRadius = SCAN_RADIUS.get();
        colorActive = COLOR_ACTIVE.get();
        colorStalled = COLOR_STALLED.get();
        colorStuck = COLOR_STUCK.get();
        fillAlphaInner = FILL_ALPHA_INNER.get();
        fillAlphaOuter = FILL_ALPHA_OUTER.get();
        outlineAlpha = OUTLINE_ALPHA.get();
    }

    /** Validate config values and log warnings for out-of-range values. */
    public static void validate() {
        validateInt(STALL_THRESHOLD_SECONDS, "highlight.stallThresholdSeconds", 5, 1, 60);
        validateInt(STUCK_THRESHOLD_SECONDS, "highlight.stuckThresholdSeconds", 15, 5, 120);
        validateInt(SCAN_INTERVAL_TICKS, "scan.scanIntervalTicks", 20, 5, 100);
        validateInt(SCAN_RADIUS, "scan.scanRadius", 64, 16, 256);
        validateInt(COLOR_ACTIVE, "colors.colorActive", 0x55FF55, 0x000000, 0xFFFFFF);
        validateInt(COLOR_STALLED, "colors.colorStalled", 0xFFFF55, 0x000000, 0xFFFFFF);
        validateInt(COLOR_STUCK, "colors.colorStuck", 0xFF5555, 0x000000, 0xFFFFFF);
        validateInt(FILL_ALPHA_INNER, "colors.fillAlphaInner", 30, 0, 255);
        validateInt(FILL_ALPHA_OUTER, "colors.fillAlphaOuter", 80, 0, 255);
        validateInt(OUTLINE_ALPHA, "colors.outlineAlpha", 255, 0, 255);
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

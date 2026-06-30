package org.chatterjay.crafting_tracker.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import org.chatterjay.crafting_tracker.Crafting_tracker;

@EventBusSubscriber(modid = Crafting_tracker.MODID, bus = EventBusSubscriber.Bus.MOD)
public class CTConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HIGHLIGHT_ENABLED;
    public static final ModConfigSpec.IntValue STALL_THRESHOLD_SECONDS;
    public static final ModConfigSpec.IntValue STUCK_THRESHOLD_SECONDS;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SCAN_RADIUS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("highlight");
        HIGHLIGHT_ENABLED = BUILDER
                .comment("Whether crafting highlight is enabled by default for all players")
                .define("enabled", true);
        STALL_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a busy provider transitions from ACTIVE (green) to STALLED (yellow)")
                .defineInRange("stallThresholdSeconds", 5, 1, 60);
        STUCK_THRESHOLD_SECONDS = BUILDER
                .comment("Seconds before a stalled provider transitions to STUCK (red)")
                .defineInRange("stuckThresholdSeconds", 15, 5, 120);
        SCAN_INTERVAL_TICKS = BUILDER
                .comment("Ticks between provider scans (20 = 1 second)")
                .defineInRange("scanIntervalTicks", 20, 5, 100);
        SCAN_RADIUS = BUILDER
                .comment("Radius in blocks to scan for pattern providers")
                .defineInRange("scanRadius", 64, 16, 256);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static boolean highlightEnabled;
    public static int stallThresholdSeconds;
    public static int stuckThresholdSeconds;
    public static int scanIntervalTicks;
    public static int scanRadius;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        highlightEnabled = HIGHLIGHT_ENABLED.get();
        stallThresholdSeconds = STALL_THRESHOLD_SECONDS.get();
        stuckThresholdSeconds = STUCK_THRESHOLD_SECONDS.get();
        scanIntervalTicks = SCAN_INTERVAL_TICKS.get();
        scanRadius = SCAN_RADIUS.get();
    }
}

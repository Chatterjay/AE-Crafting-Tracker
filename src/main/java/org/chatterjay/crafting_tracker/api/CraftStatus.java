package org.chatterjay.crafting_tracker.api;

public enum CraftStatus {
    ACTIVE(0x55FF55),
    STALLED(0xFFFF55),
    STUCK(0xFF5555);

    public final int color;

    CraftStatus(int color) {
        this.color = color;
    }
}

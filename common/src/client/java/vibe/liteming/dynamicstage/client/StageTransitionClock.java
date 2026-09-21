package vibe.liteming.dynamicstage.client;

/** Monotonic client clock for transitions that cross ClientLevel instances. */
public record StageTransitionClock(long startNanos, int durationTicks) {
    public StageTransitionClock {
        if (durationTicks < 1) {
            throw new IllegalArgumentException("durationTicks must be positive");
        }
    }

    public static StageTransitionClock start(int durationTicks) {
        return new StageTransitionClock(System.nanoTime(), durationTicks);
    }

    public long tickNanos() {
        return durationTicks * 50_000_000L;
    }

    public boolean reached(long nowNanos) {
        return nowNanos - startNanos >= tickNanos();
    }

    public float progress(long nowNanos) {
        return Math.max(0.0F, Math.min(1.0F,
                (nowNanos - startNanos) / (float) tickNanos()));
    }
}

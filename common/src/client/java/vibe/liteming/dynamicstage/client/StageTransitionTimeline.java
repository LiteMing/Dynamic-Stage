package vibe.liteming.dynamicstage.client;

/** Render-thread timeline, independent of ClientLevel and game time. */
public final class StageTransitionTimeline {
    private final long started;
    private final long fadeIn;
    private final long fadeOut;
    private boolean opaqueDrawn;
    private boolean presented;
    private long targetReady = -1L;
    private long failed = -1L;
    private long arrived = -1L;

    public StageTransitionTimeline(int durationTicks, long now) {
        started = now;
        int inTicks = Math.max(2, Math.round(durationTicks * 0.25F));
        fadeIn = inTicks * 50_000_000L;
        fadeOut = Math.max(2, durationTicks - inTicks) * 50_000_000L;
    }

    public void drawn(long now) { opaqueDrawn = alpha(now) >= 1.0F; }

    /** Called only AFTER Window.updateDisplay(), not from tick or Gui.render(). */
    public boolean presented() {
        if (presented || !opaqueDrawn || failed()) return false;
        presented = true;
        return true;
    }

    public boolean hasPresented() { return presented; }
    public void arrived(long now) { if (arrived == -1L) arrived = now; }
    public void cancelFailure() { failed = -1L; targetReady = -1L; }
    public boolean revealing() { return targetReady != -1L && !failed(); }
    public void targetReady(long now) {
        if (presented && targetReady == -1L && !failed()) targetReady = now;
    }
    public void fail(long now) { if (!failed()) failed = now; }
    public boolean failed() { return failed != -1L; }
    public boolean fallbackDue(long now) { return failed() && now - failed >= 3_000_000_000L; }
    public boolean finished(long now) { return revealing() && now - targetReady >= fadeOut; }
    public boolean timedOut(long now, boolean preparingPackage) {
        if (arrived != -1L) return now - arrived >= 30_000_000_000L;
        return now - started >= (preparingPackage ? 600_000_000_000L : 30_000_000_000L);
    }
    public float alpha(long now) {
        if (failed()) return 1.0F;
        if (targetReady != -1L) return 1.0F - smooth((float) (now - targetReady) / fadeOut);
        return smooth((float) (now - started) / fadeIn);
    }
    private static float smooth(float value) {
        float t = Math.max(0F, Math.min(1F, value));
        return t * t * (3F - 2F * t);
    }
}

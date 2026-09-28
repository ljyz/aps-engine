package com.smartmes.aps.core;

import com.smartmes.aps.api.ApsEngine.ProgressListener;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 单次排产的进度通知；同一百分比下每500ms刷新明细，阶段变化立即通知。 */
final class ApsEngineProgress {
    private static final long REFRESH_NANOS = 500_000_000L;
    private final String requestId;
    private final ProgressListener listener;
    private final LongSupplier clock;
    private int percent;
    private int emittedPercent = -1;
    private String emittedStage;
    private long emittedAt;

    ApsEngineProgress(String requestId, ProgressListener listener) {
        this(requestId, listener, System::nanoTime);
    }

    ApsEngineProgress(String requestId, ProgressListener listener, LongSupplier clock) {
        this.requestId = requestId;
        this.listener = listener;
        this.clock = clock;
    }

    void report(String stage, double value, String message) {
        report(stage, value, () -> message);
    }

    void report(String stage, double value, Supplier<String> message) {
        percent = Math.max(percent, Math.min(100, Math.max(0, (int) value)));
        long now = clock.getAsLong();
        if (percent == emittedPercent && stage.equals(emittedStage) && now - emittedAt < REFRESH_NANOS) {
            return;
        }
        listener.onProgress(requestId, stage, percent, message.get());
        emittedPercent = percent;
        emittedStage = stage;
        emittedAt = now;
    }

    int percent() {
        return percent;
    }
}

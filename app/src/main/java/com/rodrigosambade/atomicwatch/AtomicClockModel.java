package com.rodrigosambade.atomicwatch;

import android.os.SystemClock;

final class AtomicClockModel {
    private boolean calibrated;
    private long anchorEpochMs;
    private long anchorElapsedRealtimeMs;

    synchronized void calibrate(long epochAtReceiveMs, long receiveElapsedRealtimeMs) {
        calibrated = true;
        anchorEpochMs = epochAtReceiveMs;
        anchorElapsedRealtimeMs = receiveElapsedRealtimeMs;
    }

    synchronized boolean isCalibrated() {
        return calibrated;
    }

    synchronized long nowMs() {
        if (!calibrated) return System.currentTimeMillis();
        return anchorEpochMs + (SystemClock.elapsedRealtime() - anchorElapsedRealtimeMs);
    }
}

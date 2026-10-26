package com.miui.aod.utils;

import android.os.Looper;

/** MIUI's optional slow-log diagnostic is not a public Android API. */
public final class LooperUtils {
    public static void setSlowLogThresholdMs(Looper looper,long dispatch,long delivery) {
        // Rendering and message delivery retain the original Looper. This
        // diagnostic-only setter is unavailable to a third-party process.
    }
}

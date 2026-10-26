package dev.unvoid.originceiler;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/** Xiaomi's AOD auto-density policy, without its global resource hooks. */
final class XiaomiAodDensity {
    static int resolve(Context host, ClassLoader loader) {
        Display display = ((WindowManager) host.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        try {
            Object config = loader.loadClass("miuix.autodensity.DisplayDensityConfig")
                .getConstructor(Context.class, Display.class).newInstance(host, display);
            Object target = config.getClass().getMethod("getTargetConfig").invoke(config);
            int dpi = target.getClass().getField("densityDpi").getInt(target);
            if (dpi >= 120 && dpi <= 1000) return dpi;
        } catch (Exception | LinkageError unavailable) {
            android.util.Log.w("OriginCeilerXiaomi", "Original auto-density unavailable; using phone policy", unavailable);
        }
        DisplayMetrics m = new DisplayMetrics();
        display.getRealMetrics(m);
        if (m.xdpi < 100 || m.ydpi < 100 || !Float.isFinite(m.xdpi) || !Float.isFinite(m.ydpi)) return m.densityDpi;
        double longPx = Math.max(m.widthPixels, m.heightPixels);
        double shortPx = Math.min(m.widthPixels, m.heightPixels);
        double longInches = longPx / Math.max(m.xdpi, m.ydpi);
        double shortInches = shortPx / Math.min(m.xdpi, m.ydpi);
        double ppi = Math.hypot(longPx, shortPx) / Math.hypot(longInches, shortInches);
        double scale = Math.min(1, Math.min(longInches, shortInches) / 2.8);
        double accessibility = (double) m.densityDpi / DisplayMetrics.DENSITY_DEVICE_STABLE;
        return (int) Math.round(ppi * 1.1398963928222656 * scale * accessibility);
    }
}

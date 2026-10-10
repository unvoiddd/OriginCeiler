package de.robv.android.xposed.callbacks;

/**
 * Compile-only stub of the LSPosed/Xposed API.
 * Never packaged into the APK; real implementation comes from LSPosed at runtime.
 *
 * Note: {@code appInfo} is typed as {@code Object} (instead of
 * {@code android.content.pm.ApplicationInfo}) so this module stays a plain
 * java-library without the Android framework on its classpath. It is unused
 * by the app module.
 */
public final class XC_LoadPackage {
    private XC_LoadPackage() {
    }

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public Object appInfo;
        public boolean isFirstApplication;
    }
}

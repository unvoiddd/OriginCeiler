package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compile-only stub of the LSPosed/Xposed API.
 * Never packaged into the APK; real implementation comes from LSPosed at runtime.
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable;
}

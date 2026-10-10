package de.robv.android.xposed;

/**
 * Compile-only stub of the LSPosed/Xposed API.
 * Never packaged into the APK; real implementation comes from LSPosed at runtime.
 */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return new XC_MethodHook.Unhook();
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        return new XC_MethodHook.Unhook();
    }
}

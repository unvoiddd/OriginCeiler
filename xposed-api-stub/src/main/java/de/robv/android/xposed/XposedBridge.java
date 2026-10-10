package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Collections;
import java.util.Set;

/**
 * Compile-only stub of the LSPosed/Xposed API.
 * Never packaged into the APK; real implementation comes from LSPosed at runtime.
 */
public final class XposedBridge {
    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable throwable) {
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return Collections.emptySet();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return Collections.emptySet();
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return new XC_MethodHook.Unhook();
    }
}

package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-only stub of the LSPosed/Xposed API.
 *
 * This module is referenced as {@code compileOnly project(":xposed-api-stub")}
 * and is never packaged into the APK: at runtime the real implementations
 * are provided by the LSPosed framework inside the hooked processes.
 */
public class XC_MethodHook {
    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Object getThisObject() {
            return thisObject;
        }

        public Object[] getArgs() {
            return args;
        }
    }

    public static class Unhook {
        public void unhook() {
        }
    }
}

package dev.unvoid.originceiler

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MaterialOriginPlayerHook {
    fun install(classLoader: ClassLoader) {
        synchronized(installedLoaders) {
            if (installedLoaders.put(classLoader, true) != null) return
        }
        classNames.forEach { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let(::installClass)
        }
        if (watcherInstalled.compareAndSet(false, true)) {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = param.result as? Class<*> ?: return
                    if (type.name in classNames) installClass(type)
                }
            })
        }
    }

    private fun installClass(type: Class<*>) {
        synchronized(installedClasses) {
            if (installedClasses.put(type, true) != null) return
        }
        when (type.name) {
            "com.vivo.musicmixcard.CCMUiPlugin" -> {
                XposedBridge.hookAllMethods(type, "qsCenterCallToMixMusic", object : XC_MethodHook(Int.MAX_VALUE) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled(param.thisObject, param.args)) return
                        when ((param.args.getOrNull(0) as? Number)?.toInt()) {
                            7, 8 -> param.result = null
                            10 -> (param.args.getOrNull(1) as? Bundle)?.putBoolean("enableBorderHlDraw", false)
                        }
                    }
                })
            }
            "com.vivo.musicmixcard.utils.k" -> {
                XposedBridge.hookAllMethods(type, "d", object : XC_MethodHook(Int.MAX_VALUE) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (enabled(null, param.args)) param.result = param.args.getOrNull(1)
                    }
                })
                listOf("e", "w").forEach { name ->
                    XposedBridge.hookAllMethods(type, name, object : XC_MethodHook(Int.MAX_VALUE) {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (enabled(null, param.args)) param.result = null
                        }
                    })
                }
            }
        }
    }

    private fun enabled(owner: Any?, args: Array<out Any?>): Boolean {
        return false
    }

    private fun readField(owner: Any, name: String): Any? = runCatching { findField(owner.javaClass, name)?.get(owner) }.getOrNull()

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) return field.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    companion object {
        private val watcherInstalled = AtomicBoolean(false)
        private val installedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val classNames = setOf(
            "com.vivo.musicmixcard.CCMUiPlugin",
            "com.vivo.musicmixcard.utils.k"
        )
    }
}

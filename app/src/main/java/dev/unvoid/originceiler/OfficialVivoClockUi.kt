package dev.unvoid.originceiler

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Process
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import dalvik.system.DexClassLoader
import java.io.File

class OfficialVivoClockUi(host: Context) : ContextWrapper(host) {
    private val clockResources: Resources
    private val clockTheme: Resources.Theme
    private val clockClassLoader: ClassLoader

    init {
        val apk: File = LockscreenClockRuntime.vivoLayouts(host)
        val path = apk.path
        var loader: ClassLoader? = null

        synchronized(OfficialVivoClockUi::class.java) {
            if (path != sharedPath || sharedLoader == null) {
                sharedLoader = DexClassLoader(
                    path,
                    host.codeCacheDir.path,
                    null,
                    host.classLoader
                )
                sharedPath = path
            }
            loader = sharedLoader
        }

        var res: Resources? = runCatching {
            val info = ApplicationInfo().apply {
                packageName = PACKAGE
                uid = Process.myUid()
                sourceDir = path
                publicSourceDir = path
                targetSdkVersion = 35
            }
            host.packageManager.getResourcesForApplication(info)
        }.getOrNull()

        if (res == null || res.getIdentifier("view_time_s12", "layout", PACKAGE) == 0) {
            val assetManager = AssetManager::class.java.getConstructor().newInstance()
            val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assetManager, path)
            res = Resources(assetManager, host.resources.displayMetrics, host.resources.configuration)
        }

        clockResources = res
        clockClassLoader = loader ?: host.classLoader
        clockTheme = clockResources.newTheme().apply {
            applyStyle(android.R.style.Theme_Material_NoActionBar, true)
        }
    }

    fun inflate(group: Int, factory: LayoutInflater.Factory2): View {
        val layoutGroup = if (group == 4) 2 else group
        val layoutId = clockResources.getIdentifier("view_time_s$layoutGroup", "layout", PACKAGE)
        if (layoutId == 0) {
            throw Resources.NotFoundException("Original Vivo clock layout for group $group (id: view_time_s$layoutGroup)")
        }
        val inflater = LayoutInflater.from(baseContext).cloneInContext(this)
        inflater.factory2 = factory
        return inflater.inflate(layoutId, null, false)
    }

    fun create(name: String, attrs: AttributeSet): View? {
        if (name.endsWith("ConstraintLayout")) {
            return runCatching {
                val clazz = clockClassLoader.loadClass("androidx.constraintlayout.widget.ConstraintLayout")
                val constructor = clazz.getConstructor(Context::class.java, AttributeSet::class.java)
                constructor.newInstance(this, attrs) as View
            }.getOrElse {
                val clazz = Class.forName("androidx.constraintlayout.widget.ConstraintLayout")
                clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(this, attrs) as View
            }
        }
        if (name.endsWith("Relativelayout") || name.endsWith("RelativeLayout")) {
            return android.widget.RelativeLayout(this, attrs)
        }
        if (name.endsWith("FrameLayout")) {
            return FrameLayout(this, attrs)
        }
        if (name.startsWith("com.vivo.") && (name.endsWith("TextView") || name.endsWith("InfoWidgetContainer"))) {
            return TextView(this, attrs)
        }
        return null
    }

    override fun getResources(): Resources = clockResources
    override fun getAssets(): AssetManager = clockResources.assets
    override fun getTheme(): Resources.Theme = clockTheme
    override fun getClassLoader(): ClassLoader = clockClassLoader

    companion object {
        const val PACKAGE = "com.vivo.systemuiplugin"
        @Volatile private var sharedPath: String? = null
        @Volatile private var sharedLoader: ClassLoader? = null
    }
}

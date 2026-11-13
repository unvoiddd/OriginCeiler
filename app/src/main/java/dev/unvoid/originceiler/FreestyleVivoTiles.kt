package dev.unvoid.originceiler

import android.graphics.drawable.Drawable
import android.view.ViewGroup
import java.lang.reflect.Proxy

/**
 * The tiles of vivo's own "add tiles" menu: asks vivo's VivoTileQueryHelper (the helper behind its
 * customizer) for every available tile, with its real icon and name, exactly like vivo's menu.
 */
internal class FreestyleVivoTiles(private val panel: ViewGroup, private val onChange: () -> Unit) {
    private val engine = FreestyleControlCenter
    val icons = HashMap<String, Drawable>()
    val labels = HashMap<String, String>()
    /** Specs vivo offers as available system tiles (only known once [systemDone]). */
    val available = LinkedHashSet<String>()
    var systemDone = false
        private set

    private var helper: Any? = null
    private var listener: Any? = null

    fun start() {
        runCatching {
            val cl = panel.javaClass.classLoader ?: return
            val helperClass = Class.forName("com.vivo.systemuiplugin.android.systemui.qs.customize.VivoTileQueryHelper", false, cl)
            val dependency = Class.forName("com.vivo.systemuiplugin.LocalDependency", false, cl)
            val get = dependency.methods.first { it.name == "get" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Class::class.java }
            val h = runCatching { get.invoke(null, helperClass) }.getOrNull() ?: run {
                val managerClass = Class.forName("com.vivo.systemuiplugin.android.systemui.qs.VivoQsHostManagerInterface", false, cl)
                val manager = get.invoke(null, managerClass)
                helperClass.constructors.first { it.parameterTypes.size == 2 }.newInstance(panel.context, manager)
            } ?: return
            val host = engine.findField(panel.javaClass, "mHost")?.get(panel) ?: return
            val iface = Class.forName(helperClass.name + "\$TileStateListener", false, cl)
            var proxy: Any? = null
            proxy = Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
                when (method.name) {
                    "onTilesChanged" -> { (args?.getOrNull(0) as? List<*>)?.let(::read); null }
                    "updateItem" -> { (args?.getOrNull(0) as? String)?.let { spec -> args.getOrNull(1)?.let { readState(spec, it) } }; onChange(); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    "toString" -> "FreestyleGalleryListener"
                    else -> null
                }
            }
            helperClass.getMethod("addListener", iface).invoke(h, proxy)
            helper = h
            listener = proxy
            helperClass.methods.first { it.name == "queryTiles" && it.parameterTypes.size == 1 }.invoke(h, host)
        }.onFailure { engine.log("vivo tiles: $it") }
    }

    fun stop() {
        val h = helper ?: return
        runCatching {
            val l = listener
            if (l != null) h.javaClass.methods.firstOrNull { it.name == "removeListener" }?.invoke(h, l)
            h.javaClass.getMethod("removeAllTileCallbacksAndClearTiles").invoke(h)
        }.onFailure { engine.log("vivo tiles stop: $it") }
        helper = null
        listener = null
    }

    private fun read(list: List<*>) {
        for (info in list) {
            info ?: continue
            val spec = engine.findField(info.javaClass, "spec")?.get(info) as? String ?: continue
            val system = engine.findField(info.javaClass, "isSystem")?.getBoolean(info) ?: false
            if (system) available += spec
            engine.findField(info.javaClass, "state")?.get(info)?.let { readState(spec, it) }
        }
        systemDone = systemDone || runCatching { helper?.javaClass?.getMethod("isSystemTileFinished")?.invoke(helper) as? Boolean }.getOrNull() == true
        onChange()
    }

    private fun readState(spec: String, state: Any) {
        (engine.findField(state.javaClass, "label")?.get(state) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }?.let { labels[spec] = it }
        var icon = engine.findField(state.javaClass, "icon")?.get(state)
        if (icon == null) {
            val supplier = engine.findField(state.javaClass, "iconSupplier")?.get(state)
            icon = runCatching { supplier?.javaClass?.getMethod("get")?.invoke(supplier) }.getOrNull()
        }
        val drawable = icon?.let { i ->
            runCatching { i.javaClass.methods.first { it.name == "getDrawable" && it.parameterTypes.size == 1 }.invoke(i, panel.context) as? Drawable }.getOrNull()
        }
        if (drawable != null) icons[spec] = drawable.constantState?.newDrawable()?.mutate() ?: drawable
    }
}

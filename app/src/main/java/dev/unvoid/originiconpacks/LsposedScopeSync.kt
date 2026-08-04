package com.autonavi.minimap

object LsposedScopeSync {
    private const val modulePackage = "com.autonavi.minimap"

    private val packages = listOf(
        "system",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.bbk.launcher2",
        "com.bbk.theme",
        "com.google.android.googlequicksearchbox",
        "com.iqoo.powersaving",
        "com.vivo.ai.copilot",
        "com.vivo.gamecube",
        "com.vivo.launchercopilot",
        "com.vivo.musicmixcard",
        "com.vivo.musicwidgetmix",
        "com.vivo.pay",
        "com.vivo.systemuiplugin",
        "com.vivo.upslide"
    )

    fun sync(): Boolean {
        val inserts = packages.joinToString(" ") { packageName ->
            "INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) " +
                "SELECT module_pkg_name,'$packageName',user_id FROM modules_state " +
                "WHERE module_pkg_name='$modulePackage' AND enabled=1;"
        }
        val script = """
            LSPD_PID=${'$'}(pidof lspd | awk '{print ${'$'}1}')
            [ -n "${'$'}LSPD_PID" ] || exit 0
            DB=/proc/${'$'}LSPD_PID/root/data/adb/lspd/config/modules_config.db
            [ -f "${'$'}DB" ] || exit 0
            sqlite3 "${'$'}DB" "PRAGMA busy_timeout=5000; BEGIN IMMEDIATE; $inserts COMMIT;"
        """.trimIndent()
        return runCatching {
            ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.bufferedReader().readText() }
                .waitFor() == 0
        }.getOrDefault(false)
    }
}

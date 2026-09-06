package dev.unvoid.originceiler

import android.app.Application

class OriginIconsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread { LsposedScopeSync.sync(applicationInfo.sourceDir) }.start()
        val preferences = getSharedPreferences("module_runtime", 0)
        if (preferences.getInt("version_code", -1) == BuildConfig.VERSION_CODE) return
        preferences.edit().putInt("version_code", BuildConfig.VERSION_CODE).apply()
        Thread {
            runCatching {
                ProcessBuilder(
                    "su",
                    "-c",
                    "settings put global originroottoolbox_originos7_control_center_editor 0; settings delete global originroottoolbox_material_originos_control_center; LOCKSCREEN_BACKUP=\$(settings get global originicons_lockscreen_unseen_backup); case \"\$LOCKSCREEN_BACKUP\" in 0|1) settings put secure lock_screen_show_only_unseen_notifications \"\$LOCKSCREEN_BACKUP\";; esac; settings delete global originicons_lockscreen_unseen_backup; settings delete global originicons_notification_always_lockscreen; am force-stop com.vivo.ai.copilot; killall com.vivo.ai.copilot 2>/dev/null || true; am force-stop com.vivo.launchercopilot; killall com.vivo.launchercopilot 2>/dev/null || true"
                ).redirectErrorStream(true).start().apply {
                    inputStream.bufferedReader().readText()
                    waitFor()
                }
            }
        }.start()
    }
}

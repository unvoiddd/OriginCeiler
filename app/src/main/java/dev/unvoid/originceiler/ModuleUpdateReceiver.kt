package dev.unvoid.originceiler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ModuleUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageEvent = intent.action in setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED
        )
        if (packageEvent && intent.data?.schemeSpecificPart != "com.android.packageinstaller") return
        if (!packageEvent && intent.action !in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) return
        val pending = goAsync()
        Thread {
            if (packageEvent) {
                RootThemeInstaller(context).removePackageInstallerIfEnabled()
                pending.finish()
                return@Thread
            }
            LsposedScopeSync.sync(context.applicationInfo.sourceDir)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                RootThemeInstaller(context).restoreGoogleAssistantIfEnabled()
                RootThemeInstaller(context).removePackageInstallerIfEnabled()
                pending.finish()
                return@Thread
            }
            runCatching {
                ProcessBuilder(
                    "su",
                    "-c",
                    "LOCKSCREEN_BACKUP=\$(settings get global originicons_lockscreen_unseen_backup); case \"\$LOCKSCREEN_BACKUP\" in 0|1) settings put secure lock_screen_show_only_unseen_notifications \"\$LOCKSCREEN_BACKUP\";; esac; settings delete global originicons_lockscreen_unseen_backup; settings delete global originicons_notification_always_lockscreen; am force-stop com.vivo.ai.copilot; killall com.vivo.ai.copilot 2>/dev/null || true; am force-stop com.vivo.launchercopilot; killall com.vivo.launchercopilot 2>/dev/null || true"
                ).redirectErrorStream(true).start().apply {
                    inputStream.bufferedReader().readText()
                    waitFor()
                }
            }
            pending.finish()
        }.start()
    }

}

package com.autonavi.minimap

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val command = intent.getIntExtra(EXTRA_COMMAND, 0)
        val component = ComponentName(context, OriginIslandNotificationListener::class.java)
        val controller = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(component)
                .firstOrNull { it.packageName == packageName }
        }.getOrNull() ?: return
        val controls = controller.transportControls
        when (command) {
            COMMAND_PREVIOUS -> controls.skipToPrevious()
            COMMAND_PLAY_PAUSE -> {
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) controls.pause() else controls.play()
                OriginIslandNotificationListener.requestMediaStateRefresh(packageName)
            }
            COMMAND_NEXT -> controls.skipToNext()
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.autonavi.minimap.MEDIA_CONTROL"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_COMMAND = "command"
        const val COMMAND_PREVIOUS = 1
        const val COMMAND_PLAY_PAUSE = 2
        const val COMMAND_NEXT = 3
    }
}

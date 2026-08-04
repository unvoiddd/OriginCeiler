package com.autonavi.minimap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import java.lang.ref.WeakReference

class OriginIslandNotificationListener : NotificationListenerService() {
    private val casts = linkedMapOf<String, Int>()
    private val changeRecords = linkedMapOf<String, Int>()
    private val states = linkedMapOf<String, CastState>()
    private val expiryCallbacks = linkedMapOf<String, Runnable>()
    private val mediaControllers = linkedMapOf<String, TrackedMedia>()
    private val mediaSnapshots = linkedMapOf<String, MediaSnapshot>()
    private val mediaEndCallbacks = linkedMapOf<String, Runnable>()
    private val mediaRebuildCallbacks = linkedMapOf<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener(::syncMediaControllers)
    private val mediaTicker = object : Runnable {
        override fun run() {
            mediaControllers.keys.toList().forEach { refreshMediaPackage(it, false) }
            scheduleMediaTicker()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = WeakReference(this)
        val notifications = activeNotifications?.toList().orEmpty()
        val sources = notifications.filter { it.packageName != packageName }
        val recasts = notifications.filter { it.packageName == packageName }
        stopMediaSessions()
        clearCastState()
        startMediaSessions()
        recasts.forEach {
            if (it.tag != null) notificationManager().cancel(it.tag, it.id) else notificationManager().cancel(it.id)
        }
        sources.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isEnabled() || sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val media = mediaSnapshots[sbn.packageName]
        val selected = isSelected(sbn.packageName)
        val captureMedia = captureAllMedia() && isMediaNotification(sbn.notification, media)
        val captureProgress = captureAllProgress() && isProgressNotification(sbn.notification)
        if (!selected && !captureMedia && !captureProgress) return
        val notificationTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val notificationText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val title = media?.title?.takeIf { it.isNotBlank() } ?: notificationTitle
        val text = media?.artist?.takeIf { it.isNotBlank() } ?: notificationText
        if (title.isEmpty() && text.isEmpty()) return
        val operation = if (casts.containsKey(sbn.key)) 1 else 0
        val id = 20000 + (sbn.key.hashCode() and 0x7FFFFFFF) % 10000
        casts[sbn.key] = id
        val changeRecord = (changeRecords[sbn.key] ?: 0) + 1
        changeRecords[sbn.key] = changeRecord
        val finalTitle = title.ifEmpty { appName(sbn.packageName) }
        states[sbn.key] = CastState(sbn, id, finalTitle, text, isMediaNotification(sbn.notification, media))
        notifyCast(sbn, id, operation, changeRecord, finalTitle, text, media)
        scheduleExpiry(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        finishCast(sbn.key)
    }

    override fun onDestroy() {
        if (activeService?.get() === this) activeService = null
        stopMediaSessions()
        clearCastState()
        super.onDestroy()
    }

    private fun notifyCast(
        sbn: StatusBarNotification,
        id: Int,
        operation: Int,
        changeRecord: Int,
        title: String,
        text: String,
        media: MediaSnapshot? = mediaSnapshots[sbn.packageName]
    ) {
        ensureChannel()
        val source = sbn.notification
        val appName = appName(sbn.packageName)
        val icon = resolvePlatformIcon(source)
        val clickIntent = resolveClickIntent(sbn, id)
        val accent = media?.accent ?: resolveAccent()
        val sourceProgress = source.extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val sourceProgressMax = source.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = if (sourceProgressMax > 0) sourceProgress else media?.progress ?: 0
        val progressMax = if (sourceProgressMax > 0) sourceProgressMax else if ((media?.duration ?: 0) > 0) 100 else 0
        val indeterminate = source.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val progressPercent = if (progressMax > 0) (progress * 100 / progressMax).coerceIn(0, 100) else 0
        val imageIcon = media?.icon ?: source.getLargeIcon() ?: icon
        val actionPresentations = source.actions.orEmpty().mapIndexedNotNull { index, action ->
            action.actionIntent?.let {
                ActionPresentation(index, action, resolveActionIcon(action), !action.remoteInputs.isNullOrEmpty())
            }
        }
        val actionTitles = actionPresentations.map { it.action.title?.toString().orEmpty().lowercase() }
        val actionIndicatesPlaying = actionTitles.any { it.contains("pause") || it.contains("пауз") }
        val isMedia = isMediaNotification(source, media)
        val directActions = actionPresentations.filterNot { it.remoteInput }
        val compactIndices = source.extras.getIntArray("android.compactActions")?.toList().orEmpty()
        val sourceCardActions = if (isMedia && compactIndices.isNotEmpty()) {
            (compactIndices.mapNotNull { index -> directActions.firstOrNull { it.index == index } } + directActions)
                .distinctBy { it.index }
                .take(3)
        } else {
            directActions.take(3)
        }
        val cardActions = if (media?.controllable == true) {
            mediaIslandActions(sbn.packageName, media.playing, id)
        } else {
            sourceCardActions.map {
                IslandAction(
                    icon = it.icon,
                    clickIntent = it.action.actionIntent,
                    title = it.action.title?.toString().orEmpty()
                )
            }
        }
        val template = when {
            isMedia || cardActions.isNotEmpty() || indeterminate -> 4
            progressMax > 0 -> 2
            else -> 1
        }

        val baseInfos = Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", if (isMedia) imageIcon else icon)
            putCharSequence("notification.superx.baseInfos.title", title)
            putCharSequence("notification.superx.baseInfos.content", text.ifEmpty { appName })
            when {
                cardActions.isNotEmpty() -> {
                    putInt("notification.superx.baseInfos.subInfo", 4)
                    putParcelableArrayList("notification.superx.baseInfos.subImageList", ArrayList(cardActions.map { it.icon }))
                    putParcelableArrayList(
                        "notification.superx.baseInfos.subInfoClickRespList",
                        ArrayList(cardActions.map { it.clickIntent })
                    )
                }
                progressMax > 0 -> {
                    putInt("notification.superx.baseInfos.subInfo", 5)
                    putInt("notification.superx.baseInfos.subProgress", progressPercent)
                    putInt("notification.superx.baseInfos.subProgressColor", accent)
                    putInt("notification.superx.baseInfos.subProgressBgColor", Color.argb(55, 255, 255, 255))
                    putInt("notification.superx.baseInfos.progressState", if (progressPercent >= 100) 1 else 0)
                    putCharSequence(
                        "notification.superx.baseInfos.progressContent",
                        media?.let { "${formatDuration(it.position)} / ${formatDuration(it.duration)}" } ?: "$progressPercent%"
                    )
                }
                indeterminate -> {
                    putInt("notification.superx.baseInfos.subInfo", 6)
                    putInt("notification.superx.baseInfos.loadingColor", accent)
                }
                else -> putInt("notification.superx.baseInfos.subInfo", 0)
            }
        }

        val infos = Bundle().apply {
            if (template == 2) {
                putParcelableArrayList("notification.superx.infos.nodeIcon", arrayListOf(icon, imageIcon))
                putInt("notification.superx.infos.progress", progressPercent)
                putParcelable("notification.superx.infos.indicatorIcon", imageIcon)
                putInt("notification.superx.infos.indicatorLoc", 1)
                putInt("notification.superx.infos.progressColor", accent)
                putInt("notification.superx.infos.BgColor", Color.argb(55, 255, 255, 255))
                putCharSequence(
                    "notification.superx.infos.progressContent",
                    media?.let { "${formatDuration(it.position)} / ${formatDuration(it.duration)}" } ?: "$progressPercent%"
                )
            } else if (template == 1) {
                putString("notification.superx.infos.describe", text.ifEmpty { appName })
                putString("notification.superx.infos.coreInfo", title)
                putParcelable("notification.superx.infos.image", imageIcon)
                putParcelable("notification.superx.infos.imageClickResp", clickIntent)
            }
        }

        val shortInfos = Bundle().apply {
            putParcelable("notification.superx.shortInfos.icon", icon)
            putParcelable("notification.superx.shortInfos.image", icon)
            putParcelable("notification.superx.shortInfos.imageClickResp", clickIntent)
            putParcelable("notification.superx.shortInfos.OriginBImage", icon)
            putString("notification.superx.shortInfos.describeShort", text.ifEmpty { appName })
            putString("notification.superx.shortInfos.coreInfoShort", title)
        }

        val capsule = Bundle().apply {
            putInt("notification.superx.capsule.state", 1)
            putParcelable("notification.superx.capsule.icon", icon)
            putString("notification.superx.capsule.content", compactText(title, text))
            putInt("notification.superx.capsule.contentColor", Color.WHITE)
            putInt("notification.superx.capsule.bgColor", accent)
            putParcelable("notification.superx.capsule.clickResp", clickIntent)
        }

        val leftInfo = Bundle().apply {
            putParcelable("island.superx.leftInfo.icon", icon)
            putCharSequence("island.superx.leftInfo.content", if (isMedia) title.take(32) else appName.take(12))
        }

        val rightInfo = Bundle().apply {
            if (isMedia) {
                putIntegerArrayList("island.superx.rightInfo.waveColor", arrayListOf(accent, Color.WHITE))
                putInt("island.superx.rightInfo.waveState", if (media?.playing == true || actionIndicatesPlaying || source.flags and Notification.FLAG_ONGOING_EVENT != 0) 1 else 0)
            } else if (progressMax > 0) {
                putInt("island.superx.rightInfo.progressValue", progressPercent)
                putInt("island.superx.rightInfo.progressColor", accent)
                putInt("island.superx.rightInfo.progressBgColor", Color.argb(90, 255, 255, 255))
                putInt("island.superx.rightInfo.progressState", if (progressPercent >= 100) 1 else 0)
            } else if (indeterminate) {
                putInt("island.superx.rightInfo.loadingColor", accent)
            } else if (cardActions.isNotEmpty()) {
                val action = cardActions[0]
                putCharSequence("island.superx.rightInfo.capsuleContent", action.title.take(10))
                putInt("island.superx.rightInfo.capsuleBgColor", accent)
                putParcelable("island.superx.rightInfo.clickResp", action.clickIntent)
            } else {
                putParcelable("island.superx.rightInfo.icon", imageIcon)
                putCharSequence("island.superx.rightInfo.content", compactText(title, text))
                putParcelable("island.superx.rightInfo.clickResp", clickIntent)
            }
        }

        val rightTemplate = when {
            isMedia -> 1
            progressMax > 0 -> 2
            indeterminate -> 3
            cardActions.isNotEmpty() -> 6
            else -> 4
        }

        val island = Bundle().apply {
            putInt("island.superx.leftTemplate", 1)
            putBundle("island.superx.leftInfo", leftInfo)
            putInt("island.superx.rightTemplate", rightTemplate)
            putBundle("island.superx.rightInfo", rightInfo)
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", clickIntent)
            putInt("island.superx.template", template)
            putBundle("island.superx.baseInfos", baseInfos)
            putBundle("island.superx.infos", infos)
        }

        val superX = Bundle().apply {
            putInt("notification.superx.operation", operation)
            putBoolean("notification.superx.showNotify", false)
            putInt("notification.superx.template", template)
            putBundle("notification.superx.baseInfos", baseInfos)
            putBundle("notification.superx.infos", infos)
            putBundle("notification.superx.shortInfos", shortInfos)
            putBundle("notification.superx.capsule", capsule)
            putBundle("notification.superx.island", island)
            putParcelable("notification.superx.clickResp", clickIntent)
            putString("notification.superx.scene", "NAVIGATION")
            putInt("notification.superx.changedRecord", changeRecord)
            putInt("notification.superx.newNode", changeRecord)
            putInt("notification.superx.displays", 0x111)
            putBoolean("notification.superx.sound", false)
            putBoolean("notification.superx.dismissWhenKill", false)
            putBoolean(EXTRA_RECAST, true)
            putString(EXTRA_SOURCE_PACKAGE, sbn.packageName)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { appName })
            .setSubText(appName)
            .setSmallIcon(resolveCompatIcon(source))
            .setColor(accent)
            .setOngoing(source.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .setOnlyAlertOnce(operation == 1)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(source.`when`.takeIf { it > 0 } ?: sbn.postTime)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(source.category ?: NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(clickIntent)
            .addExtras(superX)

        if (progressMax > 0) builder.setProgress(progressMax, progress, false)
        repeat(NotificationCompat.getActionCount(source)) { index ->
            NotificationCompat.getAction(source, index)?.let(builder::addAction)
        }
        if (isMedia && media?.token != null && sourceCardActions.isNotEmpty()) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(media.token)
                    .setShowActionsInCompactView(*sourceCardActions.map { it.index }.toIntArray())
            )
        }
        notificationManager().notify(VIVO_SUPERX_TAG, id, builder.build())
    }

    private fun mediaIslandActions(packageName: String, playing: Boolean, notificationId: Int): List<IslandAction> {
        fun action(command: Int, iconResource: Int, title: String): IslandAction {
            val intent = Intent(this, MediaControlReceiver::class.java).apply {
                action = MediaControlReceiver.ACTION_CONTROL
                putExtra(MediaControlReceiver.EXTRA_PACKAGE, packageName)
                putExtra(MediaControlReceiver.EXTRA_COMMAND, command)
            }
            val clickIntent = PendingIntent.getBroadcast(
                this,
                notificationId * 10 + command,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return IslandAction(Icon.createWithResource(this, iconResource), clickIntent, title)
        }
        return listOf(
            action(MediaControlReceiver.COMMAND_PREVIOUS, R.drawable.ic_media_previous, "Previous"),
            action(
                MediaControlReceiver.COMMAND_PLAY_PAUSE,
                if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                if (playing) "Pause" else "Play"
            ),
            action(MediaControlReceiver.COMMAND_NEXT, R.drawable.ic_media_next, "Next")
        )
    }

    private fun resolveActionIcon(action: Notification.Action): Icon {
        action.getIcon()?.let { return it }
        val title = action.title?.toString().orEmpty().lowercase()
        val resource = when {
            title.contains("previous") || title.contains("prev") || title.contains("предыдущ") || title.contains("назад") -> R.drawable.ic_media_previous
            title.contains("next") || title.contains("следующ") || title.contains("вперёд") || title.contains("вперед") -> R.drawable.ic_media_next
            title.contains("pause") || title.contains("пауза") || title.contains("приостанов") -> R.drawable.ic_media_pause
            title.contains("play") || title.contains("воспроиз") || title.contains("слушать") -> R.drawable.ic_media_play
            title.contains("stop") || title.contains("останов") -> R.drawable.ic_media_stop
            !action.remoteInputs.isNullOrEmpty() || title.contains("reply") || title.contains("ответ") -> R.drawable.ic_action_reply
            title.contains("read") || title.contains("прочит") || title.contains("done") || title.contains("готов") -> R.drawable.ic_action_done
            else -> R.drawable.ic_action_open
        }
        return Icon.createWithResource(this, resource)
    }

    private fun isMediaNotification(notification: Notification, media: MediaSnapshot?): Boolean {
        if (media != null || notification.category == Notification.CATEGORY_TRANSPORT || notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        return notification.actions.orEmpty().any { action ->
            val title = action.title?.toString().orEmpty().lowercase()
            title.contains("play") || title.contains("pause") || title.contains("воспроиз") || title.contains("пауз")
        }
    }

    private fun isProgressNotification(notification: Notification): Boolean {
        return notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
    }

    private fun scheduleExpiry(sbn: StatusBarNotification) {
        expiryCallbacks.remove(sbn.key)?.let(handler::removeCallbacks)
        val source = sbn.notification
        val progress = source.extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val progressMax = source.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = source.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val delay = when {
            progressMax > 0 && progress >= progressMax -> 2_500L
            progressMax > 0 || indeterminate -> 15_000L
            source.flags and Notification.FLAG_ONGOING_EVENT == 0 -> 45_000L
            source.category == Notification.CATEGORY_TRANSPORT -> 600_000L
            else -> 120_000L
        }
        val callback = Runnable {
            if (activeNotifications?.any { it.key == sbn.key } == true) {
                scheduleExpiry(sbn)
            } else {
                finishCast(sbn.key)
            }
        }
        expiryCallbacks[sbn.key] = callback
        handler.postDelayed(callback, delay)
    }

    private fun finishCast(key: String) {
        expiryCallbacks.remove(key)?.let(handler::removeCallbacks)
        mediaRebuildCallbacks.remove(key)?.let(handler::removeCallbacks)
        val state = states.remove(key)
        val id = casts.remove(key) ?: state?.id ?: return
        val changeRecord = (changeRecords.remove(key) ?: 0) + 1
        if (state != null) {
            notifyCast(state.sbn, id, 2, changeRecord, state.title, state.text)
            handler.postDelayed({
                if (!casts.containsKey(key)) {
                    notificationManager().cancel(VIVO_SUPERX_TAG, id)
                    notificationManager().cancel(id)
                }
            }, 800L)
        } else {
            notificationManager().cancel(VIVO_SUPERX_TAG, id)
            notificationManager().cancel(id)
        }
    }

    private fun clearCastState() {
        expiryCallbacks.values.forEach(handler::removeCallbacks)
        mediaRebuildCallbacks.values.forEach(handler::removeCallbacks)
        expiryCallbacks.clear()
        mediaRebuildCallbacks.clear()
        states.clear()
        casts.clear()
        changeRecords.clear()
    }

    private fun startMediaSessions() {
        val component = ComponentName(this, OriginIslandNotificationListener::class.java)
        val manager = getSystemService(MediaSessionManager::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component, handler)
            syncMediaControllers(manager.getActiveSessions(component))
        }
    }

    private fun stopMediaSessions() {
        handler.removeCallbacks(mediaTicker)
        mediaEndCallbacks.values.forEach(handler::removeCallbacks)
        mediaEndCallbacks.clear()
        runCatching {
            getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        mediaControllers.values.forEach { tracked ->
            runCatching { tracked.controller.unregisterCallback(tracked.callback) }
        }
        mediaControllers.clear()
        mediaSnapshots.clear()
    }

    private fun syncMediaControllers(controllers: List<MediaController>?) {
        val previousPackages = mediaControllers.keys.toSet()
        mediaControllers.values.forEach { tracked ->
            runCatching { tracked.controller.unregisterCallback(tracked.callback) }
        }
        mediaControllers.clear()
        controllers.orEmpty()
            .filter { isSelected(it.packageName) || captureAllMedia() }
            .groupBy { it.packageName }
            .forEach { (packageName, sessions) ->
                val controller = sessions.maxByOrNull {
                    if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0
                } ?: return@forEach
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        refreshMediaPackage(packageName)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state == null || state.state == PlaybackState.STATE_NONE || state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_ERROR) {
                            scheduleMediaEnd(packageName)
                        } else {
                            cancelMediaEnd(packageName)
                            refreshMediaPackage(packageName)
                        }
                    }

                    override fun onSessionDestroyed() {
                        scheduleMediaEnd(packageName)
                    }
                }
                runCatching { controller.registerCallback(callback, handler) }
                mediaControllers[packageName] = TrackedMedia(controller, callback)
            }
        val currentPackages = mediaControllers.keys.toSet()
        (previousPackages - currentPackages).forEach(::scheduleMediaEnd)
        currentPackages.forEach { packageName ->
            val state = mediaControllers[packageName]?.controller?.playbackState?.state
            if (state == null || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) {
                scheduleMediaEnd(packageName)
            } else {
                cancelMediaEnd(packageName)
                refreshMediaPackage(packageName)
            }
        }
        scheduleMediaTicker()
    }

    private fun scheduleMediaEnd(packageName: String) {
        mediaEndCallbacks.remove(packageName)?.let(handler::removeCallbacks)
        val callback = Runnable {
            mediaEndCallbacks.remove(packageName)
            val active = runCatching {
                val component = ComponentName(this, OriginIslandNotificationListener::class.java)
                getSystemService(MediaSessionManager::class.java).getActiveSessions(component).any { controller ->
                    if (controller.packageName != packageName) return@any false
                    val state = controller.playbackState?.state
                    state != null && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                }
            }.getOrDefault(false)
            if (active) {
                cancelMediaEnd(packageName)
                refreshMediaPackage(packageName)
            } else {
                mediaSnapshots.remove(packageName)
                finishMediaCasts(packageName)
            }
        }
        mediaEndCallbacks[packageName] = callback
        handler.postDelayed(callback, 1500L)
    }

    private fun cancelMediaEnd(packageName: String) {
        mediaEndCallbacks.remove(packageName)?.let(handler::removeCallbacks)
    }

    private fun finishMediaCasts(packageName: String) {
        states.filterValues { it.sbn.packageName == packageName && it.media }.keys.toList().forEach(::finishCast)
    }

    private fun refreshMediaPackage(packageName: String, scheduleTicker: Boolean = true) {
        val snapshot = mediaControllers[packageName]?.controller?.let(::mediaSnapshot)
        val previous = mediaSnapshots[packageName]
        if (snapshot == null) {
            mediaSnapshots.remove(packageName)
            if (previous != null) scheduleMediaEnd(packageName)
            if (scheduleTicker) scheduleMediaTicker()
            return
        }
        mediaSnapshots[packageName] = snapshot
        val playbackChanged = previous != null && previous.playing != snapshot.playing
        val changed = previous?.title != snapshot.title ||
            previous?.artist != snapshot.artist ||
            previous?.progress != snapshot.progress ||
            previous?.duration != snapshot.duration ||
            previous?.playing != snapshot.playing ||
            previous?.controllable != snapshot.controllable
        if (changed) {
            states.filterValues { it.sbn.packageName == packageName }.forEach { (key, state) ->
                if (playbackChanged && state.media) {
                    rebuildMediaCast(key, state)
                } else {
                    val changeRecord = (changeRecords[key] ?: 0) + 1
                    changeRecords[key] = changeRecord
                    notifyCast(state.sbn, state.id, 1, changeRecord, snapshot.title.ifEmpty { state.title }, snapshot.artist.ifEmpty { state.text }, snapshot)
                }
            }
        }
        if (scheduleTicker) scheduleMediaTicker()
    }

    private fun rebuildMediaCast(key: String, state: CastState) {
        mediaRebuildCallbacks.remove(key)?.let(handler::removeCallbacks)
        val endRecord = (changeRecords[key] ?: 0) + 1
        changeRecords[key] = endRecord
        notifyCast(state.sbn, state.id, 2, endRecord, state.title, state.text, mediaSnapshots[state.sbn.packageName])
        val callback = Runnable {
            mediaRebuildCallbacks.remove(key)
            val currentState = states[key] ?: return@Runnable
            val snapshot = mediaSnapshots[currentState.sbn.packageName] ?: return@Runnable
            notificationManager().cancel(VIVO_SUPERX_TAG, currentState.id)
            val createRecord = (changeRecords[key] ?: 0) + 1
            changeRecords[key] = createRecord
            notifyCast(
                currentState.sbn,
                currentState.id,
                0,
                createRecord,
                snapshot.title.ifEmpty { currentState.title },
                snapshot.artist.ifEmpty { currentState.text },
                snapshot
            )
        }
        mediaRebuildCallbacks[key] = callback
        handler.postDelayed(callback, 180L)
    }

    private fun scheduleMediaTicker() {
        handler.removeCallbacks(mediaTicker)
        if (mediaControllers.values.any { it.controller.playbackState?.state == PlaybackState.STATE_PLAYING }) {
            handler.postDelayed(mediaTicker, 1000L)
        }
    }

    private fun mediaSnapshot(controller: MediaController): MediaSnapshot? {
        val metadata = controller.metadata
        val playback = controller.playbackState
        if (playback == null || playback.state == PlaybackState.STATE_NONE || playback.state == PlaybackState.STATE_STOPPED || playback.state == PlaybackState.STATE_ERROR) return null
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: appName(controller.packageName)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: appName(controller.packageName)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val elapsed = if (playback?.state == PlaybackState.STATE_PLAYING) {
            ((android.os.SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0L) * playback.playbackSpeed).toLong()
        } else {
            0L
        }
        val position = ((playback?.position ?: 0L) + elapsed).coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val progress = if (duration > 0L) ((position * 100L) / duration).toInt().coerceIn(0, 100) else 0
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        return MediaSnapshot(
            title = title,
            artist = artist,
            progress = progress,
            position = position,
            duration = duration,
            playing = playback?.state == PlaybackState.STATE_PLAYING,
            controllable = (playback?.actions ?: 0L) != 0L ||
                playback?.state == PlaybackState.STATE_PLAYING ||
                playback?.state == PlaybackState.STATE_PAUSED ||
                duration > 0L,
            icon = bitmap?.let(Icon::createWithBitmap),
            accent = bitmap?.let(::mediaAccent),
            token = MediaSessionCompat.Token.fromToken(controller.sessionToken)
        )
    }

    private fun mediaAccent(bitmap: Bitmap): Int = runCatching {
        var selected = resolveAccent()
        var selectedScore = 0f
        val stepX = (bitmap.width / 14).coerceAtLeast(1)
        val stepY = (bitmap.height / 14).coerceAtLeast(1)
        val hsv = FloatArray(3)
        for (y in stepY / 2 until bitmap.height step stepY) {
            for (x in stepX / 2 until bitmap.width step stepX) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < 180) continue
                Color.colorToHSV(color, hsv)
                val score = hsv[1] * (1f - kotlin.math.abs(hsv[2] - 0.76f) * 0.65f)
                if (score > selectedScore) {
                    selectedScore = score
                    selected = color
                }
            }
        }
        Color.colorToHSV(selected, hsv)
        hsv[1] = hsv[1].coerceIn(0.58f, 0.92f)
        hsv[2] = hsv[2].coerceIn(0.68f, 0.9f)
        Color.HSVToColor(hsv)
    }.getOrElse { resolveAccent() }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private fun resolveClickIntent(sbn: StatusBarNotification, id: Int): PendingIntent {
        sbn.notification.contentIntent?.let { return it }
        val launchIntent = packageManager.getLaunchIntentForPackage(sbn.packageName)
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, id, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun resolvePlatformIcon(notification: Notification): Icon {
        notification.smallIcon?.let { return it }
        return Icon.createWithResource(this, R.drawable.app_icon)
    }

    private fun resolveCompatIcon(notification: Notification): IconCompat {
        return notification.smallIcon?.let { runCatching { IconCompat.createFromIcon(this, it) }.getOrNull() }
            ?: IconCompat.createWithResource(this, R.drawable.app_icon)
    }

    private fun compactText(title: String, text: String): String {
        return text.ifBlank { title }.replace('\n', ' ').take(16)
    }

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Navigation status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Live navigation status"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun resolveAccent(): Int {
        return if (Build.VERSION.SDK_INT >= 31) getColor(android.R.color.system_accent1_500) else Color.rgb(62, 117, 255)
    }

    private fun isEnabled(): Boolean = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("origin_island_enabled", false)

    private fun isSelected(packageName: String): Boolean {
        return getSharedPreferences("settings", MODE_PRIVATE).getStringSet("origin_island_apps", emptySet()).orEmpty().contains(packageName)
    }

    private fun captureAllMedia(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("capture_all_media", false) ||
            Settings.Global.getInt(contentResolver, "originicons_island_all_media", 0) == 1

    private fun captureAllProgress(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("capture_all_progress", false) ||
            Settings.Global.getInt(contentResolver, "originicons_island_all_progress", 0) == 1

    private fun appName(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "origin_island_navigation"
        const val VIVO_SUPERX_TAG = "VIVO_SUPERX_TAG"
        const val EXTRA_RECAST = "originicons.island.recast"
        const val EXTRA_SOURCE_PACKAGE = "originicons.island.source"
        private var activeService: WeakReference<OriginIslandNotificationListener>? = null

        fun requestMediaStateRefresh(packageName: String) {
            activeService?.get()?.let { service ->
                listOf(80L, 200L, 450L, 900L).forEach { delay ->
                    service.handler.postDelayed({ service.refreshMediaPackage(packageName) }, delay)
                }
            }
        }
    }

    private data class CastState(
        val sbn: StatusBarNotification,
        val id: Int,
        val title: String,
        val text: String,
        val media: Boolean
    )

    private data class ActionPresentation(
        val index: Int,
        val action: Notification.Action,
        val icon: Icon,
        val remoteInput: Boolean
    )

    private data class IslandAction(
        val icon: Icon,
        val clickIntent: PendingIntent,
        val title: String
    )

    private data class TrackedMedia(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private data class MediaSnapshot(
        val title: String,
        val artist: String,
        val progress: Int,
        val position: Long,
        val duration: Long,
        val playing: Boolean,
        val controllable: Boolean,
        val icon: Icon?,
        val accent: Int?,
        val token: MediaSessionCompat.Token
    )
}

package dev.unvoid.originceiler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Replaces the implementation behind BlueLM's own RecognitionService component.
 *
 * Clients still bind to CopilotRecognitionService and receive the original Android
 * RecognitionService.Callback. Only the recognizer used by that service is swapped.
 */
internal class BlueLmWhisperHook(private val classLoader: ClassLoader) {
    fun install() {
        installApplicationCapture()
        installSpeechGptEngineProxy()
        installOverlayTapFallback()
        installOverlayRootInputFallback()
        installOverlayInputReceiverFallback()
        installOverlayWindowCreationFallback()
        installBlueLmWindowLifecycleHook()
        installRecognitionServiceProxy()
    }

    private fun installOverlayTapFallback() {
        XposedBridge.hookAllMethods(View::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_UP) return
                val title = (view.rootView?.layoutParams as? WindowManager.LayoutParams)
                    ?.title?.toString().orEmpty()
                tryStartOverlayCapture(title)
            }
        })
        Log.i(LOG_TAG, "CopilotPanelWindow tap fallback installed")
    }

    private fun installOverlayRootInputFallback() {
        val viewRootClass = runCatching {
            Class.forName("android.view.ViewRootImpl")
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(viewRootClass, "dispatchInputEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_UP) return
                tryStartOverlayCapture(windowTitleForViewRoot(param.thisObject))
            }
        })
        Log.i(LOG_TAG, "CopilotPanelWindow root input fallback installed")
    }

    private fun installOverlayInputReceiverFallback() {
        val receiverClass = runCatching {
            Class.forName("android.view.ViewRootImpl\$WindowInputEventReceiver")
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(receiverClass, "onInputEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_UP) return
                val viewRoot = readField(param.thisObject, "this\$0") ?: return
                tryStartOverlayCapture(windowTitleForViewRoot(viewRoot))
            }
        })
        Log.i(LOG_TAG, "CopilotPanelWindow input receiver fallback installed")
    }

    private fun installOverlayWindowCreationFallback() {
        val windowManagerGlobalClass = runCatching {
            Class.forName("android.view.WindowManagerGlobal")
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(windowManagerGlobalClass, "addView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val attributes = param.args.firstOrNull {
                    it is WindowManager.LayoutParams
                } as? WindowManager.LayoutParams ?: return
                val title = attributes.title?.toString().orEmpty()
                if (!title.contains(COPILOT_PANEL_WINDOW)) return
                // On the first invocation SpeechGptEngine normally supplies ACTION_START.
                // On later invocations Vivo often only recreates the overlay window while
                // EngineCore remains in its completed state. Queue this after addView so a
                // normal ACTION_START wins when it exists; otherwise re-arm Whisper from the
                // last real engine request.
                mainHandler.post { tryStartOverlayCapture(title) }
            }
        })
        Log.i(LOG_TAG, "CopilotPanelWindow creation fallback installed")
    }

    private fun installBlueLmWindowLifecycleHook() {
        listOf(BASE_VWINDOW, VWINDOW_DECOR_VIEW, VOICE_MANAGER).forEach { name ->
            runCatching { Class.forName(name, false, classLoader) }
                .getOrNull()
                ?.let(::hookLoadedBlueLmClass)
        }

        // These classes live in later dex files and may not exist in the package loader
        // yet when the Xposed entry point runs. Hook them as soon as ART loads them.
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args.firstOrNull() as? String ?: return
                if (name != BASE_VWINDOW && name != VWINDOW_DECOR_VIEW && name != VOICE_MANAGER) return
                val loaded = param.result as? Class<*> ?: return
                hookLoadedBlueLmClass(loaded)
            }
        })
    }

    private fun hookLoadedBlueLmClass(loaded: Class<*>) {
        when (loaded.name) {
            BASE_VWINDOW -> hookBlueLmWindowClass(loaded)
            VWINDOW_DECOR_VIEW -> hookBlueLmDecorViewClass(loaded)
            VOICE_MANAGER -> hookBlueLmVoiceManagerClass(loaded)
        }
    }

    private fun hookBlueLmWindowClass(windowClass: Class<*>) {
        synchronized(hookedWindowClasses) {
            if (!hookedWindowClasses.add(windowClass)) return
        }
        XposedBridge.hookAllMethods(windowClass, "show", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                debugLog("BaseVWindow.show " + windowClass.name)
                // Let BlueLM attach its listeners and update the panel state first.
                // If its normal ACTION_START arrives, engineSessions suppresses this
                // fallback; on every later show this starts a fresh Whisper round.
                mainHandler.postDelayed({
                    if (tryStartOverlayCapture(COPILOT_PANEL_WINDOW)) {
                        setOverlayStatus(STATUS_STARTING, false)
                    } else if (hasActiveRecording()) {
                        setOverlayStatus(STATUS_LISTENING, false)
                    }
                }, OVERLAY_SHOW_REARM_DELAY_MS)
            }
        })
        Log.i(LOG_TAG, "BlueLM BaseVWindow.show lifecycle hook installed")
    }

    private fun hookBlueLmDecorViewClass(decorClass: Class<*>) {
        synchronized(hookedWindowClasses) {
            if (!hookedWindowClasses.add(decorClass)) return
        }
        XposedBridge.hookAllMethods(decorClass, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_UP) return
                // The injected TextView already performed its click action.
                if (param.result == true) return
                // VWindowDecorView is the actual root of CopilotPanelWindow. Unlike
                // SurfaceView children, this method sees every completed panel tap.
                val root = param.thisObject as? View
                val bottomSpeakArea = root != null && event.y >= root.height * 0.78f
                if (bottomSpeakArea || isBlueLmVoiceIdle() != false) {
                    forceRestartOverlayCapture()
                }
            }
        })
        XposedBridge.hookAllMethods(decorClass, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                ensureCopilotWarmup((param.thisObject as? View)?.context ?: return)
            }
        })
        Log.i(LOG_TAG, "BlueLM VWindowDecorView touch hook installed")
    }

    // Status is owned by BlueLM. The previous injected TextView obscured the real
    // state and, on some window revisions, consumed taps intended for the panel.
    private fun setOverlayStatus(text: String, clickable: Boolean) = Unit

    private fun hookBlueLmVoiceManagerClass(managerClass: Class<*>) {
        synchronized(hookedVoiceManagerClasses) {
            if (!hookedVoiceManagerClasses.add(managerClass)) return
        }
        voiceManagerClass = managerClass
        XposedBridge.hookAllMethods(managerClass, "updateAsrState", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hasActiveRecording()) return
                // BlueLM may deliver a stale ALL_END/idle event after the next local
                // AudioRecord has already started. Keep the observable state truthful.
                if (param.args.getOrNull(0) != VOICE_STATE_WORKING) {
                    param.args[0] = VOICE_STATE_WORKING
                    if (param.args.size > 1) param.args[1] = "Whisper AudioRecord active"
                }
            }
        })
        Log.i(LOG_TAG, "BlueLM VoiceManager ASR state guard installed")
    }

    private fun isBlueLmVoiceIdle(): Boolean? {
        val managerClass = voiceManagerClass ?: return null
        return runCatching {
            fun state(field: String): String? {
                val flow = managerClass.getDeclaredField(field).apply { isAccessible = true }.get(null)
                return flow.javaClass.methods.first {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                }.invoke(flow) as? String
            }
            state("_asrStateFlow") == VOICE_STATE_IDLE &&
                state("_ttsStateFlow") == VOICE_STATE_IDLE
        }.getOrNull()
    }

    private fun hasActiveRecording(): Boolean = synchronized(engineSessions) {
        engineSessions.values.any { it.captureActive }
    }

    private fun startOverlayVisibilityMonitor() {
        synchronized(overlayMonitorLock) {
            if (overlayMonitorStarted) return
            overlayMonitorStarted = true
        }
        mainHandler.post(object : Runnable {
            override fun run() {
                val panelRoot = findCopilotPanelRoot()
                val visible = panelRoot != null
                val becameVisible = synchronized(overlayMonitorLock) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!visible) {
                        if (overlayWasVisible) overlayHiddenAt = now
                        overlayWasVisible = false
                        false
                    } else {
                        val changed = !overlayWasVisible &&
                            (overlayHiddenAt == 0L || now - overlayHiddenAt >= OVERLAY_REOPEN_MIN_HIDDEN_MS)
                        overlayWasVisible = true
                        changed
                    }
                }
                if (becameVisible) {
                    mainHandler.postDelayed({
                        val target = synchronized(lastEngineTargetLock) { lastEngineTarget }
                        val active = target?.let {
                            synchronized(engineSessions) { engineSessions[it.engine] }
                        }
                        if (active?.captureActive == true) {
                            emitEngineMessage(target.engine, MESSAGE_RECORD_START, Bundle())
                            setOverlayStatus(STATUS_LISTENING, false)
                        } else {
                            if (tryStartOverlayCapture(COPILOT_PANEL_WINDOW)) {
                                setOverlayStatus(STATUS_STARTING, false)
                            }
                        }
                    }, OVERLAY_SHOW_REARM_DELAY_MS)
                }
                mainHandler.postDelayed(this, OVERLAY_MONITOR_INTERVAL_MS)
            }
        })
    }

    private fun findCopilotPanelRoot(): ViewGroup? = runCatching {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").apply {
            isAccessible = true
        }.invoke(null)
        val params = readField(global, "mParams") as? List<*> ?: return@runCatching null
        val views = readField(global, "mViews") as? List<*> ?: return@runCatching null
        params.indices.firstNotNullOfOrNull { index ->
            val attributes = params.getOrNull(index) as? WindowManager.LayoutParams
                ?: return@firstNotNullOfOrNull null
            if (!attributes.title.toString().contains(COPILOT_PANEL_WINDOW)) {
                return@firstNotNullOfOrNull null
            }
            val view = views.getOrNull(index) as? ViewGroup
                ?: return@firstNotNullOfOrNull null
            if (view.isAttachedToWindow && view.windowVisibility == View.VISIBLE &&
                view.visibility == View.VISIBLE
            ) view else null
        }
    }.getOrNull()

    private fun windowTitleForViewRoot(viewRoot: Any): String {
        val attributes = readField(viewRoot, "mWindowAttributes") as? WindowManager.LayoutParams
        if (attributes != null) return attributes.title?.toString().orEmpty()
        val rootView = readField(viewRoot, "mView") as? View
        return (rootView?.layoutParams as? WindowManager.LayoutParams)?.title?.toString().orEmpty()
    }

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val value = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            if (value.isSuccess) return value.getOrNull()
            type = current.superclass
        }
        return null
    }

    private fun debugLog(message: String) {
        val context = blueLmContext ?: return
        runCatching {
            val file = java.io.File(context.cacheDir, "originwhisper_debug.log")
            if (file.length() > 1_000_000) file.delete()
            file.appendText(System.currentTimeMillis().toString() + " " + message + "\n")
        }
    }

    private fun tryStartOverlayCapture(windowTitle: String): Boolean {
        if (!windowTitle.contains(COPILOT_PANEL_WINDOW)) return false
        val context = blueLmContext ?: return false
        if (!isEnabled(context)) return false
        if (!copilotWarmupReady) {
            ensureCopilotWarmup(context)
            return false
        }
        val target = synchronized(lastEngineTargetLock) { lastEngineTarget } ?: return false
        if (synchronized(engineSessions) { engineSessions.containsKey(target.engine) }) return false
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastEngineTargetLock) {
            if (now - lastOverlayTapStartAt < OVERLAY_TAP_DEBOUNCE_MS) return false
            lastOverlayTapStartAt = now
        }
        Log.i(LOG_TAG, "CopilotPanelWindow input rearmed Whisper capture")
        startEngineWhisper(
            target.context,
            target.engine,
            target.originalMethod,
            Bundle(target.originalBundle)
        )
        return true
    }

    private fun forceRestartOverlayCapture(): Boolean {
        val target = synchronized(lastEngineTargetLock) { lastEngineTarget } ?: return false
        finishEngine(target.engine, cancel = true)
        synchronized(lastEngineTargetLock) { lastOverlayTapStartAt = 0L }
        return tryStartOverlayCapture(COPILOT_PANEL_WINDOW)
    }

    /**
     * The power-button assistant does not call Android SpeechRecognizer. It calls
     * Vivo's SpeechGptEngine directly. Intercept ACTION_START before Vivo opens its
     * recognizer, run Whisper, then resume the *original* GPT action with the same
     * request bundle and key_gpt_bundle/key_text filled in. Thus everything after
     * ASR (dialog state, GPT request, TTS and UI callbacks) remains stock.
     */
    private fun installSpeechGptEngineProxy() {
        val engineClass = runCatching {
            Class.forName(SPEECH_GPT_ENGINE, false, classLoader)
        }.getOrElse {
            XposedBridge.log("OriginRootToolbox: SpeechGptEngine not found: $it")
            return
        }

        XposedBridge.hookAllMethods(engineClass, "registerEngineCallback", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val callback = param.args.firstOrNull() ?: return
                val engine = param.thisObject
                synchronized(engineCallbacks) { engineCallbacks[engine] = callback }
                synchronized(lastEngineTargetLock) {
                    val previous = lastEngineTarget
                    if (previous != null && previous.engine !== engine) {
                        lastEngineTarget = EngineTarget(
                            previous.context,
                            engine,
                            previous.originalMethod,
                            Bundle(previous.originalBundle)
                        )
                    }
                }
                installCallbackLifecycleHook(callback.javaClass)
                Log.i(LOG_TAG, "Captured BlueLM SpeechGptEngine callback")
            }
        })

        XposedBridge.hookAllMethods(engineClass, "doAction", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val engine = param.thisObject
                if (synchronized(engineBypass) { engineBypass.contains(engine) }) return
                val action = param.args.firstOrNull() as? Int ?: return
                val context = blueLmContext ?: return
                if (!isEnabled(context)) return

                when (action) {
                    ACTION_START -> {
                        if (synchronized(engineSessions) { engineSessions.containsKey(engine) }) {
                            // A repeated stock START must not open the Vivo recognizer or
                            // reset the pending Whisper request.
                            param.result = null
                            Log.i(LOG_TAG, "Duplicate SpeechGptEngine START suppressed")
                            return
                        }
                        val source = (param.args.getOrNull(1) as? Bundle) ?: Bundle()
                        debugLog(
                            "doAction START keys=" + source.keySet() +
                                " text=" + runCatching { source.getString(KEY_TEXT) }.getOrNull() +
                                " gptKeys=" + runCatching { source.getBundle(KEY_GPT_BUNDLE)?.keySet() }.getOrNull() +
                                " panel=" + (findCopilotPanelRoot() != null)
                        )
                        // Do not start the stock all-in-one ASR request. It remains busy
                        // waiting for Vivo ASR and rejects the later text-only GPT start.
                        // EngineCore has already initialized the application voice state;
                        // after Whisper returns we perform one real, text-only START.
                        param.result = null
                        synchronized(lastEngineTargetLock) {
                            lastEngineTarget = EngineTarget(
                                context,
                                engine,
                                param.method as Method,
                                Bundle(source)
                            )
                        }
                        if (copilotWarmupReady) {
                            startEngineWhisper(
                                context,
                                engine,
                                param.method as Method,
                                Bundle(source)
                            )
                        } else {
                            ensureCopilotWarmup(context)
                        }
                        setOverlayStatus(STATUS_STARTING, false)
                    }
                    ACTION_STOP, ACTION_STOP_ASR -> {
                        val active = synchronized(engineSessions) { engineSessions[engine] }
                        if (active != null) {
                            Log.i(LOG_TAG, "BlueLM stop action $action forwarded to Whisper")
                            active.capture?.stop()
                        }
                    }
                    ACTION_CANCEL -> {
                        val active = synchronized(engineSessions) { engineSessions[engine] }
                        if (active != null) {
                            finishEngine(engine, cancel = true)
                            setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
                        }
                    }
                }
            }
        })

        XposedBridge.log("OriginRootToolbox: BlueLM SpeechGptEngine ASR is proxied to Whisper")
        Log.i(LOG_TAG, "BlueLM SpeechGptEngine proxy installed")
    }

    private fun installCallbackLifecycleHook(callbackClass: Class<*>) {
        synchronized(hookedCallbackClasses) {
            if (!hookedCallbackClasses.add(callbackClass)) return
        }
        XposedBridge.hookAllMethods(callbackClass, "onMessage", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val code = param.args.firstOrNull() as? Int ?: return
                if (code != MESSAGE_TTS_END || lifecycleEndDispatch.get() == true) return
                val context = blueLmContext ?: return
                if (!isEnabled(context)) return
                val callback = param.thisObject
                mainHandler.post {
                    runCatching {
                        lifecycleEndDispatch.set(true)
                        callback.javaClass.methods.first {
                            it.name == "onMessage" && it.parameterTypes.contentEquals(
                                arrayOf(
                                    Int::class.javaPrimitiveType,
                                    Any::class.java,
                                    IntArray::class.java
                                )
                            )
                        }.invoke(callback, MESSAGE_ALL_END, null, intArrayOf())
                        Log.i(LOG_TAG, "BlueLM round rearmed after TTS end")
                    }.onFailure {
                        Log.e(LOG_TAG, "Cannot rearm BlueLM after TTS end", it)
                    }.also {
                        lifecycleEndDispatch.remove()
                    }
                }
            }
        })
        Log.i(LOG_TAG, "BlueLM callback lifecycle hook installed on ${callbackClass.name}")
    }

    private fun installApplicationCapture() {
        XposedBridge.hookAllMethods(Application::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                blueLmContext = app.applicationContext
                startOverlayVisibilityMonitor()
            }
        })
    }

    private fun ensureCopilotWarmup(context: Context) {
        if (copilotWarmupReady) return
        val shouldStart = synchronized(copilotWarmupLock) {
            if (copilotWarmupRequested) false else {
                copilotWarmupRequested = true
                true
            }
        }
        if (!shouldStart) return
        runCatching {
            val utilsClass = Class.forName(APP_LAUNCH_UTILS, true, classLoader)
            val instance = utilsClass.getField("INSTANCE").get(null)
            utilsClass.getMethod(
                "launchBusinessTask",
                Context::class.java,
                String::class.java
            ).invoke(instance, context.applicationContext, WARMUP_SOURCE)
            Log.i(LOG_TAG, "BlueLM AppLaunchBootstrapper initialized without an Activity")
            mainHandler.postDelayed({
                synchronized(copilotWarmupLock) { copilotWarmupRequested = false }
                completeCopilotWarmup()
            }, WARMUP_READY_DELAY_MS)
        }.onFailure {
            Log.e(LOG_TAG, "Cannot initialize BlueLM AppLaunchBootstrapper", it)
            synchronized(copilotWarmupLock) { copilotWarmupRequested = false }
            completeCopilotWarmup()
        }
    }

    private fun completeCopilotWarmup() {
        if (copilotWarmupReady) return
        copilotWarmupReady = true
        Log.i(LOG_TAG, "BlueLM background bootstrap completed")
        val target = synchronized(lastEngineTargetLock) { lastEngineTarget } ?: return
        if (synchronized(engineSessions) { engineSessions.containsKey(target.engine) }) return
        startEngineWhisper(
            target.context,
            target.engine,
            target.originalMethod,
            Bundle(target.originalBundle)
        )
    }

    private fun startEngineWhisper(
        context: Context,
        engine: Any,
        originalMethod: Method,
        originalBundle: Bundle
    ) {
        finishEngine(engine, cancel = true)
        val attemptStartedAt = android.os.SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            val current = synchronized(engineSessions) { engineSessions[engine] }
            if (current?.attemptId == attemptStartedAt && !current.captureStarted) {
                finishEngine(engine, cancel = true)
                setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
                Log.e(LOG_TAG, "BlueLM AudioRecord did not start for attempt $attemptStartedAt")
            }
        }, AUDIO_START_WATCHDOG_MS)
        mainHandler.post {
            runCatching {
                lateinit var session: EngineSession
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    handleBridgeMessage(engine, session, message)
                    true
                })
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || synchronized(engineSessions) { engineSessions[engine] !== session }) return
                        session.service = Messenger(binder)
                        startBlueLmCapture(engine, session)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        session.service = null
                        if (synchronized(engineSessions) { engineSessions[engine] === session }) {
                            Log.e(LOG_TAG, "Whisper decoder process disconnected during active round")
                            handleBridgeError(engine, SpeechRecognizer.ERROR_SERVER)
                        }
                    }
                }
                session = EngineSession(
                    context,
                    connection,
                    reply,
                    originalMethod,
                    originalBundle,
                    attemptId = attemptStartedAt
                )
                synchronized(engineSessions) { engineSessions[engine] = session }
                val bound = context.bindService(
                    Intent().setComponent(ComponentName(MODULE_PACKAGE, WHISPER_BRIDGE_SERVICE)),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
                check(bound) { "Whisper bridge bindService returned false" }
                session.bound = true
                Log.i(LOG_TAG, "Whisper decoder bound; microphone will run inside BlueLM UID")
            }.onFailure {
                XposedBridge.log("OriginRootToolbox: SpeechGptEngine Whisper start failed: $it")
                Log.e(LOG_TAG, "Cannot start engine-level Whisper", it)
                emitEngineMessage(engine, MESSAGE_ERROR, it.toString(), SpeechRecognizer.ERROR_SERVER)
                finishEngine(engine, cancel = true)
                setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
            }
        }
    }

    private fun startBlueLmCapture(engine: Any, session: EngineSession) {
        if (session.capture != null) return
        val capture = BlueLmAudioCapture(object : BlueLmAudioCapture.Listener {
            private fun postIfCurrent(block: () -> Unit) {
                mainHandler.post {
                    if (synchronized(engineSessions) { engineSessions[engine] === session }) block()
                }
            }

            override fun onReady() = postIfCurrent {
                session.captureStarted = true
                session.captureActive = true
                session.trace("BlueLM-UID AudioRecord ready")
                emitEngineMessage(engine, MESSAGE_RECORD_START, Bundle())
                setOverlayStatus(STATUS_LISTENING, false)
            }

            override fun onBeginningOfSpeech() = postIfCurrent {
                session.trace("BlueLM-UID speech begin")
                emitEngineMessage(engine, MESSAGE_SPEECH_START, null)
                setOverlayStatus(STATUS_LISTENING, false)
            }

            override fun onEndOfSpeech() = postIfCurrent {
                session.captureActive = false
                emitEngineMessage(engine, MESSAGE_SPEECH_END, null)
                emitEngineMessage(engine, MESSAGE_RECORD_END, null)
                setOverlayStatus(STATUS_RECOGNIZING, false)
            }

            override fun onAudio(pcm16le: ByteArray) = postIfCurrent {
                session.captureActive = false
                session.decodePending = true
                setOverlayStatus(STATUS_RECOGNIZING, false)
                session.trace("sending ${pcm16le.size} PCM bytes for Whisper decode")
                if (!session.sendPcm(pcm16le)) {
                    handleBridgeError(engine, SpeechRecognizer.ERROR_SERVER)
                    return@postIfCurrent
                }
                mainHandler.postDelayed({
                    val current = synchronized(engineSessions) { engineSessions[engine] }
                    if (current === session && session.decodePending) {
                        Log.e(LOG_TAG, "Whisper result watchdog expired")
                        handleBridgeError(engine, SpeechRecognizer.ERROR_SERVER)
                    }
                }, DECODE_RESULT_WATCHDOG_MS)
            }

            override fun onNoSpeech() = postIfCurrent {
                session.captureActive = false
                setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
                handleBridgeError(engine, SpeechRecognizer.ERROR_NO_MATCH)
            }

            override fun onError(error: Throwable) = postIfCurrent {
                session.captureActive = false
                setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
                session.trace("BlueLM-UID capture failed: ${error.javaClass.simpleName}: ${error.message}")
                Log.e(LOG_TAG, "BlueLM-process microphone capture failed", error)
                handleBridgeError(engine, SpeechRecognizer.ERROR_AUDIO)
            }
        })
        session.capture = capture
        capture.start()
    }

    private fun handleBridgeMessage(engine: Any, session: EngineSession, message: Message) {
        if (synchronized(engineSessions) { engineSessions[engine] !== session }) return
        Log.i(LOG_TAG, "Whisper bridge event ${message.what}")
        when (message.what) {
            WhisperBridgeProtocol.READY -> emitEngineMessage(engine, MESSAGE_RECORD_START, Bundle())
            WhisperBridgeProtocol.BEGIN -> emitEngineMessage(engine, MESSAGE_SPEECH_START, null)
            WhisperBridgeProtocol.END -> {
                emitEngineMessage(engine, MESSAGE_SPEECH_END, null)
                emitEngineMessage(engine, MESSAGE_RECORD_END, null)
            }
            WhisperBridgeProtocol.PARTIAL -> {
                val text = message.data.getString(WhisperBridgeProtocol.TEXT).orEmpty().trim()
                if (text.isNotEmpty()) emitEngineMessage(engine, MESSAGE_ASR_RESULT, asrResultJson(text), 0)
            }
            WhisperBridgeProtocol.RESULT -> {
                session.decodePending = false
                val text = message.data.getString(WhisperBridgeProtocol.TEXT).orEmpty().trim()
                if (text.isEmpty()) {
                    handleBridgeError(engine, SpeechRecognizer.ERROR_NO_MATCH)
                    return
                }
                Log.i(LOG_TAG, "Whisper result enters stock GPT pipeline: ${text.length} chars")
                session.trace("result='$text'")
                val delivered = emitEngineMessage(engine, MESSAGE_ASR_RESULT, asrResultJson(text), 1)
                emitEngineMessage(engine, MESSAGE_ASR_END, null)
                session.trace("UI result callback delivered=$delivered")
                resumeStockGpt(engine, session, text)
                finishEngine(engine, cancel = false)
                setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
            }
            WhisperBridgeProtocol.ERROR -> {
                session.decodePending = false
                handleBridgeError(engine, message.arg1)
            }
        }
    }

    private fun handleBridgeError(engine: Any, error: Int) {
        Log.e(LOG_TAG, "Whisper engine-level recognition error: $error")
        emitEngineMessage(engine, MESSAGE_ERROR, "Whisper recognition error $error", error)
        emitEngineMessage(engine, MESSAGE_ASR_END, null)
        finishEngine(engine, cancel = false)
        setOverlayStatus(STATUS_TAP_TO_SPEAK, true)
    }

    private fun resumeStockGpt(engine: Any, session: EngineSession, text: String) {
        val request = Bundle(session.originalBundle)
        request.remove(KEY_ASR_BUNDLE)
        val gpt = request.getBundle(KEY_GPT_BUNDLE)?.let(::Bundle) ?: Bundle()
        gpt.putString(KEY_TEXT, text)
        request.putBundle(KEY_GPT_BUNDLE, gpt)

        runCatching {
            synchronized(engineBypass) { engineBypass.add(engine) }
            if (session.originalMethod.parameterTypes.size == 2) {
                session.originalMethod.invoke(engine, ACTION_START, request)
            } else {
                session.originalMethod.invoke(engine, ACTION_START)
            }
            session.trace("stock GPT ACTION_START invoked with text")
            Log.i(LOG_TAG, "Stock BlueLM GPT resumed with Whisper text")
        }.onFailure {
            session.trace("stock GPT invoke failed: ${it.javaClass.simpleName}: ${it.message}")
            XposedBridge.log("OriginRootToolbox: cannot resume stock GPT: $it")
            Log.e(LOG_TAG, "Cannot resume stock GPT", it)
        }.also {
            synchronized(engineBypass) { engineBypass.remove(engine) }
        }
    }

    private fun emitEngineMessage(engine: Any, code: Int, payload: Any?, vararg args: Int): Boolean {
        val callback = synchronized(engineCallbacks) { engineCallbacks[engine] }
            ?: runCatching {
                val coreClass = Class.forName(ENGINE_CORE, false, classLoader)
                coreClass.getField("INSTANCE").get(null)
            }.getOrNull()
        if (callback == null) {
            Log.e(LOG_TAG, "Cannot deliver BlueLM UI callback $code: callback is missing")
            synchronized(engineSessions) { engineSessions[engine] }?.trace(
                "callback $code failed: EngineCore.INSTANCE missing"
            )
            return false
        }
        return runCatching {
            callback.javaClass.methods.first {
                it.name == "onMessage" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Any::class.java, IntArray::class.java)
                    )
            }.invoke(callback, code, payload, args)
            Log.i(LOG_TAG, "Delivered BlueLM UI callback $code args=${args.contentToString()}")
            true
        }.onFailure {
            XposedBridge.log("OriginRootToolbox: SpeechGptEngine callback $code failed: $it")
            Log.e(LOG_TAG, "BlueLM UI callback $code failed", it)
            synchronized(engineSessions) { engineSessions[engine] }?.trace(
                "callback $code failed: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrDefault(false)
    }

    private fun asrResultJson(text: String): String {
        val data = asrDataJson(text)
        // SpeechGptEngine wraps its ASR JSON in a second JSON object. BlueLM's
        // VoiceStateManager calls JSONObject(payload).getString("data") first.
        return JSONObject().put("data", data).toString()
    }

    private fun asrDataJson(text: String): String = JSONObject().apply {
            put("is_last", "true")
            put("result_id", "1")
            put("reformation", 0)
            put("text", text)
            put("is_use_decoder_trans", "false")
            put("total_frames", "0")
        }.toString()

    /**
     * The power-button panel uses the newer VoiceProtocolDispatcher path. In
     * that mode VoiceStateManager intentionally ignores local SpeechGptEngine
     * results (isYmEngine=false, isOffline=false). Feed Whisper into the active
     * cloud protocol callback with its real request id, exactly where a cloud
     * ASR result normally enters the overlay.
     */
    private fun dispatchOverlayProtocolResult(
        engine: Any,
        session: EngineSession,
        text: String
    ): Boolean = runCatching {
        // registerEngineCallback may provide an SDK wrapper in the overlay.
        // The mode flags and VoiceListener live on BlueLM's EngineCore itself.
        val callbackLoader = engine.javaClass.classLoader
            ?: blueLmContext?.classLoader
            ?: classLoader
        val coreClass = Class.forName(ENGINE_CORE, false, callbackLoader)
        val callback = coreClass.getField("INSTANCE").get(null) ?: return false
        val stateMethod = callback.javaClass.declaredMethods.firstOrNull {
            it.name == "getStateManager" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true } ?: return false
        val state = stateMethod.invoke(callback) ?: return false
        fun atomicFlag(name: String): Boolean {
            val value = state.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(state) as? java.util.concurrent.atomic.AtomicBoolean
            return value?.get() == true
        }
        val ym = atomicFlag("isYmEngine")
        val offline = atomicFlag("isOffline")
        session.trace("voice mode: ym=$ym offline=$offline")
        if (ym || offline) return false

        val dispatcherClass = Class.forName(VOICE_PROTOCOL_DISPATCHER, false, callbackLoader)
        val requestIdField = dispatcherClass.getDeclaredField("asrRequestId").apply {
            isAccessible = true
        }
        val requestId = requestIdField.get(null) as? String
        if (requestId.isNullOrBlank()) {
            session.trace("overlay protocol unavailable: empty asrRequestId")
            dispatchVoiceListenerFallback(callback, state, text, session, callbackLoader)
            return false
        }
        val dispatcher = dispatcherClass.getField("INSTANCE").get(null)
        val method = dispatcherClass.getDeclaredMethod(
            "dispatchAsrResultFromCloud",
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
        method.invoke(dispatcher, asrDataJson(text), requestId)
        session.trace("Whisper result injected into overlay protocol request=$requestId")
        Log.i(LOG_TAG, "Whisper result delivered through VoiceProtocolDispatcher")
        true
    }.onFailure {
        session.trace("overlay protocol injection failed: ${it.javaClass.simpleName}: ${it.message}")
        Log.e(LOG_TAG, "Cannot inject Whisper into overlay protocol", it)
    }.getOrDefault(false)

    private fun dispatchVoiceListenerFallback(
        core: Any,
        state: Any,
        text: String,
        session: EngineSession,
        callbackLoader: ClassLoader
    ) {
        runCatching {
            val builderClass = Class.forName(ASR_RESULT_BUILDER, false, callbackLoader)
            val companion = builderClass.getField("Companion").get(null)
            val result = companion.javaClass.getMethod("get", String::class.java)
                .invoke(companion, asrDataJson(text))
            val linkId = state.javaClass.methods.first {
                it.name == "getCurrentLinkId" && it.parameterTypes.isEmpty()
            }.invoke(state) as? String ?: ""
            val listener = core.javaClass.getMethod("getVoiceListener").invoke(core) ?: return
            listener.javaClass.methods.first {
                it.name == "onAsrResult" && it.parameterTypes.size == 2
            }.invoke(listener, linkId, result)
            session.trace("Whisper result delivered directly to overlay VoiceListener")
        }.onFailure {
            session.trace("overlay VoiceListener fallback failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun finishEngine(engine: Any, cancel: Boolean) {
        val session = synchronized(engineSessions) { engineSessions.remove(engine) } ?: return
        session.captureActive = false
        session.capture?.cancel()
        session.capture = null
        mainHandler.post {
            if (cancel) session.send(WhisperBridgeProtocol.CANCEL)
            if (session.bound) runCatching { session.context.unbindService(session.connection) }
            session.bound = false
        }
    }

    private fun installRecognitionServiceProxy() {
        val serviceClass = runCatching {
            Class.forName(COPILOT_RECOGNITION_SERVICE, false, classLoader)
        }.getOrElse {
            XposedBridge.log("OriginRootToolbox: CopilotRecognitionService not found: $it")
            return
        }

        XposedBridge.hookAllMethods(serviceClass, "onStartListening", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val service = param.thisObject as? Context ?: return
                if (!isEnabled(service)) return
                val intent = param.args.getOrNull(0) as? Intent
                val callback = param.args.getOrNull(1) as? RecognitionService.Callback ?: return

                param.result = null
                startProxy(service, intent, callback)
            }
        })

        XposedBridge.hookAllMethods(serviceClass, "onStopListening", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val service = param.thisObject as? Context ?: return
                if (!isEnabled(service)) return
                param.result = null
                mainHandler.post { sessions[service]?.recognizer?.stopListening() }
            }
        })

        XposedBridge.hookAllMethods(serviceClass, "onCancel", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val service = param.thisObject as? Context ?: return
                if (!isEnabled(service)) return
                param.result = null
                finish(service, cancel = true)
            }
        })

        XposedBridge.hookAllMethods(serviceClass, "onDestroy", object : XC_MethodHook(HIGHEST_PRIORITY) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Context)?.let { finish(it, cancel = true) }
            }
        })

        XposedBridge.log("OriginRootToolbox: BlueLM native RecognitionService is proxied to Whisper")
        Log.i(LOG_TAG, "BlueLM native RecognitionService proxy installed")
    }

    private fun startProxy(
        service: Context,
        sourceIntent: Intent?,
        callback: RecognitionService.Callback
    ) {
        finish(service, cancel = true)
        mainHandler.post {
            runCatching {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(
                    service,
                    ComponentName(MODULE_PACKAGE, WHISPER_SERVICE)
                )
                val session = ProxySession(recognizer, callback)
                sessions[service] = session
                recognizer.setRecognitionListener(ForwardingListener(service, session))
                recognizer.startListening(copyRecognitionIntent(sourceIntent))
                Log.i(LOG_TAG, "BlueLM RecognitionService request forwarded to Whisper")
            }.onFailure {
                XposedBridge.log("OriginRootToolbox: RecognitionService proxy failed: $it")
                Log.e(LOG_TAG, "Cannot forward BlueLM recognition request", it)
                runCatching { callback.error(SpeechRecognizer.ERROR_SERVER) }
                finish(service, cancel = false)
            }
        }
    }

    private fun copyRecognitionIntent(source: Intent?): Intent =
        Intent(source ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
            action = RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private inner class ForwardingListener(
        private val service: Context,
        private val session: ProxySession
    ) : RecognitionListener {
        private inline fun forward(block: (RecognitionService.Callback) -> Unit) {
            if (sessions[service] !== session) return
            runCatching { block(session.callback) }.onFailure {
                XposedBridge.log("OriginRootToolbox: BlueLM RecognitionService callback failed: $it")
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = forward { it.readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() = forward { it.beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = forward { it.rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = forward { it.bufferReceived(buffer ?: ByteArray(0)) }
        override fun onEndOfSpeech() = forward { it.endOfSpeech() }

        override fun onError(error: Int) {
            Log.w(LOG_TAG, "Whisper returned error $error to BlueLM RecognitionService")
            forward { it.error(error) }
            finish(service, cancel = false)
        }

        override fun onResults(results: Bundle?) {
            val textLength = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.length ?: 0
            Log.i(LOG_TAG, "Whisper result returned through BlueLM callback: $textLength chars")
            forward { it.results(results ?: Bundle()) }
            finish(service, cancel = false)
        }

        override fun onPartialResults(partialResults: Bundle?) =
            forward { it.partialResults(partialResults ?: Bundle()) }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finish(service: Context, cancel: Boolean) {
        val session = sessions.remove(service) ?: return
        mainHandler.post {
            if (cancel) runCatching { session.recognizer.cancel() }
            runCatching { session.recognizer.destroy() }
        }
    }

    private fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            BlueLmRussianHook.SETTING_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    private data class ProxySession(
        val recognizer: SpeechRecognizer,
        val callback: RecognitionService.Callback
    )

    private data class EngineSession(
        val context: Context,
        val connection: ServiceConnection,
        val reply: Messenger,
        val originalMethod: Method,
        val originalBundle: Bundle,
        val attemptId: Long = 0L,
        var service: Messenger? = null,
        var bound: Boolean = false,
        var capture: BlueLmAudioCapture? = null,
        @Volatile var captureStarted: Boolean = false,
        @Volatile var captureActive: Boolean = false,
        @Volatile var decodePending: Boolean = false
    ) {
        fun send(what: Int, replyTo: Messenger? = null) {
            runCatching {
                val target = service ?: error("Whisper bridge is not connected")
                target.send(Message.obtain(null, what).apply { this.replyTo = replyTo })
                Log.i(LOG_TAG, "Sent Whisper bridge command $what")
            }.onFailure { Log.e(LOG_TAG, "Cannot send Whisper bridge command $what", it) }
        }

        fun trace(text: String) {
            runCatching {
                val target = service ?: return
                target.send(Message.obtain(null, WhisperBridgeProtocol.TRACE).apply {
                    data = Bundle().apply { putString(WhisperBridgeProtocol.TEXT, text) }
                })
            }
        }

        fun sendPcm(pcm16le: ByteArray): Boolean = runCatching {
                val target = service ?: error("Whisper bridge is not connected")
                target.send(Message.obtain(null, WhisperBridgeProtocol.TRANSCRIBE).apply {
                    replyTo = reply
                    data = Bundle().apply { putByteArray(WhisperBridgeProtocol.PCM, pcm16le) }
                })
                Log.i(LOG_TAG, "Sent ${pcm16le.size} PCM bytes to Whisper bridge")
                true
            }.onFailure {
                Log.e(LOG_TAG, "Cannot send PCM to Whisper bridge", it)
            }.getOrDefault(false)
    }

    private data class EngineTarget(
        val context: Context,
        val engine: Any,
        val originalMethod: Method,
        val originalBundle: Bundle
    )

    private companion object {
        const val COPILOT_RECOGNITION_SERVICE =
            "com.vivo.ai.copilot.framework.wakeup.CopilotRecognitionService"
        const val MODULE_PACKAGE = "dev.unvoid.originceiler"
        const val BLUE_LM_PACKAGE = "com.vivo.ai.copilot"
        const val APP_LAUNCH_UTILS =
            "com.vivo.ai.copilot.base.tool.launcher.AppLaunchUtils"
        const val WARMUP_SOURCE = "EmptyLauncherActivity"
        const val WHISPER_SERVICE = "dev.unvoid.originceiler.WhisperRecognitionService"
        const val WHISPER_BRIDGE_SERVICE = "dev.unvoid.originceiler.WhisperBridgeService"
        const val SPEECH_GPT_ENGINE = "com.vivo.speechsdk.gpt.api.SpeechGptEngine"
        const val ENGINE_CORE = "com.vivo.ai.copilot.base.voice.engine.logic.EngineCore"
        const val BASE_VWINDOW =
            "com.vivo.ai.copilot.common.business.framework.window.BaseVWindow"
        const val VWINDOW_DECOR_VIEW =
            "com.vivo.ai.copilot.common.business.framework.window.VWindowDecorView"
        const val VOICE_MANAGER = "com.vivo.ai.copilot.base.voice.VoiceManager"
        const val VOICE_PROTOCOL_DISPATCHER =
            "com.vivo.ai.copilot.base.voice.engine.logic.protocol.VoiceProtocolDispatcher"
        const val ASR_RESULT_BUILDER =
            "com.vivo.ai.copilot.base.voice.asr.logic.AsrResultBuilder"
        const val COPILOT_PANEL_WINDOW = "CopilotPanelWindow"
        const val OVERLAY_TAP_DEBOUNCE_MS = 750L
        const val OVERLAY_SHOW_REARM_DELAY_MS = 150L
        const val OVERLAY_MONITOR_INTERVAL_MS = 250L
        const val OVERLAY_REOPEN_MIN_HIDDEN_MS = 600L
        const val AUDIO_START_WATCHDOG_MS = 4_000L
        const val DECODE_RESULT_WATCHDOG_MS = 13_000L
        const val WARMUP_READY_DELAY_MS = 500L
        const val VOICE_STATE_IDLE = "idle"
        const val VOICE_STATE_WORKING = "working"
        const val STATUS_TAP_TO_SPEAK = "Нажмите, чтобы говорить"
        const val STATUS_STARTING = "Включаю микрофон…"
        const val STATUS_LISTENING = "Слушаю…"
        const val STATUS_RECOGNIZING = "Распознаю…"
        const val ACTION_START = 0x5a
        const val ACTION_STOP = 0x5b
        const val ACTION_CANCEL = 0x5c
        const val ACTION_STOP_ASR = 0x67
        const val MESSAGE_ERROR = 0x138a
        const val MESSAGE_ASR_RESULT = 0x139a
        const val MESSAGE_SPEECH_START = 0x139b
        const val MESSAGE_SPEECH_END = 0x139c
        const val MESSAGE_RECORD_START = 0x139d
        const val MESSAGE_RECORD_END = 0x139e
        const val MESSAGE_ASR_END = 0x13a7
        const val MESSAGE_TTS_END = 0x13a8
        const val MESSAGE_ALL_END = 0x13ab
        const val KEY_ASR_BUNDLE = "key_asr_bundle"
        const val KEY_GPT_BUNDLE = "key_gpt_bundle"
        const val KEY_TEXT = "key_text"
        const val HIGHEST_PRIORITY = 10_000
        const val LOG_TAG = "OriginWhisper"
        val mainHandler = Handler(Looper.getMainLooper())
        val sessions = WeakHashMap<Context, ProxySession>()
        @Volatile var blueLmContext: Context? = null
        val engineCallbacks = WeakHashMap<Any, Any>()
        val engineSessions = WeakHashMap<Any, EngineSession>()
        val engineBypass: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
        val hookedCallbackClasses: MutableSet<Class<*>> = Collections.newSetFromMap(WeakHashMap())
        val lifecycleEndDispatch = ThreadLocal<Boolean>()
        val hookedWindowClasses: MutableSet<Class<*>> = Collections.newSetFromMap(WeakHashMap())
        val hookedVoiceManagerClasses: MutableSet<Class<*>> = Collections.newSetFromMap(WeakHashMap())
        val copilotWarmupLock = Any()
        @Volatile var copilotWarmupRequested = false
        @Volatile var copilotWarmupReady = false
        @Volatile var voiceManagerClass: Class<*>? = null
        val overlayMonitorLock = Any()
        var overlayMonitorStarted = false
        var overlayWasVisible = false
        var overlayHiddenAt = 0L
        val lastEngineTargetLock = Any()
        @Volatile var lastEngineTarget: EngineTarget? = null
        var lastOverlayTapStartAt = 0L
    }
}

package dev.unvoid.originceiler

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/** Data shared between the notification listener hook and the views injected into the expanded island. */
object IslandNotificationCards {
    /** Zero-width marker that makes our synthetic island titles recognisable inside vivo's stock expanded view. */
    const val MARKER = "​‌"

    enum class ActionKind { ANSWER, DECLINE, OTHER }

    class CallAction(val label: CharSequence, val intent: PendingIntent, val kind: ActionKind, val icon: Icon?)

    sealed class Card(val islandTitle: String, val onDismiss: () -> Unit)

    class CallCard(
        islandTitle: String,
        onDismiss: () -> Unit,
        val packageName: String,
        val name: CharSequence,
        val subtitle: CharSequence?,
        val chronometerBase: Long?,
        val avatar: Icon?,
        val contentIntent: PendingIntent?,
        val actions: List<CallAction>
    ) : Card(islandTitle, onDismiss)

    class OtpCard(
        islandTitle: String,
        onDismiss: () -> Unit,
        val packageName: String,
        val appLabel: CharSequence,
        val code: String,
        val contentIntent: PendingIntent?
    ) : Card(islandTitle, onDismiss)

    private val cards = ConcurrentHashMap<String, Card>()
    /** Titles of cards this process made (it owns their dismissal); the others are mirrors. */
    private val owned = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /*
     * The notification listener runs in SystemUI, but the expanded island is drawn by
     * com.vivo.systemuiplugin:island: a separate process with its own (empty) copy of this
     * object. Cards are mirrored there by broadcast. SystemUI and the plugin share one uid, so
     * the receiver is not exported.
     */
    private const val ACTION_SYNC = "dev.unvoid.originceiler.ISLAND_CARD_SYNC"
    private const val ACTION_REMOVE = "dev.unvoid.originceiler.ISLAND_CARD_REMOVE"
    private const val ACTION_DISMISS = "dev.unvoid.originceiler.ISLAND_CARD_DISMISS"
    private const val ACTION_REQUEST = "dev.unvoid.originceiler.ISLAND_CARD_REQUEST"
    private const val EXTRA_PID = "pid"
    private const val EXTRA_CARD = "card"
    private const val EXTRA_TITLE = "title"
    private val attached = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        if (!attached.compareAndSet(false, true)) return
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_SYNC); addAction(ACTION_REMOVE); addAction(ACTION_DISMISS); addAction(ACTION_REQUEST)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                if (intent.getIntExtra(EXTRA_PID, -1) == android.os.Process.myPid()) return
                runCatching { onBroadcast(intent) }
            }
        }
        val ok = runCatching { app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }.isSuccess
        if (!ok) { attached.set(false); return }
        // A process that starts later (the island one) asks for what is already showing.
        broadcast(android.content.Intent(ACTION_REQUEST))
    }

    private fun onBroadcast(intent: android.content.Intent) {
        when (intent.action) {
            ACTION_SYNC -> {
                val bundle = intent.getBundleExtra(EXTRA_CARD) ?: return
                val card = fromBundle(bundle) ?: return
                if (card.islandTitle in owned) return
                cards[card.islandTitle] = card
                mainHandler.post { listeners.forEach { runCatching(it) } }
            }
            ACTION_REMOVE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: return
                if (title !in owned) cards.remove(title)
            }
            ACTION_DISMISS -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: return
                if (title in owned) cards[title]?.let { card -> mainHandler.post { card.onDismiss() } }
            }
            ACTION_REQUEST -> owned.forEach { title -> cards[title]?.let(::sync) }
        }
    }

    private fun broadcast(intent: android.content.Intent) {
        val context = appContext ?: return
        intent.putExtra(EXTRA_PID, android.os.Process.myPid())
        // vivo drops implicit broadcasts here: address each process's package explicitly.
        for (target in arrayOf("com.android.systemui", "com.vivo.systemuiplugin")) {
            runCatching { context.sendBroadcast(android.content.Intent(intent).setPackage(target)) }
        }
    }

    private fun sync(card: Card) = broadcast(android.content.Intent(ACTION_SYNC).putExtra(EXTRA_CARD, toBundle(card)))

    private fun toBundle(card: Card): Bundle = Bundle().apply {
        putString("title", card.islandTitle)
        when (card) {
            is CallCard -> {
                putString("type", "call")
                putString("package", card.packageName)
                putCharSequence("name", card.name)
                putCharSequence("subtitle", card.subtitle)
                putLong("chronometer", card.chronometerBase ?: Long.MIN_VALUE)
                putParcelable("avatar", card.avatar)
                putParcelable("content", card.contentIntent)
                putParcelableArrayList("actions", ArrayList(card.actions.map { action ->
                    Bundle().apply {
                        putCharSequence("label", action.label)
                        putParcelable("intent", action.intent)
                        putString("kind", action.kind.name)
                        putParcelable("icon", action.icon)
                    }
                }))
            }
            is OtpCard -> {
                putString("type", "otp")
                putString("package", card.packageName)
                putCharSequence("app", card.appLabel)
                putString("code", card.code)
                putParcelable("content", card.contentIntent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun fromBundle(b: Bundle): Card? {
        val title = b.getString("title") ?: return null
        val dismiss = { broadcast(android.content.Intent(ACTION_DISMISS).putExtra(EXTRA_TITLE, title)) }
        val pkg = b.getString("package").orEmpty()
        val content = b.getParcelable<PendingIntent>("content")
        return when (b.getString("type")) {
            "call" -> CallCard(
                title, dismiss, pkg,
                name = b.getCharSequence("name") ?: "",
                subtitle = b.getCharSequence("subtitle"),
                chronometerBase = b.getLong("chronometer", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
                avatar = b.getParcelable<Icon>("avatar"),
                contentIntent = content,
                actions = b.getParcelableArrayList<Bundle>("actions").orEmpty().mapNotNull { a ->
                    val intent = a.getParcelable<PendingIntent>("intent") ?: return@mapNotNull null
                    CallAction(
                        a.getCharSequence("label") ?: "",
                        intent,
                        runCatching { ActionKind.valueOf(a.getString("kind").orEmpty()) }.getOrDefault(ActionKind.OTHER),
                        a.getParcelable<Icon>("icon")
                    )
                }
            )
            "otp" -> OtpCard(title, dismiss, pkg, b.getCharSequence("app") ?: pkg, b.getString("code") ?: return null, content)
            else -> null
        }
    }

    fun put(card: Card) {
        cards[card.islandTitle] = card
        owned += card.islandTitle
        listeners.forEach { runCatching(it) }
        sync(card)
    }

    fun remove(islandTitle: String) {
        cards.remove(islandTitle)
        if (owned.remove(islandTitle)) broadcast(android.content.Intent(ACTION_REMOVE).putExtra(EXTRA_TITLE, islandTitle))
    }

    fun get(islandTitle: String): Card? = cards[islandTitle]

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Finds the card whose marker title is rendered somewhere inside vivo's stock expanded island view. */
    fun findIn(view: View): Card? {
        if (cards.isEmpty()) return null
        val texts = mutableListOf<String>()
        collectTexts(view, texts)
        cards.values.filter { card -> texts.any { it.contains(card.islandTitle) } }
            .maxByOrNull { it.islandTitle.length }?.let { return it }
        // The marker may not survive vivo's text handling: match the visible title exactly.
        val plain = texts.map { it.replace(MARKER, "").trim() }.filter { it.isNotEmpty() }
        return cards.values.firstOrNull { card -> card.islandTitle.replace(MARKER, "").trim() in plain }
    }

    private fun collectTexts(view: View, out: MutableList<String>) {
        view.contentDescription?.toString()?.let(out::add)
        if (view is TextView) view.text?.toString()?.let(out::add)
        if (view is ViewGroup) for (index in 0 until view.childCount) collectTexts(view.getChildAt(index), out)
    }

    // region Call parsing

    fun isCall(notification: Notification): Boolean {
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (template.endsWith("\$CallStyle")) return true
        if (notification.category != Notification.CATEGORY_CALL) return false
        return notification.flags and Notification.FLAG_ONGOING_EVENT != 0 || notification.fullScreenIntent != null
    }

    fun callActions(notification: Notification): List<CallAction> {
        val extras = notification.extras
        val result = mutableListOf<CallAction>()
        fun intent(key: String): PendingIntent? = runCatching {
            if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(key, PendingIntent::class.java)
            else @Suppress("DEPRECATION") extras.getParcelable(key) as? PendingIntent
        }.getOrNull()
        intent("android.declineIntent")?.let { result += CallAction("Отклонить", it, ActionKind.DECLINE, null) }
        intent("android.hangUpIntent")?.let { result += CallAction("Завершить", it, ActionKind.DECLINE, null) }
        notification.actions.orEmpty().forEach { action ->
            val pending = action.actionIntent ?: return@forEach
            if (result.any { it.intent == pending }) return@forEach
            result += CallAction(action.title ?: "", pending, actionKind(action.title), action.getIcon())
        }
        intent("android.answerIntent")?.let { answer ->
            if (result.none { it.intent == answer }) result += CallAction("Ответить", answer, ActionKind.ANSWER, null)
        }
        // Keep the iOS order: decline on the left, answer on the right, everything else in between.
        return result.sortedBy { if (it.kind == ActionKind.DECLINE) 0 else if (it.kind == ActionKind.OTHER) 1 else 2 }.take(4)
    }

    private fun actionKind(title: CharSequence?): ActionKind {
        val text = title?.toString()?.lowercase().orEmpty()
        if (listOf("answer", "accept", "ответ", "принять", "接听").any(text::contains)) return ActionKind.ANSWER
        if (listOf("decline", "reject", "hang", "end call", "dismiss", "отклон", "сброс", "заверш", "положить", "挂断", "拒绝").any(text::contains)) {
            return ActionKind.DECLINE
        }
        return ActionKind.OTHER
    }

    fun callName(notification: Notification): CharSequence? {
        val extras = notification.extras
        val person = runCatching {
            if (Build.VERSION.SDK_INT >= 33) extras.getParcelable("android.callPerson", Person::class.java)
            else @Suppress("DEPRECATION") extras.getParcelable("android.callPerson") as? Person
        }.getOrNull()
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: person?.name?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
    }

    fun callAvatar(notification: Notification): Icon? {
        val extras = notification.extras
        val person = runCatching {
            if (Build.VERSION.SDK_INT >= 33) extras.getParcelable("android.callPerson", Person::class.java)
            else @Suppress("DEPRECATION") extras.getParcelable("android.callPerson") as? Person
        }.getOrNull()
        return person?.icon
            ?: runCatching { notification.getLargeIcon() }.getOrNull()
            ?: runCatching {
                if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(Notification.EXTRA_LARGE_ICON, Icon::class.java)
                else @Suppress("DEPRECATION") extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Icon
            }.getOrNull()
    }

    fun callChronometerBase(notification: Notification): Long? {
        val extras = notification.extras
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        if (!usesChronometer || notification.`when` <= 0L) return null
        return SystemClock.elapsedRealtime() - (System.currentTimeMillis() - notification.`when`)
    }

    // endregion

    // region OTP parsing

    private val otpKeywords = Regex(
        "(?i)(код|code|otp|парол|password|passcode|\\bpin\\b|подтвержд|verif|одноразов|2fa|токен|token|вход|войти|sign.?in|log.?in|验证码|校验码)"
    )
    private val otpCandidate = Regex("(?<![\\d+])(?<!\\d[.,:/])(\\d{3}-\\d{3}|\\d{4,8})(?!\\d)(?![.,:/]\\d)")

    fun extractOtp(notification: Notification): String? {
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.category == Notification.CATEGORY_CALL ||
            notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.category == Notification.CATEGORY_PROGRESS
        ) return null
        val text = notificationText(notification)
        if (text.isBlank()) return null
        val keywords = otpKeywords.findAll(text).map { it.range.first }.toList()
        if (keywords.isEmpty()) return null
        val candidate = otpCandidate.findAll(text)
            .filterNot { looksLikeYear(it.value) && text.length > 40 }
            .minByOrNull { match -> keywords.minOf { kotlin.math.abs(it - match.range.first) } }
            ?: return null
        return candidate.value.replace("-", "")
    }

    private fun looksLikeYear(value: String): Boolean = value.length == 4 && value.toInt() in 1990..2099

    private fun notificationText(notification: Notification): String {
        val extras = notification.extras
        val parts = mutableListOf<CharSequence?>(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        )
        runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()?.let { (it as? Bundle)?.getCharSequence("text") }
        }.getOrNull()?.let(parts::add)
        return parts.filterNotNull().joinToString("\n")
    }

    // endregion

    fun send(context: Context, intent: PendingIntent?) {
        intent ?: return
        runCatching {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            intent.send(context, 0, null, null, null, null, options)
        }
    }

    fun moduleDrawable(context: Context, resId: Int): Drawable? =
        runCatching { Icon.createWithResource("dev.unvoid.originceiler", resId).loadDrawable(context) }.getOrNull()
}

private fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

private fun circle(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
}

private val ovalOutline = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
}

private const val GREEN = 0xFF34C759.toInt()
private const val RED = 0xFFFF3B30.toInt()
private const val SECONDARY_TEXT = 0x99FFFFFF.toInt()
private const val NEUTRAL_BUTTON = 0x33FFFFFF

/** Expanded island card for an ongoing or incoming call, with the buttons the call notification itself provides. */
class CallIslandView(context: Context, private val title: String) : LinearLayout(context) {
    private val avatar = ImageView(context)
    private val name = TextView(context)
    private val subtitle = TextView(context)
    private val chronometer = Chronometer(context)
    private val buttons = LinearLayout(context)
    private val listener: () -> Unit = { post(::bind) }

    init {
        orientation = VERTICAL
        val pad = context.dp(16f)
        setPadding(pad, context.dp(14f), pad, context.dp(14f))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        avatar.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = ovalOutline
            clipToOutline = true
            background = circle(NEUTRAL_BUTTON)
        }
        header.addView(avatar, LayoutParams(context.dp(48f), context.dp(48f)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        name.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle.apply {
            setTextColor(SECONDARY_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chronometer.apply {
            setTextColor(SECONDARY_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        texts.addView(name)
        texts.addView(subtitle)
        texts.addView(chronometer)
        header.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12f) })
        header.setOnClickListener {
            val card = IslandNotificationCards.get(title) as? IslandNotificationCards.CallCard ?: return@setOnClickListener
            IslandNotificationCards.send(context, card.contentIntent)
        }
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        buttons.orientation = HORIZONTAL
        addView(buttons, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14f)
        })
        bind()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        IslandNotificationCards.addListener(listener)
        bind()
    }

    override fun onDetachedFromWindow() {
        IslandNotificationCards.removeListener(listener)
        chronometer.stop()
        super.onDetachedFromWindow()
    }

    private fun bind() {
        val card = IslandNotificationCards.get(title) as? IslandNotificationCards.CallCard ?: return
        name.text = card.name
        val avatarDrawable = card.avatar?.let { runCatching { it.loadDrawable(context) }.getOrNull() }
            ?: runCatching { context.packageManager.getApplicationIcon(card.packageName) }.getOrNull()
        avatar.setImageDrawable(avatarDrawable)
        if (card.chronometerBase != null) {
            chronometer.base = card.chronometerBase
            chronometer.visibility = VISIBLE
            chronometer.start()
            subtitle.visibility = GONE
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            subtitle.text = card.subtitle ?: "Вызов"
            subtitle.visibility = VISIBLE
        }
        buttons.removeAllViews()
        buttons.visibility = if (card.actions.isEmpty()) GONE else VISIBLE
        card.actions.forEachIndexed { index, action ->
            buttons.addView(actionButton(action), LayoutParams(0, context.dp(44f), 1f).apply {
                if (index > 0) marginStart = context.dp(10f)
            })
        }
    }

    private fun actionButton(action: IslandNotificationCards.CallAction): View {
        val color = when (action.kind) {
            IslandNotificationCards.ActionKind.ANSWER -> GREEN
            IslandNotificationCards.ActionKind.DECLINE -> RED
            IslandNotificationCards.ActionKind.OTHER -> NEUTRAL_BUTTON
        }
        val button = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(color, context.dp(22f).toFloat())
            isClickable = true
            setPadding(context.dp(10f), 0, context.dp(10f), 0)
        }
        val icon = when (action.kind) {
            IslandNotificationCards.ActionKind.OTHER -> action.icon?.let { runCatching { it.loadDrawable(context) }.getOrNull() }
            else -> IslandNotificationCards.moduleDrawable(context, R.drawable.ic_island_call)
        }
        if (icon != null) {
            icon.mutate().setTint(Color.WHITE)
            button.addView(ImageView(context).apply {
                setImageDrawable(icon)
                if (action.kind == IslandNotificationCards.ActionKind.DECLINE) rotation = 135f
            }, LayoutParams(context.dp(20f), context.dp(20f)))
        }
        button.addView(TextView(context).apply {
            text = action.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (icon != null) marginStart = context.dp(6f)
        })
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            IslandNotificationCards.send(context, action.intent)
        }
        return button
    }
}

/** Expanded island card showing a one-time code with a copy button. */
class OtpIslandView(context: Context, private val title: String) : LinearLayout(context) {
    private val appIcon = ImageView(context)
    private val appLabel = TextView(context)
    private val code = TextView(context)
    private val copyGlyph = CopyGlyphView(context)
    private val handler = Handler(Looper.getMainLooper())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = context.dp(16f)
        setPadding(pad, context.dp(14f), context.dp(12f), context.dp(14f))
        appIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(appIcon, LayoutParams(context.dp(36f), context.dp(36f)))

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        appLabel.apply {
            setTextColor(SECONDARY_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        code.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
            maxLines = 1
        }
        texts.addView(appLabel)
        texts.addView(code)
        texts.setOnClickListener {
            val card = IslandNotificationCards.get(title) as? IslandNotificationCards.OtpCard ?: return@setOnClickListener
            IslandNotificationCards.send(context, card.contentIntent)
        }
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12f) })

        val copyButton = FrameLayout(context).apply {
            background = circle(NEUTRAL_BUTTON)
            isClickable = true
            contentDescription = "Копировать код"
            addView(copyGlyph, FrameLayout.LayoutParams(context.dp(22f), context.dp(22f), Gravity.CENTER))
            setOnClickListener { copy(it) }
        }
        addView(copyButton, LayoutParams(context.dp(48f), context.dp(48f)).apply { marginStart = context.dp(8f) })
        bind()
    }

    private fun bind() {
        val card = IslandNotificationCards.get(title) as? IslandNotificationCards.OtpCard ?: return
        appLabel.text = card.appLabel
        code.text = card.code
        appIcon.setImageDrawable(runCatching { context.packageManager.getApplicationIcon(card.packageName) }.getOrNull())
    }

    private fun copy(view: View) {
        val card = IslandNotificationCards.get(title) as? IslandNotificationCards.OtpCard ?: return
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("OTP", card.code))
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        copyGlyph.copied = true
        handler.postDelayed({ card.onDismiss() }, 900L)
    }
}

/** Two overlapping rounded squares (copy), turning into a check mark once copied. */
class CopyGlyphView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val rect = RectF()
    private val path = Path()

    var copied = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = stroke.strokeWidth
        if (copied) {
            stroke.color = GREEN
            path.reset()
            path.moveTo(w * 0.16f, h * 0.52f)
            path.lineTo(w * 0.4f, h * 0.76f)
            path.lineTo(w * 0.86f, h * 0.26f)
            canvas.drawPath(path, stroke)
            return
        }
        stroke.color = Color.WHITE
        val size = w * 0.62f
        val corner = 3f * density
        // Back sheet (top-right): only its visible part is drawn so the front sheet looks stacked on top.
        rect.set(w - size - inset, inset, w - inset, inset + size)
        // Front sheet (bottom-left).
        val front = RectF(inset, h - size - inset, inset + size, h - inset)
        canvas.drawRoundRect(front, corner, corner, stroke)
        path.reset()
        path.moveTo(rect.left, front.top - inset * 0.6f)
        path.lineTo(rect.left, rect.top + corner)
        path.quadTo(rect.left, rect.top, rect.left + corner, rect.top)
        path.lineTo(rect.right - corner, rect.top)
        path.quadTo(rect.right, rect.top, rect.right, rect.top + corner)
        path.lineTo(rect.right, rect.bottom - corner)
        path.quadTo(rect.right, rect.bottom, rect.right - corner, rect.bottom)
        path.lineTo(front.right + inset * 0.6f, rect.bottom)
        canvas.drawPath(path, stroke)
    }
}

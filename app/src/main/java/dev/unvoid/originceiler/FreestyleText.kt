package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/** Strings of the Freestyle editor, in the device language (Russian or English). */
internal object FsText {
    private val ru get() = Locale.getDefault().language == "ru"

    val done get() = if (ru) "Готово" else "Done"
    val reset get() = if (ru) "Сбросить" else "Reset"
    val addControl get() = if (ru) "Добавить элемент" else "Add a Control"
    val galleryTitle get() = if (ru) "Элементы управления" else "Controls"
    val sectionHidden get() = if (ru) "Скрытые виджеты" else "Hidden widgets"
    val sectionSystem get() = if (ru) "Системные" else "System"
    val sectionApps get() = if (ru) "Приложения" else "Apps"
    val vivoEditor get() = if (ru) "Стандартный редактор vivo" else "vivo's own editor"
    val nothingToAdd get() = if (ru) "Все элементы уже в центре управления" else "Every control is already in the control center"
    val notPlaying get() = if (ru) "Не воспроизводится" else "Not Playing"
    val search get() = if (ru) "Поиск элементов" else "Search Controls"
    val nothingFound get() = if (ru) "Ничего не найдено" else "No results"
    val hint get() = if (ru) "Удерживайте и перетаскивайте · тяните за угол, чтобы изменить размер" else "Hold and drag to move · drag a corner to resize"

    fun stateText(state: Int) = when (state) {
        2 -> if (ru) "Вкл." else "On"
        0 -> if (ru) "Недоступно" else "Unavailable"
        else -> if (ru) "Выкл." else "Off"
    }

    fun name(spec: String): String {
        val entry = NAMES[spec] ?: return spec.removePrefix("custom(").removeSuffix(")").substringAfterLast('.')
        return if (ru) entry.second else entry.first
    }

    private val NAMES = mapOf(
        "wifi" to ("Wi-Fi" to "Wi-Fi"),
        "connect_group" to ("Connectivity" to "Сеть"),
        "volume_group" to ("Sound mode" to "Режим звука"),
        "music" to ("Music" to "Музыка"),
        "light_seek_bar" to ("Brightness" to "Яркость"),
        "volume_seek_bar" to ("Volume" to "Громкость"),
        "focus_group" to ("Focus" to "Фокусирование"),
        "vibrate_mode" to ("Sound mode" to "Режим звука"),
        "bluetooth" to ("Bluetooth" to "Bluetooth"),
        "location" to ("Location" to "Геоданные"),
        "portrait_mode" to ("Rotation lock" to "Блок. поворота"),
        "record" to ("Recorder" to "Диктофон"),
        "flashlight" to ("Flashlight" to "Фонарик"),
        "hotspot" to ("Hotspot" to "Точка доступа"),
        "vivo_share" to ("vivo Share" to "vivo Share"),
        "nfc" to ("NFC" to "NFC"),
        "screen_record" to ("Screen recording" to "Запись экрана"),
        "performance_mode" to ("Performance" to "Производительность"),
        "lower_power_mode" to ("Power saving" to "Энергосбережение"),
        "speedup" to ("Speed up" to "Ускорение"),
        "camera" to ("Camera" to "Камера"),
        "smart_short" to ("Screenshot" to "Скриншот"),
        "scanner" to ("Scanner" to "Сканер"),
        "stealth_mode" to ("Privacy mode" to "Режим инкогнито"),
        "cameratoggle" to ("Camera access" to "Доступ к камере"),
        "carsickness" to ("Motion sickness" to "Укачивание"),
        "mictoggle" to ("Mic access" to "Доступ к микрофону"),
        "connect_computer" to ("Connect PC" to "Подключить ПК"),
        "calculator" to ("Calculator" to "Калькулятор"),
        "game_mode" to ("Game mode" to "Игровой режим"),
        "upnp_server" to ("Screen cast" to "Трансляция"),
        "split_screen" to ("Split screen" to "Разделение экрана"),
        "wire" to ("Wired projection" to "Проводная проекция"),
        "cellular" to ("Mobile data" to "Моб. данные"),
        "offline" to ("Airplane mode" to "Авиарежим"),
        "night" to ("Dark mode" to "Тёмная тема"),
        "dnd" to ("Do not disturb" to "Не беспокоить"),
        "dark" to ("Dark mode" to "Тёмная тема"),
        "eye_protection" to ("Eye comfort" to "Защита зрения"),
        "wireless" to ("Wireless projection" to "Беспроводная проекция"),
        "wechat_scan" to ("WeChat scan" to "Сканер WeChat"),
        "wechat_qrcode" to ("WeChat pay code" to "Код оплаты WeChat"),
        "alipay_scan" to ("Alipay scan" to "Сканер Alipay"),
        "alipay_qrcode" to ("Alipay pay code" to "Код оплаты Alipay"),
        "hdr" to ("HDR" to "HDR"),
        "eye_mode" to ("Eye protection" to "Защита зрения"),
        "auto_color_temp" to ("Auto colour temp" to "Автотемпература цвета"),
        "night_mode" to ("Dark mode" to "Тёмная тема"),
        "sps_mode" to ("Super power saving" to "Сверхэкономия"),
        "floatingball" to ("Floating ball" to "Плавающая кнопка"),
        "mini_screen" to ("Mini screen" to "Мини-экран"),
        "driver_mode" to ("Driving mode" to "Режим вождения"),
        "jovi_voice" to ("Jovi voice" to "Голос Jovi"),
        "subtitle" to ("Live captions" to "Субтитры"),
        "screenshot" to ("Screenshot" to "Скриншот"),
        "data_switch" to ("SIM data" to "Данные SIM"),
        "vtouch" to ("vTouch" to "vTouch"),
        "power_off" to ("Power off" to "Выключение"),
        "global_search" to ("Search" to "Поиск"),
        "clean_launcher" to ("Clean up" to "Очистка"),
        "smart_life" to ("Smart home" to "Умный дом"),
        "direct_charge" to ("Bypass charging" to "Прямая зарядка"),
        "screen_lock" to ("Lock screen" to "Блокировка экрана"),
    )

    /** Material Symbols name of a spec's icon. */
    fun glyph(spec: String): String = GLYPHS[spec] ?: "toggle_on"

    private val GLYPHS = mapOf(
        "connect_group" to "wifi", "volume_group" to "notifications", "wifi" to "wifi", "music" to "music_note", "light_seek_bar" to "light_mode", "volume_seek_bar" to "volume_up",
        "focus_group" to "bedtime", "vibrate_mode" to "vibration", "bluetooth" to "bluetooth", "location" to "location_on",
        "portrait_mode" to "screen_lock_rotation", "record" to "mic", "flashlight" to "flashlight_on", "hotspot" to "wifi_tethering",
        "vivo_share" to "share", "nfc" to "nfc", "screen_record" to "screen_record", "performance_mode" to "speed",
        "lower_power_mode" to "battery_saver", "speedup" to "rocket_launch", "camera" to "photo_camera",
        "smart_short" to "screenshot_region", "scanner" to "qr_code_scanner", "stealth_mode" to "visibility_off",
        "cameratoggle" to "no_photography", "carsickness" to "directions_car", "mictoggle" to "mic_off",
        "connect_computer" to "computer", "calculator" to "calculate", "game_mode" to "sports_esports",
        "upnp_server" to "cast", "split_screen" to "splitscreen", "wire" to "cable", "cellular" to "swap_vert",
        "offline" to "flight", "night" to "dark_mode", "dark" to "dark_mode", "dnd" to "do_not_disturb_on",
        "eye_protection" to "visibility",
        "wireless" to "cast",
        "wechat_scan" to "qr_code_scanner",
        "wechat_qrcode" to "qr_code",
        "alipay_scan" to "qr_code_scanner",
        "alipay_qrcode" to "qr_code",
        "hdr" to "hdr_on",
        "eye_mode" to "visibility",
        "auto_color_temp" to "thermostat_auto",
        "night_mode" to "dark_mode",
        "sps_mode" to "battery_alert",
        "floatingball" to "radio_button_checked",
        "mini_screen" to "picture_in_picture",
        "driver_mode" to "directions_car",
        "jovi_voice" to "graphic_eq",
        "subtitle" to "closed_caption",
        "screenshot" to "screenshot_region",
        "data_switch" to "sim_card",
        "vtouch" to "touch_app",
        "power_off" to "power_settings_new",
        "global_search" to "search",
        "clean_launcher" to "mop",
        "smart_life" to "home",
        "direct_charge" to "bolt",
        "screen_lock" to "lock",
    )
}

/** Material Symbols Rounded from the module's assets, for glyphs drawn inside SystemUI. */
internal object FsGlyphs {
    @Volatile private var typeface: Typeface? = null
    private var tried = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun typeface(context: Context): Typeface? {
        typeface?.let { return it }
        if (tried) return null
        tried = true
        typeface = runCatching {
            val module = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            Typeface.createFromAsset(module.assets, "MaterialSymbolsRounded.ttf")
        }.getOrNull()
        return typeface
    }

    fun draw(canvas: Canvas, context: Context, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val tf = typeface(context)
        paint.color = color
        paint.textSize = size
        if (tf != null) {
            paint.typeface = tf
            if (runCatching { paint.hasGlyph(name) }.getOrDefault(true)) {
                canvas.drawText(name, cx, cy + size / 2f, paint)
                return
            }
        }
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * 0.62f
        val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        canvas.drawText(letter, cx, cy + paint.textSize * 0.36f, paint)
    }
}

# OriginCeiler (Origin Root Toolbox)

An advanced LSPosed customization module for rooted Vivo and iQOO devices running OriginOS.

---

## Features

### System UI & Status Bar
- **Status Bar Scaling**: Custom scale adjustments for the clock, battery, Wi-Fi, cellular, and notification icons.
- **AOSP Volume Panel**: Clean volume slider interface replacing the stock overlay.
- **Bootloader Warning Suppression**: Hides the unlocked bootloader warning on device startup.
- **iOS-Style Notifications**: Refined spacing and layout for notification center cards.

### Control Center & Liquid Glass
- **Liquid Glass Shaders**: Real-time frosted glass effect with chromatic dispersion and edge highlights for quick settings tiles, sliders, buttons, and media cards.
- **Real-Time Blur Tuning**: Granular controls for blur intensity, saturation, and glass dispersion.

### BBK Launcher & Theming
- **Circle to Search**: Long press the navigation bar or swipe gestures to trigger Google Circle to Search.
- **Material You Dynamic Theming**: Native AOSP monet color extraction for launcher icons.
- **Custom Icon Scaling**: Independent size sliders for themed icons (65% to 118%) and general launcher icons (70% to 150%).
- **Third-Party Icon Packs**: Apply standard Nova and ADW icon packs directly to the stock launcher.

### Settings Redesign
- **Expressive Settings**: Modern Material You styling with rounded cards, adaptive container padding, and expressive switch toggles.

### Origin Player & Media
- **Universal Player Support**: Removes the stock Vivo whitelist, allowing any third-party media player on desktop widgets, the lock screen, and notification controls.

### Origin Island
- **Live Updates**: Real-time activity tracking within Origin Island for supported media and navigation apps.
- **Universal Progress Capsules**: Converts download and task progress bars into dynamic island capsules.
- **Flashlight Slider**: Granular brightness control in the expanded island view.

### Google & AI Integration
- **Gemini Regional Access**: Custom DNS resolution to bypass regional restrictions for Google AI and Gemini.
- **Persistent Assistant**: Retains Google Assistant as default across device reboots.
- **Power Button Gemini Trigger**: Remaps long press on the power button to launch Gemini or VISTrigger.

### BlueLM Copilot
- **BlueLM Russification**: Full Russian translation for the Vivo Copilot interface.
- **On-Device Whisper STT**: Integrated offline OpenAI Whisper speech recognition replacing cloud voice recognition for Jovi and Copilot.

### System Utilities
- **Package Installer Bypass**: Removes forced system installer restrictions for third-party APK installations.
- **Private DNS Controls**: Enhanced network configuration options.

---

## Requirements

- **Root Access**: APatch, KernelSU, or Magisk
- **LSPosed**: Version 1.9.3 or newer
- **Operating System**: OriginOS 5 or OriginOS 6 (Android 15 / Android 16)

---

## LSPosed Scope

Enable the module for the following packages in LSPosed:

- `com.bbk.launcher2` (Launcher, icon packs, Circle to Search)
- `com.android.systemui` (Status bar, volume panel, unlock banner)
- `com.vivo.systemuiplugin` (Control Center, Liquid Glass, Origin Island)
- `com.android.settings` (Expressive Settings redesign)
- `com.vivo.musicwidgetmix` (Origin Player universal media whitelist)
- `com.vivo.ai.copilot` (BlueLM Copilot, voice recognition)
- `com.google.android.googlequicksearchbox` (Gemini and assistant persistence)
- `com.android.packageinstaller` (Third-party APK installation)
- `com.vivo.upslide` (Navigation bar gestures)
- `com.bbk.theme` (Theme integration)
- `com.android.phone`
- `com.iqoo.powersaving`
- `com.vivo.gamecube`
- `com.vivo.pay`

---

## Installation

1. Install the latest `app-release.apk` on your device.
2. Open **LSPosed**, activate the **OriginCeiler** module, and select the required scope packages listed above.
3. Open **OriginCeiler**, configure your preferred tweaks.
4. Reboot your device to apply all system-level hooks.

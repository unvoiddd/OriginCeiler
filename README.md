````markdown
# Origin Root Toolbox

Origin Root Toolbox is an LSPosed module that brings Pixel-exclusive features and modern Material You customization to rooted Vivo devices running OriginOS.

## Features

- 🤖 Launch **Gemini** by holding the power button.
- 🔍 Fully functional **Circle to Search** by Google.
- 📱 **Pixel Home Layout** with the Google Search bar below the dock, just like on Google Pixel devices.
- 🎨 Native **Material You** icon recoloring using Google's AOSP icon theming algorithm.
- 🖌️ Automatic fallback filtering for unsupported icons to ensure a clean and consistent appearance.
- 📦 Built-in **AOSP system icons** for a Pixel-like look.
- ⚙️ Material You redesign for the **Settings** app.
- 🔊 Optional **AOSP Volume Panel** replacement.
- 🚫 Hide the Vivo bootloader unlock warning shown after unlocking the bootloader.
- 🎵 **Origin Player** support for **all media players**, removing the stock whitelist limitation.
- 🏝️ **Live Updates** support inside the Dynamic Island for all supported media applications.
- ⚡ Deep integration with OriginOS while preserving the native user experience.

## Requirements

- **MiCTS and VSTrigger apps* (djwnload on official MICTS Github repository
- **Root access** (Magisk or APatch)
- **LSPosed 1.9.3+** (latest version recommended)
- **OriginOS 6** (recommended)
- **OriginOS 5** may also work, but it has not been tested yet.

### Required LSPosed Scope

Enable the module for the following packages:

- `com.bbk.launcher2`
- `com.android.settings`
- `com.android.phone`
- `com.android.systemui`
- `com.vivo.systemuiplugin`
- `com.vivo.upslide`
- `com.iqoo.powersaving`
- `com.vivo.gamecube`
- `com.bbk.theme`
- `com.vivo.ai.copilot`
- `com.vivo.pay`

## IMPORTANT

- After enabling the module in **LSPosed**, **reboot your device** for all changes to take effect.
- This module uses the package name **`com.autonavi.minimap`** to bypass the **Origin Island / Origin Player** whitelist. If you already have an application installed with the same package name, **uninstall it before installing this module** to avoid conflicts.
````

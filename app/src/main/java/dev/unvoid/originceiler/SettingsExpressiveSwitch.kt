package dev.unvoid.originceiler

import androidx.compose.runtime.Composable

@Composable
fun SettingsExpressiveSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OriginSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled
    )
}

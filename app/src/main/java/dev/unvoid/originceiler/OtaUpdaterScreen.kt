package dev.unvoid.originceiler

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Page of the "System Update" scope: OTA links, the fake update and updater tools. */
internal fun LazyListScope.updaterScope(state: UiState, model: MainViewModel) {
    val ota = state.ota
    item {
        LaunchedEffect(Unit) { model.loadOta() }
        SettingsGroup(
            "Firmware",
            footer = if (ota.spoofFrom != null) "Fake update is active: the updater thinks ${ota.spoofTo} is installed" else null,
            rows = ota.info.map { (title, value) -> @Composable { OtaInfoRow(title, value) } }
        )
    }
    if (!ota.updaterInstalled) {
        item {
            Text(
                "System Update (com.bbk.updater) is not installed on this device",
                color = OriginRed,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            )
        }
    }
    item {
        SettingsGroup(
            "Update links",
            footer = "Open System Update and check for updates. The link to the update package appears here and in a notification",
            rows = listOf(
                { Toggle("Catch update links", "Save the package link when the updater finds a new version", ota.captureEnabled, model::setOtaCapture) },
                { GroupActionRow("Check for updates", model::openUpdater, enabled = ota.updaterInstalled && !ota.updaterDisabled) }
            )
        )
    }
    item { OtaCapturesGroup(ota, model) }
    item { OtaFakeUpdateGroup(ota, model) }
    item { OtaUpdaterAppGroup(ota, model) }
    item { OtaRebootGroup(model) }
}

@Composable
private fun OtaInfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = OriginTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.width(24.dp))
        Text(
            value,
            color = OriginTextPrimary,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OtaCapturesGroup(ota: OtaState, model: MainViewModel) {
    var confirm by remember { mutableStateOf<OtaCapture?>(null) }
    val rows = buildList<@Composable () -> Unit> {
        if (ota.captures.isEmpty()) {
            add {
                Text(
                    "No update links caught yet",
                    color = OriginTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)
                )
            }
        }
        ota.captures.forEach { capture ->
            add { OtaCaptureRow(capture, ota, model, onFakeUpdate = { confirm = capture }) }
        }
        if (ota.captures.isNotEmpty()) add { GroupActionRow("Clear list", model::clearOtaHistory) }
    }
    SettingsGroup("Caught updates", rows = rows)
    confirm?.let { capture ->
        OtaConfirmDialog(
            title = "Fake update",
            text = "The system and the updater will report ${capture.versionNumber} instead of ${ota.versionNumber}, " +
                "and the updater will offer the update after it. Nothing is installed. You can restore the real version at any time",
            onConfirm = { model.fakeUpdateTo(capture) },
            onDismiss = { confirm = null }
        )
    }
}

@Composable
private fun OtaCaptureRow(capture: OtaCapture, ota: OtaState, model: MainViewModel, onFakeUpdate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            capture.version ?: capture.fileName,
            color = OriginTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        val details = listOfNotNull(
            when {
                capture.incremental -> tr("Incremental")
                capture.full -> tr("Full package")
                else -> null
            },
            capture.size?.let(OtaActions::formatSize),
            DateUtils.getRelativeTimeSpanString(capture.time).toString()
        ).joinToString(" · ")
        Text(details, color = OriginTextSecondary, fontSize = 12.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(capture.url, color = OriginTextTertiary, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        capture.md5?.let {
            Text("${if (it.length == 64) "SHA-256" else "MD5"} $it", color = OriginTextTertiary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (capture.incremental) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Incremental update: it installs only on the real firmware it was built for, a faked version does not count",
                color = OriginRed,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OriginSecondaryButton("Copy link", { model.copyOta(capture) }, Modifier.weight(1f))
            OriginSecondaryButton("Download", { model.downloadOta(capture) }, Modifier.weight(1f))
        }
        val number = capture.versionNumber
        if (number != null && number != ota.versionNumber) {
            Spacer(Modifier.height(8.dp))
            OriginButton("Fake update to $number", onFakeUpdate, Modifier.fillMaxWidth(), enabled = !ota.busy)
        }
    }
}

@Composable
private fun OtaFakeUpdateGroup(ota: OtaState, model: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val valid = OtaVersion.isValidNumber(ota.target) && ota.target != ota.versionNumber
    SettingsGroup(
        "Fake update",
        footer = "Changes the firmware number in every system property that holds it, also after a reboot. " +
            "Install only a full package after this: an incremental update checks the real firmware and fails",
        rows = buildList {
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text("Version to report", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text("Installed: ${ota.versionNumber.ifBlank { "—" }}", color = OriginTextSecondary, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    OtaVersionField(ota.target, model::setOtaTarget)
                    if (ota.preview.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Will change ${ota.preview.size} properties", color = OriginTextSecondary, fontSize = 12.5.sp)
                        ota.preview.forEach {
                            Text(it.key, color = OriginTextTertiary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OriginButton("Apply fake version", { confirm = true }, Modifier.fillMaxWidth(), enabled = valid && !ota.busy)
                }
            }
            if (ota.spoofFrom != null) add { GroupActionRow("Restore real version", model::restoreOtaSpoof, enabled = !ota.busy) }
        }
    )
    if (confirm) {
        OtaConfirmDialog(
            title = "Fake update",
            text = "The system and the updater will report ${ota.target} instead of ${ota.versionNumber}. Nothing is installed",
            onConfirm = model::applyOtaSpoof,
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun OtaVersionField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(shape).background(OriginFillGray, shape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = OriginTextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(OriginAccent),
            decorationBox = { field ->
                if (value.isEmpty()) Text("16.1.12.0", color = OriginTextTertiary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                field()
            }
        )
    }
}

@Composable
private fun OtaUpdaterAppGroup(ota: OtaState, model: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    SettingsGroup(
        "System Update app",
        rows = listOf(
            { Toggle("Disable System Update", "No update checks, downloads or reminders until you turn it back on", ota.updaterDisabled, model::setUpdaterDisabled) },
            { GroupActionRow("Clear updater data", { confirm = true }, enabled = ota.updaterInstalled) }
        )
    )
    if (confirm) {
        OtaConfirmDialog(
            title = "Clear updater data",
            text = "The updater forgets downloaded packages and pending updates and checks again from scratch",
            onConfirm = model::clearUpdaterData,
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun OtaRebootGroup(model: MainViewModel) {
    var target by remember { mutableStateOf<Pair<String, String>?>(null) }
    SettingsGroup(
        "Reboot",
        rows = listOf(
            { GroupActionRow("Reboot to recovery", { target = "recovery" to "Reboot to recovery" }) },
            { GroupActionRow("Reboot to bootloader", { target = "bootloader" to "Reboot to bootloader" }) },
            { GroupActionRow("Soft reboot", { target = "soft" to "Soft reboot" }) }
        )
    )
    target?.let { (id, title) ->
        OtaConfirmDialog(
            title = title,
            text = "Unsaved work in open apps will be lost",
            onConfirm = { model.rebootTo(id) },
            onDismiss = { target = null }
        )
    }
}

@Composable
private fun OtaConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Continue", color = OriginAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OriginTextSecondary) }
        }
    )
}

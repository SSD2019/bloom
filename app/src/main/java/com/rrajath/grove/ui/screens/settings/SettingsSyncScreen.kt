package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SyncMode
import com.rrajath.grove.settings.VaultSource
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.launch

/** Settings § Sync. */
@Composable
fun SettingsSyncScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetSyncMode: (SyncMode) -> Unit,
    onSetPeriodicMinutes: (Int) -> Unit,
    onOpenSyncLog: () -> Unit,
    onSetVaultUri: (String) -> Unit,
    onSetVaultSource: (VaultSource) -> Unit = {},
    onConnectRemoteEmacs: (String) -> Unit = {},
    onTestEmacsConnection: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    onSyncNow: () -> Unit = {},
    pendingCaptureCount: Int = 0,
) {
    val c = MaterialTheme.grove
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var emacsUrlDraft by remember(settings.emacsServerUrl) { mutableStateOf(settings.emacsServerUrl) }
    var testStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            onSetVaultUri(uri.toString())
        }
    }

    SettingsPageScaffold(title = "Sync", onBack = onBack) {
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Vault source",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SegmentedControl(
                    options = VaultSource.entries.map { it.label },
                    selectedIndex = VaultSource.entries.indexOf(settings.vaultSource).coerceAtLeast(0),
                    onSelect = { onSetVaultSource(VaultSource.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            if (settings.vaultSource == VaultSource.LOCAL_SAF) {
                SettingsRow(label = "Folder", onClick = { folderPicker.launch(null) }) {
                    Text(
                        settings.vaultTreeUri?.let { uriDisplayName(it) } ?: "tap to choose",
                        fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                    )
                }
            } else {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    Text(
                        "Remote Emacs server (WireGuard)",
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Load emacs/bloom-server.el in Emacs and run M-x bloom-server-start.",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = emacsUrlDraft,
                        onValueChange = {
                            emacsUrlDraft = it
                            testStatus = null
                        },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://10.0.0.1:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            enabled = !testing && emacsUrlDraft.isNotBlank(),
                            onClick = {
                                testing = true
                                scope.launch {
                                    val res = onTestEmacsConnection(emacsUrlDraft)
                                    testStatus = res.fold(
                                        onSuccess = { true to it },
                                        onFailure = { false to (it.message ?: "Connection failed") },
                                    )
                                    testing = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (testing) "Testing…" else "Test Connection")
                        }
                        Button(
                            enabled = emacsUrlDraft.isNotBlank(),
                            onClick = { onConnectRemoteEmacs(emacsUrlDraft) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Save & Sync")
                        }
                    }
                    testStatus?.let { (ok, msg) ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            fontFamily = PlexMono,
                            fontSize = 11.5.sp,
                            color = if (ok) c.green else c.red,
                        )
                    }
                    if (pendingCaptureCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$pendingCaptureCount offline capture(s) queued for sync",
                            fontFamily = PlexSans,
                            fontSize = 12.5.sp,
                            color = c.accent,
                        )
                    }
                }
                RowDivider()
                SettingsRow(label = "Sync now (pull settings & notes)", onClick = onSyncNow) {
                    Text("↻", fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
                }
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Auto-sync",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SyncMode.entries.forEach { mode ->
                    val active = settings.syncMode == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) c.accentSoft else c.surface)
                            .clickable { onSetSyncMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            mode.label,
                            fontFamily = PlexSans,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.5.sp,
                            color = if (active) c.accent else c.ink,
                        )
                    }
                }
            }
            if (settings.syncMode == SyncMode.PERIODIC) {
                RowDivider()
                SettingsRow(label = "Interval") {
                    SegmentedControl(
                        options = listOf("15m", "30m", "60m"),
                        selectedIndex = when (settings.periodicSyncMinutes) {
                            15 -> 0
                            60 -> 2
                            else -> 1
                        },
                        onSelect = { onSetPeriodicMinutes(listOf(15, 30, 60)[it]) },
                        modifier = Modifier.width(180.dp),
                    )
                }
            }
            RowDivider()
            SettingsRow(label = "View sync log", onClick = onOpenSyncLog) {
                Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
            }
        }
    }
}

package com.izumiapps.cameramute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.device.VerifiedDevices

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onEnableAssist: () -> Unit,
    onRestoreGenuine: () -> Unit,
    onRefresh: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAcceptSafety: () -> Unit,
    onOpenSoundSettings: () -> Unit = {},
) {
    var showShizukuHelp by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showCompatibilityHelp by rememberSaveable { mutableStateOf(false) }
    var showFontLicense by rememberSaveable { mutableStateOf(false) }

    var safetyDeferred by rememberSaveable(uiState.deviceModel, uiState.deviceDisplay) { mutableStateOf(false) }
    val needsSafety = !uiState.consentAccepted && uiState.primaryStatus() == PrimaryStatus.GENUINE
    val anotherDialogOpen = showShizukuHelp || showPrivacy || showCompatibilityHelp ||
        showFontLicense
    if (needsSafety && !safetyDeferred && !anotherDialogOpen) {
        AlertDialog(
            onDismissRequest = { safetyDeferred = true },
            title = { Text(stringResource(R.string.ui_safety_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ui_safety_intro))
                    WarningSection()
                    Text(stringResource(R.string.ui_safety_next_steps))
                    uiState.lastMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = onAcceptSafety, enabled = !uiState.isBusy) { Text(stringResource(R.string.ui_action_accept_safety)) }
            },
            dismissButton = {
                TextButton(onClick = { safetyDeferred = true }) { Text(stringResource(R.string.ui_action_later)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    ) { padding ->
        if (showShizukuHelp) {
            ShizukuHelpDialog(onDismiss = { showShizukuHelp = false })
        }
        if (showFontLicense) {
            FontLicenseDialog(onDismiss = { showFontLicense = false })
        }
        if (showCompatibilityHelp) {
            AlertDialog(
                onDismissRequest = { showCompatibilityHelp = false },
                title = { Text(stringResource(R.string.ui_compatibility_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.ui_compatibility_body,
                            VerifiedDevices.all.joinToString("\n") { "${it.model} / ${it.display}" },
                        ),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { showCompatibilityHelp = false }) { Text(stringResource(R.string.action_close)) } },
            )
        }
        if (showPrivacy) {
            PrivacyPolicyDialog(onDismiss = { showPrivacy = false })
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.affiliation_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusCard(uiState)
            DeviceInfoText(uiState)
            TextButton(onClick = { showCompatibilityHelp = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_compatibility_title))
            }
            TextButton(onClick = { showShizukuHelp = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_shizuku_help))
            }
            EnablePreparationCard(
                step = uiState.enableStep(),
                onConsent = { safetyDeferred = false },
                onNotify = onRequestNotificationPermission,
                onSound = onOpenSoundSettings,
                onOpenShizuku = onOpenShizuku,
                onAllowShizuku = onRequestShizukuPermission,
            )
            Button(onClick = onEnableAssist, enabled = uiState.canEnable(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_enable))
            }
            // 復元・再取得は注意事項より先に置く。
            Button(onClick = onRestoreGenuine, enabled = uiState.canRestore(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_restore))
            }
            OutlinedButton(onClick = onRefresh, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_refresh))
            }

            uiState.lastMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (uiState.isBusy) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                }
            }

            when (uiState.primaryStatus()) {
                PrimaryStatus.SHIZUKU_DISCONNECTED -> {
                    OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_open_shizuku))
                    }
                }
                PrimaryStatus.SHIZUKU_PERMISSION_DENIED -> {
                    Button(
                        onClick = onRequestShizukuPermission,
                        enabled = uiState.canRequestShizukuPermission(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_request_shizuku_permission))
                    }
                }
                else -> Unit
            }

            if (!uiState.notificationPermissionGranted) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.ui_notifications_required))
                        TextButton(onClick = onRequestNotificationPermission) {
                            Text(stringResource(R.string.ui_action_allow_notifications))
                        }
                        TextButton(onClick = onOpenNotificationSettings) { Text(stringResource(R.string.ui_action_open_notification_settings)) }
                    }
                }
            }

            if (uiState.deviceUnverified) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ui_unverified_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ui_unverified_body))
                        Text(stringResource(R.string.ui_unverified_audio_caution))
                    }
                }
            } else {
                Text(stringResource(R.string.ui_verified_method_notice), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            WarningSection()
            HorizontalDivider()

            Text(stringResource(R.string.ui_check_actual_audio), style = MaterialTheme.typography.bodySmall)
            if (!uiState.silentOrVibrate) {
                Text(stringResource(R.string.ui_silent_mode_reminder))
            }
            TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_action_privacy_policy)) }
            TextButton(onClick = { showFontLicense = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_action_font_license)) }
        }
    }
}

@Composable
private fun StatusCard(uiState: MainUiState) {
    val status = uiState.primaryStatus()
    val label = when (status) {
        PrimaryStatus.GENUINE -> stringResource(R.string.state_genuine)
        PrimaryStatus.MUTE_ASSIST_ACTIVE -> stringResource(R.string.state_mute_assist)
        PrimaryStatus.UNEXPECTED_VALUE -> stringResource(R.string.state_unexpected_value)
        PrimaryStatus.UNKNOWN -> stringResource(R.string.state_unknown)
        PrimaryStatus.SHIZUKU_DISCONNECTED -> stringResource(R.string.state_shizuku_disconnected)
        PrimaryStatus.SHIZUKU_PERMISSION_DENIED -> stringResource(R.string.state_shizuku_permission_denied)
        PrimaryStatus.SHIZUKU_UNSUPPORTED_BACKEND -> stringResource(R.string.state_shizuku_unsupported_backend)
        PrimaryStatus.UNMANAGED_UNFORCED -> stringResource(R.string.state_unmanaged_unforced)
    }
    val containerColor = when (status) {
        PrimaryStatus.GENUINE -> MaterialTheme.colorScheme.secondaryContainer
        PrimaryStatus.MUTE_ASSIST_ACTIVE, PrimaryStatus.UNEXPECTED_VALUE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            if (status == PrimaryStatus.MUTE_ASSIST_ACTIVE) {
                Text(stringResource(R.string.warning_active_banner), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ui_manual_restore_reminder), style = MaterialTheme.typography.bodySmall)
            }
            if (status == PrimaryStatus.UNMANAGED_UNFORCED) {
                Text(stringResource(R.string.ui_unmanaged_unforced_guidance))
            }
        }
    }
}

@Composable
private fun DeviceInfoText(uiState: MainUiState) {
    Text(
        stringResource(R.string.ui_device_details, uiState.deviceModel, uiState.deviceDisplay),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WarningSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.warning_respect_privacy), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.warning_scope), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.warning_silent_recommended), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.warning_force_stop), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.warning_manual_restore), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.warning_untested_scope), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EnablePreparationCard(
    step: EnableStep,
    onConsent: () -> Unit,
    onNotify: () -> Unit,
    onSound: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
) {
    val messageRes = when (step) {
        EnableStep.CHECKING -> R.string.ui_preparation_checking
        EnableStep.CHANGE_SHIZUKU_BACKEND -> R.string.shizuku_unsupported_backend_guidance
        EnableStep.CONNECT_SHIZUKU -> R.string.ui_preparation_connect_shizuku
        EnableStep.ALLOW_SHIZUKU -> R.string.ui_preparation_allow_shizuku
        EnableStep.READ_STATE -> R.string.ui_preparation_read_state
        EnableStep.RESTORE -> R.string.ui_preparation_restore
        EnableStep.UNMANAGED -> R.string.ui_preparation_unmanaged
        EnableStep.UNEXPECTED -> R.string.ui_preparation_unexpected
        EnableStep.CONSENT -> R.string.ui_preparation_consent
        EnableStep.NOTIFICATIONS -> R.string.ui_preparation_notifications
        EnableStep.SILENT_MODE -> R.string.ui_preparation_silent_mode
        EnableStep.READY -> R.string.ui_preparation_ready
    }
    val action = when (step) {
        EnableStep.CONSENT -> R.string.ui_action_review_safety to onConsent
        EnableStep.NOTIFICATIONS -> R.string.ui_action_allow_status_notifications to onNotify
        EnableStep.SILENT_MODE -> R.string.ui_action_open_sound_settings to onSound
        EnableStep.CONNECT_SHIZUKU, EnableStep.CHANGE_SHIZUKU_BACKEND ->
            R.string.ui_action_prepare_shizuku to onOpenShizuku
        EnableStep.ALLOW_SHIZUKU -> R.string.ui_action_allow_shizuku_access to onAllowShizuku
        else -> null
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(messageRes))
            action?.let { (labelRes, callback) ->
                Button(onClick = callback, modifier = Modifier.fillMaxWidth()) { Text(stringResource(labelRes)) }
            }
        }
    }
}

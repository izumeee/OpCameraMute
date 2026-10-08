package com.izumiapps.cameramute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.izumiapps.cameramute.R

/**
 * Shizuku導入手順の説明。詰まりやすいOPPO/ColorOS特有の注意点
 * ("認可の監視を無効化" / "Disable permission monitoring") を含む。[ShizukuHelpDialog] の本文として使う。
 * 内容が画面高さを超えることがあるため、縦スクロール可能にしてある。
 */
@Composable
fun ShizukuHelpContent() {
    Column(
        modifier = Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpStep(
            title = stringResource(R.string.shizuku_help_install_title),
            body = stringResource(R.string.shizuku_help_install_body),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shizuku_help_oppo_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.shizuku_help_oppo_setup_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HelpStep(
            title = stringResource(R.string.shizuku_help_start_title),
            body = stringResource(R.string.shizuku_help_start_body),
        )
        HelpStep(
            title = stringResource(R.string.shizuku_help_authorize_title),
            body = stringResource(R.string.shizuku_help_authorize_body),
        )
    }
}

@Composable
private fun HelpStep(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ShizukuHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shizuku_help_title)) },
        text = { ShizukuHelpContent() },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

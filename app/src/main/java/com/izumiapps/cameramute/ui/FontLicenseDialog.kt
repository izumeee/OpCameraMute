package com.izumiapps.cameramute.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.izumiapps.cameramute.R

@Composable
fun FontLicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val license = remember(context) {
        context.assets.open("licenses/noto_sans_jp_OFL.txt").bufferedReader().use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_action_font_license)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Noto Sans JP", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(license, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

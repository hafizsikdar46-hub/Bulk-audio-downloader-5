package com.example.mediadownloader.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.mediadownloader.ui.DuplicatePrompt

@Composable
fun DuplicateDialog(
    prompt: DuplicatePrompt,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Already Downloaded")
        },
        text = {
            Text(
                "\"${prompt.title}\" has already been downloaded previously.\n\nWould you like to download it again?"
            )
        },
        confirmButton = {
            Button(
                onClick = prompt.onConfirm,
                modifier = Modifier.testTag("duplicate_download_again_btn")
            ) {
                Text("Download Again")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("duplicate_cancel_btn")
            ) {
                Text("Cancel")
            }
        }
    )
}

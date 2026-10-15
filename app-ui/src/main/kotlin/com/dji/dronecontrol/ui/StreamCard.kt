package com.dji.dronecontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.dronecontrol.core.model.StreamSessionState

@Composable
fun StreamCard(
    session: StreamSessionState,
    onStartAgoraStream: (channel: String, optionalToken: String?) -> Unit,
    onStopStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var testChannel by remember { mutableStateOf("") }
    var overrideToken by remember { mutableStateOf("") }
    var showTokenOverride by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Live stream (Agora)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(session.state.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text("Channel: ${session.channel ?: "—"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Viewer: ${session.viewerUrl ?: "—"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (!session.viewerUrl.isNullOrBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(session.viewerUrl!!)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy viewer URL")
                    }
                }
            }
            session.errorCode?.let {
                Text("Error: $it ${session.errorMessage ?: ""}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Enter channel — publisher token is fetched from your configured token endpoint.",
                style = MaterialTheme.typography.labelSmall
            )
            OutlinedTextField(
                value = testChannel,
                onValueChange = { testChannel = it },
                label = { Text("Channel") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { showTokenOverride = !showTokenOverride }) {
                Text(if (showTokenOverride) "Hide manual token override" else "Manual token override (optional)")
            }
            if (showTokenOverride) {
                OutlinedTextField(
                    value = overrideToken,
                    onValueChange = { overrideToken = it },
                    label = { Text("Publisher token (memory only)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (testChannel.isNotBlank()) {
                            val token = overrideToken.takeIf { it.isNotBlank() }
                            onStartAgoraStream(testChannel, token)
                        }
                    },
                    enabled = testChannel.isNotBlank()
                ) {
                    Text("Start stream")
                }
                OutlinedButton(onClick = onStopStream) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

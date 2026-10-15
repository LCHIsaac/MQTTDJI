package com.dji.dronecontrol.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dji.dronecontrol.core.port.MediaAssetType
import com.dji.dronecontrol.core.port.MediaDeleteResult
import com.dji.dronecontrol.core.port.MediaDownloadResult
import com.dji.dronecontrol.core.port.MediaLibraryEntry
import com.dji.dronecontrol.core.port.MediaLibraryPort
import com.dji.dronecontrol.core.port.MediaListFilter
import com.dji.dronecontrol.core.port.MediaListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaLibraryScreen(
    aircraftReady: Boolean,
    mediaLibrary: MediaLibraryPort,
    onOpenConnection: () -> Unit,
    onUploadPhoto: suspend (localPath: String, fileIndex: Int) -> Result<String>,
    onUploadVideo: suspend (localPath: String, fileIndex: Int) -> Result<String>,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(MediaListFilter.ALL) }
    var entries by remember { mutableStateOf<List<MediaLibraryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf<List<MediaLibraryEntry>?>(null) }
    var photoPreview by remember { mutableStateOf<Pair<String, MediaLibraryEntry>?>(null) }
    var videoPlayer by remember { mutableStateOf<Pair<String, MediaLibraryEntry>?>(null) }
    var linkResult by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (!aircraftReady || busy) return
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    mediaLibrary.refreshList(filter)
                }
                when (result) {
                    is MediaListResult.Success -> entries = result.entries
                    is MediaListResult.Failure -> error = result.message
                }
            } catch (t: Throwable) {
                error = t.message ?: "Media refresh failed"
                entries = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(aircraftReady, filter) {
        if (aircraftReady) refresh()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Media on aircraft") },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectionMode = false
                                selected = emptySet()
                            },
                            enabled = !busy,
                        ) { Text("Cancel") }
                        IconButton(
                            onClick = {
                                val toDelete = entries.filter { selected.contains(it.fileIndex) }
                                if (toDelete.isNotEmpty()) confirmDelete = toDelete
                            },
                            enabled = selected.isNotEmpty() && !busy,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        IconButton(onClick = { refresh() }, enabled = aircraftReady && !busy) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        TextButton(
                            onClick = { selectionMode = true },
                            enabled = aircraftReady && entries.isNotEmpty() && !busy,
                        ) { Text("Select") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            if (!aircraftReady) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Waiting for aircraft", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connect and link the product before browsing SD card media.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenConnection) { Text("Open Connection") }
                    }
                }
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == MediaListFilter.ALL, onClick = { filter = MediaListFilter.ALL }, label = { Text("All") })
                FilterChip(selected = filter == MediaListFilter.PHOTOS, onClick = { filter = MediaListFilter.PHOTOS }, label = { Text("Photos") })
                FilterChip(selected = filter == MediaListFilter.VIDEOS, onClick = { filter = MediaListFilter.VIDEOS }, label = { Text("Videos") })
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    entries,
                    key = { entry -> "${entry.fileIndex}_${entry.fileName}_${entry.type}" },
                ) { entry ->
                    val checked = selected.contains(entry.fileIndex)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = !busy,
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (checked) selected - entry.fileIndex else selected + entry.fileIndex
                                    } else {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val dl = withContext(Dispatchers.IO) {
                                                    mediaLibrary.downloadOriginal(entry)
                                                }
                                                when (dl) {
                                                    is MediaDownloadResult.Success -> {
                                                        if (entry.type == MediaAssetType.PHOTO) {
                                                            photoPreview = dl.localPath to entry
                                                        } else {
                                                            videoPlayer = dl.localPath to entry
                                                        }
                                                    }
                                                    is MediaDownloadResult.Failure -> {
                                                        snackbar.showSnackbar(dl.message)
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                snackbar.showSnackbar(t.message ?: "Download failed")
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selected = selected + entry.fileIndex
                                },
                            ),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (it) selected + entry.fileIndex else selected - entry.fileIndex
                                    },
                                    enabled = !busy,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.fileName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${entry.type.name.lowercase(Locale.US)} · ${formatBytes(entry.sizeBytes)} · #${entry.fileIndex}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!selectionMode) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            when (val dl = mediaLibrary.downloadOriginal(entry)) {
                                                is MediaDownloadResult.Failure -> snackbar.showSnackbar(dl.message)
                                                is MediaDownloadResult.Success -> {
                                                    val upload = when (entry.type) {
                                                        MediaAssetType.PHOTO -> onUploadPhoto(dl.localPath, entry.fileIndex)
                                                        MediaAssetType.VIDEO -> onUploadVideo(dl.localPath, entry.fileIndex)
                                                    }
                                                    upload.onSuccess { linkResult = it }
                                                        .onFailure { snackbar.showSnackbar(it.message ?: "Upload failed") }
                                                }
                                            }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = "Get link")
                                }
                                IconButton(
                                    onClick = { confirmDelete = listOf(entry) },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = null },
            title = { Text("Delete on aircraft?") },
            text = { Text("Delete ${targets.size} file(s) from the drone SD card. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            when (val result = mediaLibrary.deleteEntries(targets)) {
                                is MediaDeleteResult.Success -> {
                                    entries = entries.filterNot { e -> targets.any { it.fileIndex == e.fileIndex } }
                                    selected = emptySet()
                                    selectionMode = false
                                    snackbar.showSnackbar("Deleted ${result.deletedCount} file(s)")
                                }
                                is MediaDeleteResult.PartialFailure -> {
                                    snackbar.showSnackbar("Deleted ${result.deletedCount}; ${result.failures.size} failed")
                                }
                                is MediaDeleteResult.Failure -> snackbar.showSnackbar(result.message)
                            }
                            busy = false
                            confirmDelete = null
                        }
                    },
                    enabled = !busy,
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }

    photoPreview?.let { (path, entry) ->
        AlertDialog(
            onDismissRequest = { photoPreview = null },
            title = { Text(entry.fileName) },
            text = {
                val bitmap = remember(path) {
                    if (path.startsWith("mock://")) null else BitmapFactory.decodeFile(path)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = entry.fileName,
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Preview unavailable for $path")
                }
            },
            confirmButton = {
                TextButton(onClick = { photoPreview = null }) { Text("Close") }
            },
        )
    }

    videoPlayer?.let { (path, entry) ->
        VideoPlayerDialog(path = path, title = entry.fileName, onDismiss = { videoPlayer = null })
    }

    linkResult?.let { url ->
        AlertDialog(
            onDismissRequest = { linkResult = null },
            title = { Text("Download link") },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = { linkResult = null }) { Text("Close") }
            },
        )
    }

    if (busy) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun VideoPlayerDialog(path: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(path) {
        if (!path.startsWith("mock://")) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            player.release()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (path.startsWith("mock://")) {
                Text("Mock video path — use a real device to play pulled MP4.")
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

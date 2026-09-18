package com.example.mediadownloader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediadownloader.ui.components.ActiveDownloadCard
import com.example.mediadownloader.ui.components.CompletedDownloadCard
import com.example.mediadownloader.ui.components.DuplicateDialog
import com.example.mediadownloader.ui.components.FailedDownloadCard
import com.example.mediadownloader.ui.components.PlaylistSelectionSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()
    val playlistModalState by viewModel.playlistModalState.collectAsState()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsState()

    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val completedDownloads by viewModel.completedDownloads.collectAsState()
    val failedDownloads by viewModel.failedDownloads.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Notification Permission for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Gracefully handle permission result
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Automatically switch to Active tab when new download starts
    LaunchedEffect(activeDownloads.size) {
        if (activeDownloads.isNotEmpty() && selectedTabIndex != 0) {
            selectedTabIndex = 0
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Media Downloader",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. URL Input & Format Card
                UrlInputCard(
                    url = urlInput,
                    selectedFormat = selectedFormat,
                    isAnalyzing = isAnalyzing,
                    analysisError = analysisError,
                    onUrlChange = viewModel::onUrlChange,
                    onFormatChange = viewModel::onFormatChange,
                    onAnalyze = viewModel::analyzeAndDownload,
                    onPaste = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text?.toString() ?: ""
                            viewModel.onUrlChange(text)
                        }
                    },
                    onClear = { viewModel.onUrlChange("") }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Tabs: Active, Completed, Failed
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Active", fontWeight = FontWeight.SemiBold)
                                if (activeDownloads.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Badge { Text("${activeDownloads.size}") }
                                }
                            }
                        },
                        modifier = Modifier.testTag("tab_active")
                    )

                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Completed", fontWeight = FontWeight.SemiBold)
                                if (completedDownloads.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ) { Text("${completedDownloads.size}") }
                                }
                            }
                        },
                        modifier = Modifier.testTag("tab_completed")
                    )

                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Failed", fontWeight = FontWeight.SemiBold)
                                if (failedDownloads.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) { Text("${failedDownloads.size}") }
                                }
                            }
                        },
                        modifier = Modifier.testTag("tab_failed")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Tab Content
                when (selectedTabIndex) {
                    0 -> {
                        ActiveDownloadsList(
                            items = activeDownloads,
                            onCancel = viewModel::cancelDownload,
                            onCancelAll = viewModel::cancelAllActive
                        )
                    }
                    1 -> {
                        CompletedDownloadsList(
                            items = completedDownloads,
                            onOpen = { viewModel.openMedia(context, it) },
                            onShare = { viewModel.shareMedia(context, it) },
                            onDelete = { viewModel.deleteDownload(it, context) },
                            onClearCompleted = viewModel::clearCompleted
                        )
                    }
                    2 -> {
                        FailedDownloadsList(
                            items = failedDownloads,
                            onRetry = viewModel::retryDownload,
                            onRetryAll = viewModel::retryAllFailed,
                            onDelete = { viewModel.deleteDownload(it, context) }
                        )
                    }
                }
            }
        }
    }

    // Playlist Modal Bottom Sheet
    playlistModalState?.let { state ->
        PlaylistSelectionSheet(
            state = state,
            sheetState = sheetState,
            onToggleItem = viewModel::togglePlaylistItem,
            onSelectAll = viewModel::selectAllPlaylistItems,
            onDeselectAll = viewModel::deselectAllPlaylistItems,
            onFormatChange = viewModel::updatePlaylistModalFormat,
            onConfirmDownload = viewModel::downloadSelectedPlaylistItems,
            onDismiss = viewModel::dismissPlaylistModal
        )
    }

    // Duplicate Download Dialog
    duplicatePrompt?.let { prompt ->
        DuplicateDialog(
            prompt = prompt,
            onDismiss = viewModel::dismissDuplicatePrompt
        )
    }
}

@Composable
private fun UrlInputCard(
    url: String,
    selectedFormat: String,
    isAnalyzing: Boolean,
    analysisError: String?,
    onUrlChange: (String) -> Unit,
    onFormatChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Text field
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("Enter video or media URL") },
                placeholder = { Text("https://www.youtube.com/... or direct media URL") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("url_input_field"),
                shape = RoundedCornerShape(12.dp),
                singleLine = false,
                maxLines = 3,
                trailingIcon = {
                    Row {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = onClear, modifier = Modifier.testTag("clear_url_btn")) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear URL")
                            }
                        }
                        IconButton(onClick = onPaste, modifier = Modifier.testTag("paste_url_btn")) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste URL")
                        }
                    }
                }
            )

            if (analysisError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = analysisError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Format Selector & Download Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Format chips: MP3 / MP4
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedFormat.equals("MP3", ignoreCase = true),
                        onClick = { onFormatChange("MP3") },
                        label = { Text("MP3 (Audio)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("format_mp3_chip")
                    )

                    FilterChip(
                        selected = selectedFormat.equals("MP4", ignoreCase = true),
                        onClick = { onFormatChange("MP4") },
                        label = { Text("MP4 (Video)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("format_mp4_chip")
                    )
                }

                // Analyze & Download Button
                Button(
                    onClick = onAnalyze,
                    enabled = !isAnalyzing && url.trim().isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("analyze_download_btn")
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadsList(
    items: List<com.example.mediadownloader.data.db.DownloadItemEntity>,
    onCancel: (Long) -> Unit,
    onCancelAll: () -> Unit
) {
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Default.HourglassEmpty,
            title = "No Active Downloads",
            subtitle = "Paste a media link above to begin downloading."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Queue (${items.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = onCancelAll,
                        modifier = Modifier.testTag("cancel_all_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.StopCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                ActiveDownloadCard(
                    item = item,
                    onCancel = { onCancel(item.id) }
                )
            }
        }
    }
}

@Composable
private fun CompletedDownloadsList(
    items: List<com.example.mediadownloader.data.db.DownloadItemEntity>,
    onOpen: (com.example.mediadownloader.data.db.DownloadItemEntity) -> Unit,
    onShare: (com.example.mediadownloader.data.db.DownloadItemEntity) -> Unit,
    onDelete: (com.example.mediadownloader.data.db.DownloadItemEntity) -> Unit,
    onClearCompleted: () -> Unit
) {
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Default.DownloadDone,
            title = "No Completed Downloads",
            subtitle = "Completed media files will be saved separately and listed here."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Downloaded Media (${items.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = onClearCompleted,
                        modifier = Modifier.testTag("clear_completed_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear History")
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                CompletedDownloadCard(
                    item = item,
                    onOpen = { onOpen(item) },
                    onShare = { onShare(item) },
                    onDelete = { onDelete(item) }
                )
            }
        }
    }
}

@Composable
private fun FailedDownloadsList(
    items: List<com.example.mediadownloader.data.db.DownloadItemEntity>,
    onRetry: (Long) -> Unit,
    onRetryAll: () -> Unit,
    onDelete: (com.example.mediadownloader.data.db.DownloadItemEntity) -> Unit
) {
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ErrorOutline,
            title = "No Failed Downloads",
            subtitle = "Downloads that encounter issues or restrictions will appear here with retry options."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Failed Downloads (${items.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    TextButton(
                        onClick = onRetryAll,
                        modifier = Modifier.testTag("retry_all_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry All Failed")
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                FailedDownloadCard(
                    item = item,
                    onRetry = { onRetry(item.id) },
                    onDelete = { onDelete(item) }
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

package com.example.mediadownloader.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediadownloader.data.db.DownloadItemEntity
import com.example.mediadownloader.data.db.DownloadStatus
import com.example.mediadownloader.data.repository.DownloadRepository
import com.example.mediadownloader.downloader.ActiveDownloadProgress
import com.example.mediadownloader.downloader.DownloadManager
import com.example.mediadownloader.resolver.CompositeMediaResolver
import com.example.mediadownloader.resolver.PlaylistItem
import com.example.mediadownloader.resolver.PlaylistMetadata
import com.example.mediadownloader.resolver.ResolveResult
import com.example.mediadownloader.storage.MediaStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistModalState(
    val metadata: PlaylistMetadata,
    val items: List<PlaylistItem>,
    val format: String
)

data class DuplicatePrompt(
    val title: String,
    val sourceUrl: String,
    val format: String,
    val onConfirm: () -> Unit
)

class MainViewModel(
    private val repository: DownloadRepository,
    private val resolver: CompositeMediaResolver,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _selectedFormat = MutableStateFlow("MP3") // "MP3" or "MP4"
    val selectedFormat: StateFlow<String> = _selectedFormat.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _playlistModalState = MutableStateFlow<PlaylistModalState?>(null)
    val playlistModalState: StateFlow<PlaylistModalState?> = _playlistModalState.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePrompt?> = _duplicatePrompt.asStateFlow()

    val activeDownloads: StateFlow<List<DownloadItemEntity>> = repository.activeDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads: StateFlow<List<DownloadItemEntity>> = repository.completedDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedDownloads: StateFlow<List<DownloadItemEntity>> = repository.failedDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProgress: StateFlow<ActiveDownloadProgress?> = downloadManager.activeProgress

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
        _analysisError.value = null
    }

    fun onFormatChange(format: String) {
        _selectedFormat.value = format
    }

    fun clearAnalysisError() {
        _analysisError.value = null
    }

    fun analyzeAndDownload() {
        val rawUrl = _urlInput.value.trim()
        if (rawUrl.isEmpty()) {
            _analysisError.value = "Please enter or paste a valid media URL"
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null

            try {
                // Check duplicate check
                val existing = repository.getByUrl(rawUrl)
                val completedExisting = existing.firstOrNull { it.status == DownloadStatus.COMPLETED }
                if (completedExisting != null) {
                    _duplicatePrompt.value = DuplicatePrompt(
                        title = completedExisting.title,
                        sourceUrl = rawUrl,
                        format = _selectedFormat.value,
                        onConfirm = {
                            _duplicatePrompt.value = null
                            proceedResolution(rawUrl, _selectedFormat.value)
                        }
                    )
                    _isAnalyzing.value = false
                    return@launch
                }

                proceedResolution(rawUrl, _selectedFormat.value)
            } catch (e: Exception) {
                _analysisError.value = e.localizedMessage ?: "Unexpected analysis error"
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    private fun proceedResolution(url: String, format: String) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null

            val result = resolver.resolve(url, format)
            _isAnalyzing.value = false

            when (result) {
                is ResolveResult.SingleMedia -> {
                    val media = result.media
                    val entity = DownloadItemEntity(
                        title = media.title,
                        sourceUrl = media.sourceUrl,
                        resolvedUrl = media.directDownloadUrl,
                        format = format,
                        thumbnailUrl = media.thumbnailUrl,
                        duration = media.durationSeconds,
                        status = DownloadStatus.PENDING
                    )
                    downloadManager.enqueue(entity)
                    _urlInput.value = ""
                }
                is ResolveResult.Playlist -> {
                    _playlistModalState.value = PlaylistModalState(
                        metadata = result.playlist,
                        items = result.playlist.items.map { it.copy(isSelected = true) },
                        format = format
                    )
                }
                is ResolveResult.Restricted -> {
                    _analysisError.value = "Content Restricted: ${result.reason}"
                }
                is ResolveResult.Unsupported -> {
                    _analysisError.value = "Unsupported Source: ${result.reason}"
                }
                is ResolveResult.Error -> {
                    _analysisError.value = "Error: ${result.message}"
                }
            }
        }
    }

    fun togglePlaylistItem(itemId: String) {
        val current = _playlistModalState.value ?: return
        val updatedItems = current.items.map {
            if (it.id == itemId) it.copy(isSelected = !it.isSelected) else it
        }
        _playlistModalState.value = current.copy(items = updatedItems)
    }

    fun selectAllPlaylistItems() {
        val current = _playlistModalState.value ?: return
        val updatedItems = current.items.map { it.copy(isSelected = true) }
        _playlistModalState.value = current.copy(items = updatedItems)
    }

    fun deselectAllPlaylistItems() {
        val current = _playlistModalState.value ?: return
        val updatedItems = current.items.map { it.copy(isSelected = false) }
        _playlistModalState.value = current.copy(items = updatedItems)
    }

    fun updatePlaylistModalFormat(format: String) {
        val current = _playlistModalState.value ?: return
        _playlistModalState.value = current.copy(format = format)
    }

    fun downloadSelectedPlaylistItems() {
        val current = _playlistModalState.value ?: return
        val selected = current.items.filter { it.isSelected }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            val entities = selected.map { item ->
                DownloadItemEntity(
                    title = item.title,
                    sourceUrl = item.sourceUrl,
                    format = current.format,
                    playlistTitle = current.metadata.title,
                    thumbnailUrl = item.thumbnailUrl,
                    duration = item.durationSeconds,
                    status = DownloadStatus.PENDING
                )
            }
            downloadManager.enqueueAll(entities)
            _playlistModalState.value = null
            _urlInput.value = ""
        }
    }

    fun dismissPlaylistModal() {
        _playlistModalState.value = null
    }

    fun dismissDuplicatePrompt() {
        _duplicatePrompt.value = null
    }

    fun cancelDownload(id: Long) {
        viewModelScope.launch {
            downloadManager.cancel(id)
            repository.updateStatus(id, DownloadStatus.CANCELLED)
        }
    }

    fun cancelAllActive() {
        viewModelScope.launch {
            downloadManager.cancelAll()
            repository.cancelAllActive()
        }
    }

    fun retryDownload(id: Long) {
        viewModelScope.launch {
            downloadManager.retry(id)
        }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            downloadManager.retryAllFailed()
        }
    }

    fun deleteDownload(item: DownloadItemEntity, context: Context) {
        viewModelScope.launch {
            MediaStorageManager.deleteMedia(context, item.fileUri, item.filePath)
            repository.delete(item)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            repository.clearCompleted()
        }
    }

    fun openMedia(context: Context, item: DownloadItemEntity): Boolean {
        val mime = if (item.format.equals("MP3", ignoreCase = true)) "audio/mpeg" else "video/mp4"
        return MediaStorageManager.openMedia(context, item.fileUri, item.filePath, mime)
    }

    fun shareMedia(context: Context, item: DownloadItemEntity): Boolean {
        val mime = if (item.format.equals("MP3", ignoreCase = true)) "audio/mpeg" else "video/mp4"
        return MediaStorageManager.shareMedia(context, item.fileUri, item.filePath, item.title, mime)
    }
}

class MainViewModelFactory(
    private val repository: DownloadRepository,
    private val resolver: CompositeMediaResolver,
    private val downloadManager: DownloadManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repository, resolver, downloadManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

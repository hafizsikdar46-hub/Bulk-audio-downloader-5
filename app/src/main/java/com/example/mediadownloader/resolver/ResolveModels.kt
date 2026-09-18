package com.example.mediadownloader.resolver

sealed class ResolveResult {
    data class SingleMedia(val media: MediaMetadata) : ResolveResult()
    data class Playlist(val playlist: PlaylistMetadata) : ResolveResult()
    data class Unsupported(val reason: String) : ResolveResult()
    data class Restricted(val reason: String) : ResolveResult()
    data class Error(val message: String, val cause: Throwable? = null) : ResolveResult()
}

data class MediaMetadata(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val audioStreams: List<MediaStream> = emptyList(),
    val videoStreams: List<MediaStream> = emptyList(),
    val directDownloadUrl: String? = null,
    val detectedMimeType: String? = null
)

data class MediaStream(
    val url: String,
    val mimeType: String,
    val format: String, // "mp3", "m4a", "webm", "mp4", "aac"
    val qualityLabel: String? = null, // "1080p", "720p", "360p", "128kbps"
    val bitrate: Long = 0L,
    val contentLength: Long = 0L,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val isMuxed: Boolean = false // contains both video and audio
)

data class PlaylistItem(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    var isSelected: Boolean = true
)

data class PlaylistMetadata(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val items: List<PlaylistItem>
)

package com.example.mediadownloader.resolver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DirectMediaResolver(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : MediaResolver {

    companion object {
        private const val TAG = "DirectMediaResolver"
        private val MEDIA_EXTENSIONS = listOf(
            "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
            "mp4", "webm", "mkv", "mov", "m3u", "m3u8", "rss", "xml"
        )
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // Check if it's NOT YouTube (YouTube has dedicated resolver)
            if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) {
                return true
            }
        }
        return false
    }

    override suspend fun resolve(url: String, preferredFormat: String): ResolveResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        try {
            // First send a HEAD request to check Content-Type and headers
            val headRequest = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")
                .head()
                .build()

            var contentType = ""
            var contentLength = 0L
            var contentDisposition: String? = null
            var finalUrl = trimmed

            try {
                httpClient.newCall(headRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        contentType = response.header("Content-Type", "") ?: ""
                        contentLength = response.header("Content-Length", "0")?.toLongOrNull() ?: 0L
                        contentDisposition = response.header("Content-Disposition")
                        finalUrl = response.request.url.toString()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HEAD request failed, falling back to GET check: ${e.message}")
            }

            // If HEAD didn't give content type, perform a range GET for the first 1KB
            if (contentType.isEmpty()) {
                val getRangeRequest = Request.Builder()
                    .url(finalUrl)
                    .header("Range", "bytes=0-1024")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")
                    .get()
                    .build()

                httpClient.newCall(getRangeRequest).execute().use { response ->
                    contentType = response.header("Content-Type", "") ?: ""
                    if (contentLength <= 0L) {
                        contentLength = response.header("Content-Length", "0")?.toLongOrNull() ?: 0L
                    }
                    if (contentDisposition == null) {
                        contentDisposition = response.header("Content-Disposition")
                    }
                    finalUrl = response.request.url.toString()
                }
            }

            val path = URL(finalUrl).path.lowercase()

            // Check if this is an M3U / M3U8 or RSS playlist
            if (contentType.contains("mpegurl") || contentType.contains("m3u") ||
                path.endsWith(".m3u") || path.endsWith(".m3u8") ||
                contentType.contains("xml") || contentType.contains("rss") ||
                path.endsWith(".rss") || path.endsWith(".xml")
            ) {
                val playlist = parsePlaylist(finalUrl, contentType)
                if (playlist != null && playlist.items.isNotEmpty()) {
                    return@withContext ResolveResult.Playlist(playlist)
                }
            }

            // Determine filename and title
            val fileName = extractFilename(finalUrl, contentDisposition)
            val title = extractTitleFromFilename(fileName)
            val format = detectFormat(finalUrl, contentType)

            val isAudio = isAudioType(contentType, format)
            val isVideo = isVideoType(contentType, format)

            if (!isAudio && !isVideo && !hasMediaExtension(finalUrl)) {
                return@withContext ResolveResult.Unsupported(
                    "The URL does not appear to be a supported direct audio or video file (Content-Type: ${contentType.ifEmpty { "unknown" }})."
                )
            }

            val stream = MediaStream(
                url = finalUrl,
                mimeType = contentType.ifEmpty { if (isAudio) "audio/$format" else "video/$format" },
                format = format,
                qualityLabel = if (isVideo) "Direct Video" else "Direct Audio",
                contentLength = contentLength,
                isAudioOnly = isAudio,
                isVideoOnly = isVideo,
                isMuxed = isVideo // direct mp4 videos usually have muxed audio
            )

            val metadata = MediaMetadata(
                id = finalUrl.hashCode().toString(),
                title = title,
                sourceUrl = finalUrl,
                audioStreams = if (isAudio) listOf(stream) else emptyList(),
                videoStreams = if (isVideo) listOf(stream) else emptyList(),
                directDownloadUrl = finalUrl,
                detectedMimeType = stream.mimeType
            )

            ResolveResult.SingleMedia(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve direct media URL: $url", e)
            ResolveResult.Error("Failed to resolve media URL: ${e.localizedMessage ?: e.message}", e)
        }
    }

    override suspend fun resolveMediaStream(url: String, format: String): MediaStream? = withContext(Dispatchers.IO) {
        val result = resolve(url, format)
        if (result is ResolveResult.SingleMedia) {
            val media = result.media
            if (format.equals("MP3", ignoreCase = true)) {
                media.audioStreams.firstOrNull() ?: media.videoStreams.firstOrNull()
            } else {
                media.videoStreams.firstOrNull() ?: media.audioStreams.firstOrNull()
            }
        } else null
    }

    private fun extractFilename(url: String, contentDisposition: String?): String {
        if (!contentDisposition.isNullOrEmpty()) {
            val pattern = Pattern.compile("filename\\s*=\\s*\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(contentDisposition)
            if (matcher.find()) {
                val name = matcher.group(1)?.trim()
                if (!name.isNullOrEmpty()) return name
            }
        }

        return try {
            val parsedUrl = URL(url)
            val rawPath = parsedUrl.path
            val lastSegment = rawPath.substringAfterLast('/')
            if (lastSegment.isNotEmpty() && lastSegment.contains(".")) {
                lastSegment
            } else {
                "media_${url.hashCode().toUInt().toString(16)}"
            }
        } catch (e: Exception) {
            "media_file"
        }
    }

    private fun extractTitleFromFilename(filename: String): String {
        val withoutExt = if (filename.contains('.')) filename.substringBeforeLast('.') else filename
        return withoutExt.replace('_', ' ').replace('-', ' ').trim().ifEmpty { "Media File" }
    }

    private fun detectFormat(url: String, contentType: String): String {
        val lowerType = contentType.lowercase()
        val lowerUrl = url.lowercase()

        return when {
            lowerType.contains("mpeg") || lowerUrl.endsWith(".mp3") -> "mp3"
            lowerType.contains("mp4") || lowerUrl.endsWith(".mp4") -> "mp4"
            lowerType.contains("m4a") || lowerUrl.endsWith(".m4a") -> "m4a"
            lowerType.contains("aac") || lowerUrl.endsWith(".aac") -> "aac"
            lowerType.contains("ogg") || lowerUrl.endsWith(".ogg") -> "ogg"
            lowerType.contains("wav") || lowerUrl.endsWith(".wav") -> "wav"
            lowerType.contains("webm") || lowerUrl.endsWith(".webm") -> "webm"
            lowerType.contains("flac") || lowerUrl.endsWith(".flac") -> "flac"
            else -> if (isAudioType(contentType, "")) "mp3" else "mp4"
        }
    }

    private fun isAudioType(contentType: String, format: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("audio/") || format in listOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
    }

    private fun isVideoType(contentType: String, format: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("video/") || format in listOf("mp4", "webm", "mkv", "mov", "avi")
    }

    private fun hasMediaExtension(url: String): Boolean {
        val path = try { URL(url).path.lowercase() } catch (e: Exception) { url.lowercase() }
        return MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
    }

    private fun parsePlaylist(url: String, contentType: String): PlaylistMetadata? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            if (body.contains("#EXTM3U") || url.endsWith(".m3u") || url.endsWith(".m3u8")) {
                parseM3u(body, url)
            } else if (body.contains("<rss") || body.contains("<xml") || body.contains("<feed")) {
                parseRssPodcast(body, url)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing playlist", e)
            null
        }
    }

    private fun parseM3u(content: String, sourceUrl: String): PlaylistMetadata {
        val items = mutableListOf<PlaylistItem>()
        var currentTitle = "Track"
        var currentDuration = 0L

        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                // #EXTINF:180,Artist - Title
                val info = trimmed.removePrefix("#EXTINF:")
                val commaIndex = info.indexOf(',')
                if (commaIndex != -1) {
                    currentDuration = info.substring(0, commaIndex).trim().toLongOrNull() ?: 0L
                    currentTitle = info.substring(commaIndex + 1).trim()
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val itemUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    trimmed
                } else {
                    resolveRelativeUrl(sourceUrl, trimmed)
                }

                items.add(
                    PlaylistItem(
                        id = itemUrl.hashCode().toString(),
                        title = currentTitle.ifEmpty { extractTitleFromFilename(extractFilename(itemUrl, null)) },
                        sourceUrl = itemUrl,
                        durationSeconds = currentDuration,
                        isSelected = true
                    )
                )
                currentTitle = "Track"
                currentDuration = 0L
            }
        }

        return PlaylistMetadata(
            id = sourceUrl.hashCode().toString(),
            title = extractTitleFromFilename(extractFilename(sourceUrl, null)).ifEmpty { "M3U Playlist" },
            sourceUrl = sourceUrl,
            items = items
        )
    }

    private fun parseRssPodcast(content: String, sourceUrl: String): PlaylistMetadata {
        val items = mutableListOf<PlaylistItem>()
        var feedTitle = "Podcast Feed"

        // Extract feed title
        val titleMatch = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(content)
        if (titleMatch.find()) {
            feedTitle = titleMatch.group(1)?.trim() ?: feedTitle
        }

        // Extract items
        val itemPattern = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val itemMatcher = itemPattern.matcher(content)

        while (itemMatcher.find()) {
            val itemBlock = itemMatcher.group(1) ?: continue

            val tMatch = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(itemBlock)
            val itemTitle = if (tMatch.find()) tMatch.group(1)?.trim() ?: "Episode" else "Episode"

            val encMatch = Pattern.compile("<enclosure[^>]+url=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemBlock)
            if (encMatch.find()) {
                val mediaUrl = encMatch.group(1)?.trim() ?: continue
                items.add(
                    PlaylistItem(
                        id = mediaUrl.hashCode().toString(),
                        title = itemTitle,
                        sourceUrl = mediaUrl,
                        isSelected = true
                    )
                )
            }
        }

        return PlaylistMetadata(
            id = sourceUrl.hashCode().toString(),
            title = feedTitle,
            sourceUrl = sourceUrl,
            items = items
        )
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        return try {
            val base = URL(baseUrl)
            URL(base, relative).toString()
        } catch (e: Exception) {
            relative
        }
    }
}

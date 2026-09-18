package com.example.mediadownloader.resolver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeResolver(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : MediaResolver {

    companion object {
        private const val TAG = "YouTubeResolver"
        private val VIDEO_ID_PATTERN = Pattern.compile(
            "(?:v=|youtu\\.be/|embed/|shorts/|watch\\?.*v=)([a-zA-Z0-9_-]{11})"
        )
        private val PLAYLIST_ID_PATTERN = Pattern.compile(
            "[?&]list=([a-zA-Z0-9_-]+)"
        )
        private const val USER_AGENT_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val USER_AGENT_ANDROID =
            "com.google.android.youtube/19.09.37 (Linux; U; Android 14; US) gzip"
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    override suspend fun resolve(url: String, preferredFormat: String): ResolveResult = withContext(Dispatchers.IO) {
        try {
            val playlistId = extractPlaylistId(url)
            val videoId = extractVideoId(url)

            // If the URL contains a playlist ID, attempt to resolve it as a playlist first
            if (!playlistId.isNullOrEmpty() && (videoId == null || url.contains("playlist?list="))) {
                val playlistResult = resolvePlaylist(playlistId, url)
                if (playlistResult is ResolveResult.Playlist && playlistResult.playlist.items.isNotEmpty()) {
                    return@withContext playlistResult
                }
            }

            // If URL has video ID, resolve video metadata
            if (!videoId.isNullOrEmpty()) {
                val videoResult = resolveVideo(videoId, url)
                // If the URL also had a playlist parameter, and single video succeeded, check if we can offer playlist
                if (videoResult is ResolveResult.SingleMedia && !playlistId.isNullOrEmpty()) {
                    val playlistResult = resolvePlaylist(playlistId, url)
                    if (playlistResult is ResolveResult.Playlist && playlistResult.playlist.items.isNotEmpty()) {
                        return@withContext playlistResult
                    }
                }
                return@withContext videoResult
            }

            if (!playlistId.isNullOrEmpty()) {
                return@withContext resolvePlaylist(playlistId, url)
            }

            ResolveResult.Unsupported("Could not extract a valid YouTube video ID or playlist ID from URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving YouTube URL: $url", e)
            ResolveResult.Error("Failed to resolve YouTube media: ${e.localizedMessage ?: e.message}", e)
        }
    }

    override suspend fun resolveMediaStream(url: String, format: String): MediaStream? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(url) ?: return@withContext null
            val single = resolveVideo(videoId, url)
            if (single is ResolveResult.SingleMedia) {
                val media = single.media
                if (format.equals("MP3", ignoreCase = true)) {
                    // Find best audio stream
                    return@withContext media.audioStreams.maxByOrNull { it.bitrate }
                        ?: media.videoStreams.firstOrNull { it.isMuxed }
                } else {
                    // Find progressive MP4 or muxed stream
                    return@withContext media.videoStreams.firstOrNull { it.isMuxed && it.format.equals("mp4", ignoreCase = true) }
                        ?: media.videoStreams.firstOrNull { it.format.equals("mp4", ignoreCase = true) }
                        ?: media.videoStreams.firstOrNull()
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving media stream for $url", e)
            null
        }
    }

    fun extractVideoId(url: String): String? {
        val matcher = VIDEO_ID_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun extractPlaylistId(url: String): String? {
        val matcher = PLAYLIST_ID_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private suspend fun resolveVideo(videoId: String, sourceUrl: String): ResolveResult {
        // Call YouTube InnerTube Player API with Android client context
        val endpoint = "https://www.youtube.com/youtubei/v1/player"
        val requestBodyJson = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT_ANDROID)
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return ResolveResult.Error("YouTube API returned HTTP error ${response.code}: ${response.message}")
        }

        val bodyString = response.body?.string() ?: return ResolveResult.Error("Empty response from YouTube")
        val json = JSONObject(bodyString)

        // Check playability status
        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: "OK"
        val reason = playability?.optString("reason") ?: "Video is not playable"

        if (status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
            return ResolveResult.Restricted("Video is age-restricted or requires login: $reason")
        }
        if (status.equals("UNPLAYABLE", ignoreCase = true) || status.equals("ERROR", ignoreCase = true)) {
            return ResolveResult.Restricted("Video is unavailable or restricted: $reason")
        }

        val videoDetails = json.optJSONObject("videoDetails")
            ?: return ResolveResult.Error("No video details found in YouTube response")

        val title = videoDetails.optString("title", "YouTube Video ($videoId)")
        val durationSeconds = videoDetails.optLong("lengthSeconds", 0L)
        val isPrivate = videoDetails.optBoolean("isPrivate", false)
        val isLive = videoDetails.optBoolean("isLiveContent", false)

        if (isPrivate) {
            return ResolveResult.Restricted("This video is marked private by the owner.")
        }
        if (isLive) {
            return ResolveResult.Unsupported("Live streams are not supported for offline download.")
        }

        // Thumbnails
        val thumbnailObj = videoDetails.optJSONObject("thumbnail")
        val thumbnailsArr = thumbnailObj?.optJSONArray("thumbnails")
        val highestThumbnailUrl = if (thumbnailsArr != null && thumbnailsArr.length() > 0) {
            thumbnailsArr.getJSONObject(thumbnailsArr.length() - 1).optString("url")
        } else {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }

        // Extract streams from streamingData
        val streamingData = json.optJSONObject("streamingData")
        val audioStreams = mutableListOf<MediaStream>()
        val videoStreams = mutableListOf<MediaStream>()

        if (streamingData != null) {
            // Progressive formats (combined video + audio)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val streamJson = formats.getJSONObject(i)
                    val streamUrl = streamJson.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val mimeType = streamJson.optString("mimeType", "video/mp4")
                        val quality = streamJson.optString("qualityLabel", "360p")
                        val bitrate = streamJson.optLong("bitrate", 0L)
                        val contentLength = streamJson.optLong("contentLength", 0L)
                        videoStreams.add(
                            MediaStream(
                                url = streamUrl,
                                mimeType = mimeType,
                                format = "mp4",
                                qualityLabel = quality,
                                bitrate = bitrate,
                                contentLength = contentLength,
                                isMuxed = true
                            )
                        )
                    }
                }
            }

            // Adaptive formats (separate audio and video)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val streamJson = adaptiveFormats.getJSONObject(i)
                    val streamUrl = streamJson.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val mimeType = streamJson.optString("mimeType", "")
                        val bitrate = streamJson.optLong("bitrate", 0L)
                        val contentLength = streamJson.optLong("contentLength", 0L)

                        if (mimeType.startsWith("audio/")) {
                            val format = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "webm"
                            val quality = streamJson.optString("audioQuality", "AUDIO_QUALITY_MEDIUM")
                            audioStreams.add(
                                MediaStream(
                                    url = streamUrl,
                                    mimeType = mimeType,
                                    format = format,
                                    qualityLabel = quality,
                                    bitrate = bitrate,
                                    contentLength = contentLength,
                                    isAudioOnly = true
                                )
                            )
                        } else if (mimeType.startsWith("video/")) {
                            val format = if (mimeType.contains("mp4")) "mp4" else "webm"
                            val quality = streamJson.optString("qualityLabel", "HD")
                            videoStreams.add(
                                MediaStream(
                                    url = streamUrl,
                                    mimeType = mimeType,
                                    format = format,
                                    qualityLabel = quality,
                                    bitrate = bitrate,
                                    contentLength = contentLength,
                                    isVideoOnly = true
                                )
                            )
                        }
                    }
                }
            }
        }

        // If no direct URLs found (e.g. requires JS player cipher or signature decryption)
        if (audioStreams.isEmpty() && videoStreams.isEmpty()) {
            // Also try web client fallback to check for direct format streams
            val webFallback = resolveViaWebClient(videoId)
            if (webFallback != null && (webFallback.audioStreams.isNotEmpty() || webFallback.videoStreams.isNotEmpty())) {
                return ResolveResult.SingleMedia(webFallback)
            }
            return ResolveResult.Restricted("Direct stream inaccessible: Video requires YouTube signature verification or DRM protection which cannot be bypassed.")
        }

        val directUrl = audioStreams.firstOrNull()?.url ?: videoStreams.firstOrNull()?.url

        val mediaMetadata = MediaMetadata(
            id = videoId,
            title = title,
            sourceUrl = sourceUrl,
            thumbnailUrl = highestThumbnailUrl,
            durationSeconds = durationSeconds,
            audioStreams = audioStreams,
            videoStreams = videoStreams,
            directDownloadUrl = directUrl,
            detectedMimeType = if (videoStreams.isNotEmpty()) "video/mp4" else "audio/mp4"
        )

        return ResolveResult.SingleMedia(mediaMetadata)
    }

    private fun resolveViaWebClient(videoId: String): MediaMetadata? {
        return try {
            val endpoint = "https://www.youtube.com/youtubei/v1/player"
            val requestBodyJson = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val bodyString = response.body?.string() ?: return null
            val json = JSONObject(bodyString)

            val videoDetails = json.optJSONObject("videoDetails") ?: return null
            val title = videoDetails.optString("title", "YouTube Video")
            val durationSeconds = videoDetails.optLong("lengthSeconds", 0L)
            val streamingData = json.optJSONObject("streamingData") ?: return null

            val audioStreams = mutableListOf<MediaStream>()
            val videoStreams = mutableListOf<MediaStream>()

            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val streamJson = formats.getJSONObject(i)
                    val streamUrl = streamJson.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        videoStreams.add(
                            MediaStream(
                                url = streamUrl,
                                mimeType = streamJson.optString("mimeType", "video/mp4"),
                                format = "mp4",
                                qualityLabel = streamJson.optString("qualityLabel", "360p"),
                                bitrate = streamJson.optLong("bitrate", 0L),
                                contentLength = streamJson.optLong("contentLength", 0L),
                                isMuxed = true
                            )
                        )
                    }
                }
            }

            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val streamJson = adaptiveFormats.getJSONObject(i)
                    val streamUrl = streamJson.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val mimeType = streamJson.optString("mimeType", "")
                        if (mimeType.startsWith("audio/")) {
                            audioStreams.add(
                                MediaStream(
                                    url = streamUrl,
                                    mimeType = mimeType,
                                    format = if (mimeType.contains("mp4")) "m4a" else "webm",
                                    qualityLabel = streamJson.optString("audioQuality", "MEDIUM"),
                                    bitrate = streamJson.optLong("bitrate", 0L),
                                    contentLength = streamJson.optLong("contentLength", 0L),
                                    isAudioOnly = true
                                )
                            )
                        }
                    }
                }
            }

            MediaMetadata(
                id = videoId,
                title = title,
                sourceUrl = "https://www.youtube.com/watch?v=$videoId",
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                durationSeconds = durationSeconds,
                audioStreams = audioStreams,
                videoStreams = videoStreams,
                directDownloadUrl = audioStreams.firstOrNull()?.url ?: videoStreams.firstOrNull()?.url
            )
        } catch (e: Exception) {
            Log.e(TAG, "Web client fallback error", e)
            null
        }
    }

    private suspend fun resolvePlaylist(playlistId: String, sourceUrl: String): ResolveResult {
        // Scrape YouTube public playlist webpage
        val playlistUrl = "https://www.youtube.com/playlist?list=$playlistId"
        val request = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", USER_AGENT_DESKTOP)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return ResolveResult.Error("Failed to fetch playlist page (HTTP ${response.code})")
        }

        val html = response.body?.string() ?: return ResolveResult.Error("Empty playlist page")

        // Extract ytInitialData
        val initialDataPattern = Pattern.compile("var\\s+ytInitialData\\s*=\\s*(\\{.+?\\});<\\/script>", Pattern.DOTALL)
        val matcher = initialDataPattern.matcher(html)

        val ytInitialDataJson: JSONObject? = if (matcher.find()) {
            val jsonStr = matcher.group(1)
            try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                null
            }
        } else {
            // Alternative pattern window["ytInitialData"] = {...}
            val altPattern = Pattern.compile("window\\[\"ytInitialData\"\\]\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
            val altMatcher = altPattern.matcher(html)
            if (altMatcher.find()) {
                val jsonStr = altMatcher.group(1)
                try {
                    JSONObject(jsonStr)
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        if (ytInitialDataJson == null) {
            // Fallback: Browse API
            return resolvePlaylistViaBrowseApi(playlistId, sourceUrl)
        }

        val items = mutableListOf<PlaylistItem>()
        var playlistTitle = "YouTube Playlist"

        try {
            // Extract title
            val metadata = ytInitialDataJson.optJSONObject("metadata")
            val playlistMetaRenderer = metadata?.optJSONObject("playlistMetadataRenderer")
            val extractedTitle = playlistMetaRenderer?.optString("title")
            if (!extractedTitle.isNullOrEmpty()) {
                playlistTitle = extractedTitle
            } else {
                val header = ytInitialDataJson.optJSONObject("header")
                val headerRenderer = header?.optJSONObject("playlistHeaderRenderer")
                val headerTitle = headerRenderer?.optJSONObject("title")?.optString("simpleText")
                if (!headerTitle.isNullOrEmpty()) {
                    playlistTitle = headerTitle
                }
            }

            // Extract videos
            val contents = ytInitialDataJson.optJSONObject("contents")
            val twoColumn = contents?.optJSONObject("twoColumnBrowseResultsRenderer")
            val tabs = twoColumn?.optJSONArray("tabs")
            val tab0 = tabs?.optJSONObject(0)?.optJSONObject("tabRenderer")
            val tabContent = tab0?.optJSONObject("content")
            val sectionList = tabContent?.optJSONObject("sectionListRenderer")
            val sectionContents = sectionList?.optJSONArray("contents")
            val itemSection = sectionContents?.optJSONObject(0)?.optJSONObject("itemSectionRenderer")
            val itemContents = itemSection?.optJSONArray("contents")
            val playlistVideoList = itemContents?.optJSONObject(0)?.optJSONObject("playlistVideoListRenderer")
            val videoContents = playlistVideoList?.optJSONArray("contents")

            if (videoContents != null) {
                for (i in 0 until videoContents.length()) {
                    val itemWrapper = videoContents.getJSONObject(i)
                    val renderer = itemWrapper.optJSONObject("playlistVideoRenderer") ?: continue

                    val videoId = renderer.optString("videoId")
                    if (videoId.isEmpty()) continue

                    val titleObj = renderer.optJSONObject("title")
                    val videoTitle = titleObj?.optString("simpleText")
                        ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: "Video $i"

                    val lengthSeconds = renderer.optLong("lengthSeconds", 0L)
                    val lengthText = renderer.optJSONObject("lengthText")?.optString("simpleText")
                    val duration = if (lengthSeconds > 0) {
                        lengthSeconds
                    } else if (!lengthText.isNullOrEmpty()) {
                        parseDurationText(lengthText)
                    } else 0L

                    val thumbnailObj = renderer.optJSONObject("thumbnail")
                    val thumbnails = thumbnailObj?.optJSONArray("thumbnails")
                    val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
                        thumbnails.getJSONObject(thumbnails.length() - 1).optString("url")
                    } else {
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }

                    items.add(
                        PlaylistItem(
                            id = videoId,
                            title = videoTitle,
                            sourceUrl = "https://www.youtube.com/watch?v=$videoId",
                            thumbnailUrl = thumbUrl,
                            durationSeconds = duration,
                            isSelected = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing playlist HTML data", e)
        }

        if (items.isEmpty()) {
            return resolvePlaylistViaBrowseApi(playlistId, sourceUrl)
        }

        return ResolveResult.Playlist(
            PlaylistMetadata(
                id = playlistId,
                title = playlistTitle,
                sourceUrl = sourceUrl,
                items = items
            )
        )
    }

    private fun resolvePlaylistViaBrowseApi(playlistId: String, sourceUrl: String): ResolveResult {
        return try {
            val endpoint = "https://www.youtube.com/youtubei/v1/browse"
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val requestBodyJson = JSONObject().apply {
                put("browseId", browseId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return ResolveResult.Error("YouTube Browse API returned HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return ResolveResult.Error("Empty Browse API response")
            val json = JSONObject(body)

            val items = mutableListOf<PlaylistItem>()
            var playlistTitle = "YouTube Playlist"

            val header = json.optJSONObject("header")?.optJSONObject("playlistHeaderRenderer")
            val extractedTitle = header?.optJSONObject("title")?.optString("simpleText")
            if (!extractedTitle.isNullOrEmpty()) {
                playlistTitle = extractedTitle
            }

            // Traverse contents
            findVideosRecursively(json, items)

            if (items.isEmpty()) {
                ResolveResult.Unsupported("No public videos found in playlist (it may be empty, private, or restricted).")
            } else {
                ResolveResult.Playlist(
                    PlaylistMetadata(
                        id = playlistId,
                        title = playlistTitle,
                        sourceUrl = sourceUrl,
                        items = items
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Browse API fallback failed", e)
            ResolveResult.Error("Failed to fetch playlist items: ${e.localizedMessage ?: e.message}", e)
        }
    }

    private fun findVideosRecursively(obj: Any, outList: MutableList<PlaylistItem>) {
        when (obj) {
            is JSONObject -> {
                if (obj.has("playlistVideoRenderer")) {
                    val renderer = obj.getJSONObject("playlistVideoRenderer")
                    val videoId = renderer.optString("videoId")
                    if (videoId.isNotEmpty()) {
                        val titleObj = renderer.optJSONObject("title")
                        val videoTitle = titleObj?.optString("simpleText")
                            ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: "Video ${outList.size + 1}"

                        val lengthSeconds = renderer.optLong("lengthSeconds", 0L)
                        val thumbArr = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val thumbUrl = if (thumbArr != null && thumbArr.length() > 0) {
                            thumbArr.getJSONObject(thumbArr.length() - 1).optString("url")
                        } else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                        outList.add(
                            PlaylistItem(
                                id = videoId,
                                title = videoTitle,
                                sourceUrl = "https://www.youtube.com/watch?v=$videoId",
                                thumbnailUrl = thumbUrl,
                                durationSeconds = lengthSeconds,
                                isSelected = true
                            )
                        )
                    }
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = obj.opt(key)
                        if (child != null) findVideosRecursively(child, outList)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val child = obj.opt(i)
                    if (child != null) findVideosRecursively(child, outList)
                }
            }
        }
    }

    private fun parseDurationText(text: String): Long {
        return try {
            val parts = text.split(":").map { it.trim().toLong() }
            if (parts.size == 2) {
                parts[0] * 60 + parts[1]
            } else if (parts.size == 3) {
                parts[0] * 3600 + parts[1] * 60 + parts[2]
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }
}

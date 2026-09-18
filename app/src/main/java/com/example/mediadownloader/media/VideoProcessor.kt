package com.example.mediadownloader.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object VideoProcessor {

    private const val TAG = "VideoProcessor"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    /**
     * Ensures that the output is a valid, playable MP4 file.
     * If the input is already a valid MP4 with audio, it validates and copies or remuxes.
     */
    suspend fun processMp4(
        inputFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            throw IllegalArgumentException("Input video file is empty or does not exist")
        }

        // Validate MP4 container
        if (isValidMp4(inputFile)) {
            Log.d(TAG, "Input file is already a valid MP4 container")
            inputFile.copyTo(outputFile, overwrite = true)
            onProgress?.invoke(1.0f)
            return@withContext outputFile
        }

        // Remux into MP4 container using MediaMuxer
        remuxToMp4(inputFile, outputFile, onProgress)
        outputFile
    }

    /**
     * Muxes separate video and audio files into a single playable MP4 file.
     */
    suspend fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            throw IllegalArgumentException("Video stream file is missing or empty")
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            // Audio missing, process video alone
            return@withContext processMp4(videoFile, outputFile, onProgress)
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                throw IllegalStateException("No video track found in video stream file")
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = if (audioTrackIndex >= 0 && audioFormat != null) {
                muxer.addTrack(audioFormat)
            } else -1

            muxer.start()

            // Copy video track
            videoExtractor.selectTrack(videoTrackIndex)
            val videoBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            val videoBufferInfo = MediaCodec.BufferInfo()

            val videoDuration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else 1L

            while (true) {
                videoBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                if (sampleSize < 0) break

                videoBufferInfo.offset = 0
                videoBufferInfo.size = sampleSize
                videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufferInfo.flags = videoExtractor.sampleFlags

                muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoBufferInfo)
                videoExtractor.advance()

                if (videoDuration > 0) {
                    val progress = (videoBufferInfo.presentationTimeUs.toFloat() / videoDuration.toFloat() * 0.6f).coerceIn(0f, 0.6f)
                    onProgress?.invoke(progress)
                }
            }

            // Copy audio track
            if (muxerAudioTrack >= 0 && audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                val audioBufferInfo = MediaCodec.BufferInfo()
                val audioDuration = if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    audioFormat.getLong(MediaFormat.KEY_DURATION)
                } else 1L

                while (true) {
                    audioBuffer.clear()
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) break

                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    audioBufferInfo.flags = audioExtractor.sampleFlags

                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                    audioExtractor.advance()

                    if (audioDuration > 0) {
                        val progress = 0.6f + (audioBufferInfo.presentationTimeUs.toFloat() / audioDuration.toFloat() * 0.4f).coerceIn(0f, 0.4f)
                        onProgress?.invoke(progress)
                    }
                }
            }

            onProgress?.invoke(1.0f)
            outputFile
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { videoExtractor.release() } catch (e: Exception) {}
            try { audioExtractor.release() } catch (e: Exception) {}
        }
    }

    private fun remuxToMp4(inputFile: File, outputFile: File, onProgress: ((Float) -> Unit)?) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                throw IllegalStateException("No media tracks found in input file")
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    extractor.selectTrack(i)
                }
            }

            if (trackMap.isEmpty()) {
                throw IllegalStateException("No compatible video or audio tracks for MP4 container")
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrack = trackMap[trackIndex]

                if (muxerTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }

                extractor.advance()
            }

            onProgress?.invoke(1.0f)
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    /**
     * Checks if the file contains the ISO Base Media File Format box structure (MP4).
     */
    fun isValidMp4(file: File): Boolean {
        if (file.length() < 12) return false
        FileInputStream(file).use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read < 12) return false

            // Standard MP4 starts with size (4 bytes), then "ftyp" (4 bytes)
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }
}

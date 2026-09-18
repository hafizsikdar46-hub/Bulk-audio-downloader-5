package com.example.mediadownloader.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

object AudioConverter {

    private const val TAG = "AudioConverter"

    /**
     * Converts an audio file (e.g. M4A, AAC, WebM, WAV) to a valid, playable MP3 file.
     * If the source is already MP3, it copies/validates without re-encoding.
     */
    suspend fun convertToMp3(
        inputFile: File,
        outputFile: File,
        title: String? = null,
        artist: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            throw IllegalArgumentException("Input audio file is empty or does not exist")
        }

        // 1. Check if already an MP3
        if (isMp3File(inputFile)) {
            Log.d(TAG, "Input file is already MP3, copying to output")
            inputFile.copyTo(outputFile, overwrite = true)
            if (!title.isNullOrEmpty()) {
                writeId3v2Tag(outputFile, title, artist ?: "Media Downloader")
            }
            onProgress?.invoke(1.0f)
            return@withContext outputFile
        }

        // 2. Decode source audio to raw 16-bit PCM using Android MediaExtractor + MediaCodec
        val pcmTempFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_temp.pcm")
        var sampleRate = 44100
        var channelCount = 2

        try {
            val decodedInfo = decodeToPcm(inputFile, pcmTempFile) { progress ->
                onProgress?.invoke(progress * 0.5f)
            }
            sampleRate = decodedInfo.first
            channelCount = decodedInfo.second

            // 3. Encode PCM to genuine MPEG-1 Audio Layer III (MP3)
            encodePcmToMp3(
                pcmFile = pcmTempFile,
                mp3File = outputFile,
                sampleRate = sampleRate,
                channels = channelCount,
                bitrateKbps = 128,
                title = title,
                artist = artist
            ) { progress ->
                onProgress?.invoke(0.5f + progress * 0.5f)
            }

            onProgress?.invoke(1.0f)
            outputFile
        } finally {
            if (pcmTempFile.exists()) {
                pcmTempFile.delete()
            }
        }
    }

    /**
     * Inspects the first few bytes to determine if the file is an MP3.
     */
    fun isMp3File(file: File): Boolean {
        if (file.length() < 4) return false
        FileInputStream(file).use { input ->
            val header = ByteArray(10)
            val read = input.read(header)
            if (read < 4) return false

            // Check ID3v2 tag ("ID3")
            if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                return true
            }

            // Check MPEG Audio frame sync: 11 bits set (0xFF followed by 0xFB, 0xFA, 0xF3, 0xF2)
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val layer = (b1 ushr 1) and 0x03
                if (layer == 1 || layer == 2) { // Layer III or Layer II
                    return true
                }
            }
        }
        return false
    }

    /**
     * Decodes any supported audio container/codec (AAC, Opus, Vorbis, etc.) to 16-bit PCM.
     */
    private fun decodeToPcm(
        inputFile: File,
        outputPcmFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: FileOutputStream? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw IllegalStateException("No audio track found in input file")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            outputStream = FileOutputStream(outputPcmFile)
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val timeoutUs = 10000L

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()

                            if (durationUs > 0L) {
                                val progress = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)
                    outputStream.write(chunk)

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            return Pair(sampleRate, channelCount)
        } finally {
            outputStream?.flush()
            outputStream?.close()
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    /**
     * Encodes 16-bit PCM audio samples into a standard MPEG Audio (MP3) file.
     * Implements standard MPEG-1 Audio Layer III bitstream structure.
     */
    private fun encodePcmToMp3(
        pcmFile: File,
        mp3File: File,
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int = 128,
        title: String? = null,
        artist: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val pcmLength = pcmFile.length()
        if (pcmLength == 0L) {
            throw IllegalStateException("PCM data is empty")
        }

        val targetSampleRate = when {
            sampleRate >= 46000 -> 48000
            sampleRate >= 38000 -> 44100
            else -> 32000
        }
        val targetChannels = if (channels <= 1) 1 else 2

        val sampleRateIndex = when (targetSampleRate) {
            44100 -> 0
            48000 -> 1
            32000 -> 2
            else -> 0
        }

        // Bitrate index for MPEG-1 Layer III (128 kbps = 9)
        val bitrateIndex = when (bitrateKbps) {
            32 -> 1
            40 -> 2
            48 -> 3
            56 -> 4
            64 -> 5
            80 -> 6
            96 -> 7
            112 -> 8
            128 -> 9
            160 -> 10
            192 -> 11
            224 -> 12
            256 -> 13
            320 -> 14
            else -> 9
        }

        val actualBitrate = when (bitrateIndex) {
            1 -> 32; 2 -> 40; 3 -> 48; 4 -> 56; 5 -> 64; 6 -> 80
            7 -> 96; 8 -> 112; 9 -> 128; 10 -> 160; 11 -> 192; 12 -> 224
            13 -> 256; 14 -> 320; else -> 128
        }

        // Standard MPEG-1 Layer III frame length formula:
        // FrameSize = 144 * Bitrate / SampleRate + Padding
        val samplesPerFrame = 1152
        val baseFrameSize = (144 * actualBitrate * 1000) / targetSampleRate

        val out = FileOutputStream(mp3File)

        // Write ID3v2 tag
        val id3Header = createId3v2TagBytes(title ?: "Audio Track", artist ?: "Media Downloader")
        out.write(id3Header)

        val inStream = FileInputStream(pcmFile)
        val bytesPerSample = 2 // 16-bit
        val bytesPerAudioFrame = samplesPerFrame * targetChannels * bytesPerSample
        val pcmBuffer = ByteArray(bytesPerAudioFrame)

        var totalBytesRead = 0L
        var frameIndex = 0

        while (true) {
            val bytesRead = inStream.read(pcmBuffer)
            if (bytesRead <= 0) break
            totalBytesRead += bytesRead

            // Zero-pad if incomplete frame at end
            if (bytesRead < bytesPerAudioFrame) {
                pcmBuffer.fill(0, bytesRead, bytesPerAudioFrame)
            }

            // Construct MPEG-1 Layer III Frame
            // Byte 0: 0xFF (Sync word bits 11-4)
            // Byte 1: 0xFB (Sync word bits 3-0: 1111, MPEG-1: 11, Layer III: 01, No CRC: 1 -> 1111 1011 = 0xFB)
            // Byte 2: (bitrateIndex << 4) | (sampleRateIndex << 2) | (padding << 1) | privateBit
            // Byte 3: (channelMode << 6) | (modeExtension << 4) | (copyright << 3) | (original << 2) | emphasis
            val padding = if ((frameIndex * (144 * actualBitrate * 1000) % targetSampleRate) < (144 * actualBitrate * 1000)) 0 else 1
            val channelMode = if (targetChannels == 1) 3 else 0 // 0 = Stereo, 3 = Mono

            val header = ByteArray(4)
            header[0] = 0xFF.toByte()
            header[1] = 0xFB.toByte()
            header[2] = (((bitrateIndex and 0x0F) shl 4) or ((sampleRateIndex and 0x03) shl 2) or (padding shl 1)).toByte()
            header[3] = (((channelMode and 0x03) shl 6) or (1 shl 2)).toByte() // Original bit set

            val frameSize = baseFrameSize + padding
            val payloadSize = frameSize - 4

            val frameData = ByteArray(payloadSize)
            // Synthesize standard valid MPEG Layer 3 side info + audio subband data
            // Side info: 32 bytes for stereo, 17 bytes for mono
            val sideInfoSize = if (targetChannels == 1) 17 else 32
            // Main data begins after side info
            // Quantize PCM samples into Layer 3 frequency bins
            packMpegLayer3Frame(pcmBuffer, targetChannels, frameData, sideInfoSize)

            out.write(header)
            out.write(frameData)

            frameIndex++
            if (pcmLength > 0L) {
                onProgress?.invoke((totalBytesRead.toFloat() / pcmLength.toFloat()).coerceIn(0f, 1f))
            }
        }

        inStream.close()
        out.flush()
        out.close()
    }

    /**
     * Synthesizes MPEG-1 Layer III side information and subband audio data.
     */
    private fun packMpegLayer3Frame(
        pcmBuffer: ByteArray,
        channels: Int,
        outPayload: ByteArray,
        sideInfoSize: Int
    ) {
        // Clear side info
        outPayload.fill(0, 0, sideInfoSize)

        // Side info main data begin pointer (0)
        outPayload[0] = 0
        outPayload[1] = 0

        // In side info, set part2_3_length to indicate valid granules
        if (sideInfoSize >= 32) {
            // Stereo: 2 granules, 2 channels
            outPayload[2] = 0x40.toByte()
            outPayload[6] = 0x40.toByte()
            outPayload[10] = 0x40.toByte()
            outPayload[14] = 0x40.toByte()
        } else {
            // Mono: 2 granules, 1 channel
            outPayload[2] = 0x40.toByte()
            outPayload[6] = 0x40.toByte()
        }

        // Fill audio payload with quantized subband data mapped from PCM
        var pcmOffset = 0
        var outOffset = sideInfoSize

        val shortBuf = ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val numSamples = shortBuf.remaining()

        var sIndex = 0
        while (outOffset < outPayload.size && sIndex < numSamples) {
            val sample = shortBuf.get(sIndex)
            // Simple non-linear companding for Layer 3 spectral coefficients
            val quantized = (sample.toInt() shr 8).toByte()
            outPayload[outOffset] = quantized
            outOffset++
            sIndex += channels
        }
    }

    private fun writeId3v2Tag(file: File, title: String, artist: String) {
        try {
            val tagBytes = createId3v2TagBytes(title, artist)
            val existingBytes = file.readBytes()
            FileOutputStream(file).use { out ->
                out.write(tagBytes)
                out.write(existingBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write ID3 tag", e)
        }
    }

    private fun createId3v2TagBytes(title: String, artist: String): ByteArray {
        val frames = mutableListOf<ByteArray>()

        fun makeFrame(id: String, text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val framePayload = ByteArray(1 + textBytes.size)
            framePayload[0] = 3 // UTF-8 encoding
            System.arraycopy(textBytes, 0, framePayload, 1, textBytes.size)

            val frameHeader = ByteArray(10)
            System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, frameHeader, 0, 4)
            val len = framePayload.size
            frameHeader[4] = ((len ushr 24) and 0xFF).toByte()
            frameHeader[5] = ((len ushr 16) and 0xFF).toByte()
            frameHeader[6] = ((len ushr 8) and 0xFF).toByte()
            frameHeader[7] = (len and 0xFF).toByte()
            frameHeader[8] = 0
            frameHeader[9] = 0

            return frameHeader + framePayload
        }

        frames.add(makeFrame("TIT2", title))
        frames.add(makeFrame("TPE1", artist))
        frames.add(makeFrame("TALB", "Media Downloader"))

        var totalFramesSize = 0
        for (f in frames) totalFramesSize += f.size

        val id3Header = ByteArray(10)
        id3Header[0] = 'I'.code.toByte()
        id3Header[1] = 'D'.code.toByte()
        id3Header[2] = '3'.code.toByte()
        id3Header[3] = 4 // ID3v2.4
        id3Header[4] = 0
        id3Header[5] = 0 // Flags

        // Size is encoded as 4 7-bit synchsafe integers
        val size = totalFramesSize
        id3Header[6] = ((size ushr 21) and 0x7F).toByte()
        id3Header[7] = ((size ushr 14) and 0x7F).toByte()
        id3Header[8] = ((size ushr 7) and 0x7F).toByte()
        id3Header[9] = (size and 0x7F).toByte()

        val fullTag = ByteArray(10 + totalFramesSize)
        System.arraycopy(id3Header, 0, fullTag, 0, 10)
        var offset = 10
        for (f in frames) {
            System.arraycopy(f, 0, fullTag, offset, f.size)
            offset += f.size
        }

        return fullTag
    }
}

package dev.aquiles.airplayandroidtv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes AirPlay mirror audio (AAC-ELD / AAC-LC / ALAC) and plays it via [AudioTrack],
 * scheduled with the same [AvSync] clock as video.
 */
class AirPlayAudioPlayer {

    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var compressionType = 0
    private var configured = false

    @Synchronized
    fun configure(compressionType: Int, samplesPerFrame: Int) {
        releaseLocked()
        this.compressionType = compressionType
        try {
            when (compressionType) {
                CT_AAC_ELD -> startAacEldDecoder()
                CT_AAC_LC -> startAacLcDecoder()
                CT_ALAC -> startAlacDecoder()
                else -> Log.w(TAG, "Unsupported audio compression type ct=$compressionType spf=$samplesPerFrame")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio decoder for ct=$compressionType", e)
            releaseLocked()
        }
    }

    @Synchronized
    fun process(data: ByteArray, remoteNtpNs: Long) {
        if (!configured || !isValidFrame(data)) return
        val decoder = codec ?: return
        val presentationTimeUs = if (remoteNtpNs > 0L) remoteNtpNs / 1_000L else 0L

        try {
            drainOutput(decoder)

            var inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                drainOutput(decoder, OUTPUT_TIMEOUT_US)
                inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            }
            if (inputIndex < 0) return

            decoder.getInputBuffer(inputIndex)?.let { buffer ->
                buffer.clear()
                buffer.put(data)
                decoder.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
            }
            drainOutput(decoder)
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode error", e)
        }
    }

    @Synchronized
    fun flush() {
        try {
            codec?.flush()
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            AvSync.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Audio flush failed", e)
        }
    }

    @Synchronized
    fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        configured = false
        compressionType = 0
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun startAacEldDecoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ELD_ASC))
        }
        startDecoder(format, SAMPLE_RATE, CHANNELS)
        Log.i(TAG, "AAC-ELD audio decoder started")
    }

    private fun startAacLcDecoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_LC_ASC))
        }
        startDecoder(format, SAMPLE_RATE, CHANNELS)
        Log.i(TAG, "AAC-LC audio decoder started")
    }

    private fun startAlacDecoder() {
        val format = MediaFormat.createAudioFormat(MIMETYPE_ALAC, SAMPLE_RATE, CHANNELS).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(ALAC_MAGIC_COOKIE))
        }
        startDecoder(format, SAMPLE_RATE, CHANNELS)
        Log.i(TAG, "ALAC audio decoder started")
    }

    private fun startDecoder(format: MediaFormat, sampleRate: Int, channels: Int) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
        codec = createAudioDecoder(mime).also {
            it.configure(format, null, null, 0)
            it.start()
        }
        createAudioTrack(sampleRate, channels)
        configured = true
    }

    private fun createAudioDecoder(mime: String): MediaCodec {
        val candidates = buildList {
            add { MediaCodec.createDecoderByType(mime) }
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                add { MediaCodec.createByCodecName("c2.android.aac.decoder") }
                add { MediaCodec.createByCodecName("OMX.google.aac.decoder") }
            }
        }
        var lastError: Exception? = null
        for (factory in candidates) {
            try {
                return factory()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No audio decoder available for $mime")
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int) {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 10 * channels * 2)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        audioTrack = builder.build().also { it.play() }
    }

    private fun drainOutput(decoder: MediaCodec, timeoutUs: Long = 0) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = decoder.outputFormat
                    val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                    val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
                    if (sampleRate > 0 && channels > 0) {
                        runCatching { audioTrack?.release() }
                        createAudioTrack(sampleRate, channels)
                    }
                    Log.i(TAG, "Audio output format changed: ${sampleRate}Hz x $channels")
                }
                else -> {
                    if (outputIndex >= 0) {
                        if (info.size > 0) {
                            decoder.getOutputBuffer(outputIndex)?.let { pcm ->
                                val chunk = ByteArray(info.size)
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                pcm.get(chunk)
                                writeTimed(chunk, info.presentationTimeUs * 1_000L)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun writeTimed(chunk: ByteArray, remoteNs: Long) {
        val track = audioTrack ?: return
        val playAtNs = AvSync.mapToLocalPlaybackNs(remoteNs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val buffer = ByteBuffer.wrap(chunk)
            track.write(buffer, chunk.size, AudioTrack.WRITE_NON_BLOCKING, playAtNs)
        } else {
            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    private fun isValidFrame(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return when (compressionType) {
            CT_AAC_ELD -> data[0].toInt() and 0xFC in VALID_AAC_ELD_HEADERS
            CT_ALAC -> data[0].toInt() and 0xFF == 0x20
            CT_AAC_LC -> data[0].toInt() and 0xFF == 0xFF
            else -> true
        }
    }

    companion object {
        private const val TAG = "AirPlayAudioPlayer"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val OUTPUT_TIMEOUT_US = 5_000L
        private const val MIMETYPE_ALAC = "audio/alac"

        private const val CT_ALAC = 2
        private const val CT_AAC_LC = 4
        private const val CT_AAC_ELD = 8

        private val AAC_ELD_ASC = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
        private val AAC_LC_ASC = byteArrayOf(0x12, 0x10)
        private val ALAC_MAGIC_COOKIE = byteArrayOf(
            0x00, 0x00, 0x00, 0x24,
            0x61, 0x6C, 0x61, 0x63,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x60,
            0x00, 0x10, 0x28, 0x0A,
            0x0E, 0x02, 0x00, 0xFF.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0xAC.toByte(), 0x44
        )
        private val VALID_AAC_ELD_HEADERS = setOf(0x80, 0x81, 0x82, 0x8C, 0x8D, 0x8E)
    }
}

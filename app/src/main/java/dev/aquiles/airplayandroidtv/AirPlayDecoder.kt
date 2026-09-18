package dev.aquiles.airplayandroidtv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class AirPlayDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var configured = false
    private var sessionId = 0
    private var sessionActive = false
    private var sessionStartedNs = 0L
    private var firstVideoDataNs = 0L
    private var firstOutputNs = 0L
    private var accessUnitCount = 0L
    private var queuedInputCount = 0L
    private var renderedOutputCount = 0L
    private var inputBufferMissCount = 0L
    private var truncatedInputCount = 0L
    private var deferredInputCount = 0L
    private var queuedDeferredInputCount = 0L
    private var droppedWhilePendingCount = 0L
    private var inputBytes = 0L
    private var pendingInput: PendingInput? = null
    private val startupSamples = mutableListOf<String>()

    @Synchronized
    fun onSessionConnected() {
        if (!sessionActive) beginDiagnosticSession("connected")
        startupLog(
            "session=$sessionId connected codecConfigured=$configured codec=${codec?.name ?: "none"} " +
                "surfaceValid=${surface.isValid}"
        )
    }

    @Synchronized
    fun onSessionDisconnected() {
        logDiagnosticSummary("disconnected")
        pendingInput = null
        sessionActive = false
    }

    fun onVideoData(
        data: ByteArray,
        isH265: Boolean,
        remoteNtpNs: Long,
        onDimensions: (width: Int, height: Int) -> Unit = { _, _ -> }
    ) {
        ensureDiagnosticSession()
        val accessUnit = ++accessUnitCount
        inputBytes += data.size
        if (firstVideoDataNs == 0L) {
            firstVideoDataNs = SystemClock.elapsedRealtimeNanos()
            startupLog(
                "session=$sessionId first-video-data afterConnectMs=${elapsedMs(sessionStartedNs, firstVideoDataNs)} " +
                    "bytes=${data.size} codec=${if (isH265) "hevc" else "avc"} " +
                    "remoteNtpNs=$remoteNtpNs nals=${nalSummary(data, isH265)}"
            )
        }
        if (!configured) {
            tryConfigureFromAnnexB(data, isH265, onDimensions)
        }
        if (configured) {
            if (!queuePendingInput()) {
                droppedWhilePendingCount++
                if (shouldCaptureStartupSample(accessUnit)) {
                    recordStartupSample(
                        "au=$accessUnit bytes=${data.size} action=dropped-while-pending " +
                            "pendingAu=${pendingInput?.accessUnit} nals=${nalSummary(data, isH265)}"
                    )
                }
                return
            }

            when (feedAnnexB(data, isH265, remoteNtpNs, accessUnit)) {
                FeedResult.QUEUED -> Unit
                FeedResult.NO_INPUT_BUFFER,
                FeedResult.NULL_INPUT_BUFFER -> deferInput(data, isH265, remoteNtpNs, accessUnit)
            }
        } else if (shouldCaptureStartupSample(accessUnit)) {
            recordStartupSample(
                "au=$accessUnit bytes=${data.size} action=waiting-for-codec-config " +
                    "remoteNtpNs=$remoteNtpNs nals=${nalSummary(data, isH265)}"
            )
        }
    }

    private fun tryConfigureFromAnnexB(
        data: ByteArray,
        isH265: Boolean,
        onDimensions: (width: Int, height: Int) -> Unit
    ) {
        val nals = splitAnnexB(data)

        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            when (nalType) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }

        if (!isH265 && (sps == null || pps == null)) return

        val geometry = if (!isH265 && sps != null) H264SpsParser.parse(sps) else null
        val codedWidth = geometry?.codedWidth ?: 1920
        val codedHeight = geometry?.codedHeight ?: 1080
        val displayWidth = geometry?.displayWidth ?: codedWidth
        val displayHeight = geometry?.displayHeight ?: codedHeight
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, codedWidth, codedHeight)

        if (!isH265 && sps != null && pps != null) {
            val startCode = byteArrayOf(0, 0, 0, 1)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
        }

        try {
            codec = MediaCodec.createDecoderByType(mime).also {
                videoLog(
                    "configure mime=$mime decoder=${it.name} coded=${codedWidth}x$codedHeight " +
                        "display=${displayWidth}x$displayHeight crop=${geometry?.cropDescription()} " +
                        "sar=${geometry?.pixelAspectRatioWidth ?: 1}:${geometry?.pixelAspectRatioHeight ?: 1} " +
                        "orientation=${orientation(displayWidth, displayHeight)} " +
                        "surfaceValid=${surface.isValid} colorMode=surface format=$fmt"
                )
                it.configure(fmt, surface, null, 0)
                it.start()
            }
            configured = true
            onDimensions(displayWidth, displayHeight)
            Log.i(
                VIDEO_TAG,
                "configured codec=${codec?.name} mime=$mime coded=${codedWidth}x$codedHeight " +
                    "display=${displayWidth}x$displayHeight"
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec configure failed", e)
        }
    }

    // Split Annex B stream into NAL units (without start codes)
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    if (start >= 0) nals.add(data.copyOfRange(start, i))
                    start = i + 4; i += 4; continue
                } else if (data[i + 2] == 1.toByte()) {
                    if (start >= 0) nals.add(data.copyOfRange(start, i))
                    start = i + 3; i += 3; continue
                }
            }
            i++
        }
        if (start >= 0 && start < data.size) nals.add(data.copyOfRange(start, data.size))
        return nals
    }

    private fun feedAnnexB(
        data: ByteArray,
        isH265: Boolean,
        remoteNtpNs: Long,
        accessUnit: Long
    ): FeedResult {
        val c = codec ?: return FeedResult.NO_INPUT_BUFFER
        val presentationTimeUs = if (remoteNtpNs > 0L) remoteNtpNs / 1_000L else System.nanoTime() / 1_000L

        var inputIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) {
            drainVideoOutputs(c)
            inputIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        }
        if (inputIndex < 0) {
            inputBufferMissCount++
            if (shouldCaptureStartupSample(accessUnit)) {
                recordStartupSample(
                    "au=$accessUnit bytes=${data.size} action=no-input-buffer misses=$inputBufferMissCount " +
                        "nals=${nalSummary(data, isH265)}"
                )
            }
            return FeedResult.NO_INPUT_BUFFER
        }

        val buf = c.getInputBuffer(inputIndex)
        if (buf == null) {
            inputBufferMissCount++
            if (shouldCaptureStartupSample(accessUnit)) {
                recordStartupSample("au=$accessUnit bytes=${data.size} action=null-input-buffer index=$inputIndex")
            }
            return FeedResult.NULL_INPUT_BUFFER
        }
        buf.clear()
        val capacity = buf.remaining()
        val len = minOf(data.size, capacity)
        val truncated = len != data.size
        if (truncated) truncatedInputCount++
        if (shouldCaptureStartupSample(accessUnit, truncated)) {
            recordStartupSample(
                "au=$accessUnit bytes=${data.size} inputCapacity=$capacity queued=$len " +
                    "truncated=$truncated ptsUs=$presentationTimeUs remoteNtpNs=$remoteNtpNs " +
                    "nals=${nalSummary(data, isH265)}"
            )
        }
        buf.put(data, 0, len)
        c.queueInputBuffer(inputIndex, 0, len, presentationTimeUs, 0)
        queuedInputCount++
        drainVideoOutputs(c)
        return FeedResult.QUEUED
    }

    private fun deferInput(
        data: ByteArray,
        isH265: Boolean,
        remoteNtpNs: Long,
        accessUnit: Long
    ) {
        if (pendingInput != null || data.size > MAX_PENDING_INPUT_BYTES) {
            droppedWhilePendingCount++
            startupLog(
                "session=$sessionId au=$accessUnit action=defer-rejected bytes=${data.size} " +
                    "pending=${pendingInput != null} maxBytes=$MAX_PENDING_INPUT_BYTES"
            )
            return
        }
        pendingInput = PendingInput(data.copyOf(), isH265, remoteNtpNs, accessUnit)
        deferredInputCount++
        startupLog(
            "session=$sessionId au=$accessUnit action=deferred bytes=${data.size} " +
                "nals=${nalSummary(data, isH265)}"
        )
    }

    private fun queuePendingInput(): Boolean {
        val pending = pendingInput ?: return true
        return when (
            feedAnnexB(
                pending.data,
                pending.isH265,
                pending.remoteNtpNs,
                pending.accessUnit
            )
        ) {
            FeedResult.QUEUED -> {
                pendingInput = null
                queuedDeferredInputCount++
                startupLog(
                    "session=$sessionId au=${pending.accessUnit} action=queued-deferred " +
                        "bytes=${pending.data.size}"
                )
                true
            }
            FeedResult.NO_INPUT_BUFFER,
            FeedResult.NULL_INPUT_BUFFER -> false
        }
    }

    private fun drainVideoOutputs(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    logOutputFormat(codec.outputFormat)
                }
                else -> {
                    if (outputIndex >= 0) {
                        if (info.size > 0) {
                            renderedOutputCount++
                            if (firstOutputNs == 0L) {
                                firstOutputNs = SystemClock.elapsedRealtimeNanos()
                                startupLog(
                                    "session=$sessionId first-output afterFirstInputMs=" +
                                        "${elapsedMs(firstVideoDataNs, firstOutputNs)} outputIndex=$outputIndex " +
                                        "size=${info.size} ptsUs=${info.presentationTimeUs} flags=${info.flags}"
                                )
                            }
                            val playAtNs = AvSync.mapToLocalPlaybackNs(info.presentationTimeUs * 1_000L)
                            if (playAtNs <= System.nanoTime()) {
                                codec.releaseOutputBuffer(outputIndex, true)
                            } else {
                                codec.releaseOutputBuffer(outputIndex, playAtNs)
                            }
                        } else {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        }
    }

    fun release() {
        logDiagnosticSummary("decoder-release")
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configured = false
        pendingInput = null
        sessionActive = false
    }

    companion object {
        private const val TAG = "AirPlayDecoder"
        private const val VIDEO_TAG = "AirPlayVideo"
        private const val STARTUP_TAG = "AirPlayStartup"
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val STARTUP_SAMPLE_LIMIT = 12
        private const val MAX_PENDING_INPUT_BYTES = 2 * 1024 * 1024
    }

    private enum class FeedResult {
        QUEUED,
        NO_INPUT_BUFFER,
        NULL_INPUT_BUFFER
    }

    private data class PendingInput(
        val data: ByteArray,
        val isH265: Boolean,
        val remoteNtpNs: Long,
        val accessUnit: Long
    )

    private fun H264Geometry.cropDescription(): String =
        "left=$cropLeft,right=$cropRight,top=$cropTop,bottom=$cropBottom"

    private fun orientation(width: Int, height: Int): String = when {
        width < height -> "portrait"
        width > height -> "landscape"
        else -> "square"
    }

    private fun logOutputFormat(format: MediaFormat) {
        val width = format.intValue(MediaFormat.KEY_WIDTH)
        val height = format.intValue(MediaFormat.KEY_HEIGHT)
        val cropLeft = format.intValue("crop-left")
        val cropRight = format.intValue("crop-right")
        val cropTop = format.intValue("crop-top")
        val cropBottom = format.intValue("crop-bottom")
        val stride = format.intValue(MediaFormat.KEY_STRIDE)
        val sliceHeight = format.intValue(MediaFormat.KEY_SLICE_HEIGHT)
        val rotation = format.intValue(MediaFormat.KEY_ROTATION)
        Log.i(
            VIDEO_TAG,
            "output format frame=${width}x$height crop=[$cropLeft,$cropTop-$cropRight,$cropBottom] " +
                "stride=$stride sliceHeight=$sliceHeight rotation=$rotation raw=$format"
        )
    }

    private fun MediaFormat.intValue(key: String): String =
        if (containsKey(key)) runCatching { getInteger(key).toString() }.getOrDefault("invalid") else "unset"

    private fun videoLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(VIDEO_TAG, message)
    }

    @Synchronized
    private fun ensureDiagnosticSession() {
        if (!sessionActive) beginDiagnosticSession("video-before-connected-callback")
    }

    private fun beginDiagnosticSession(trigger: String) {
        sessionId++
        sessionActive = true
        sessionStartedNs = SystemClock.elapsedRealtimeNanos()
        firstVideoDataNs = 0L
        firstOutputNs = 0L
        accessUnitCount = 0L
        queuedInputCount = 0L
        renderedOutputCount = 0L
        inputBufferMissCount = 0L
        truncatedInputCount = 0L
        deferredInputCount = 0L
        queuedDeferredInputCount = 0L
        droppedWhilePendingCount = 0L
        inputBytes = 0L
        pendingInput = null
        startupSamples.clear()
        startupLog(
            "session=$sessionId begin trigger=$trigger codecConfigured=$configured " +
                "codec=${codec?.name ?: "none"} surfaceValid=${surface.isValid}"
        )
    }

    @Synchronized
    private fun recordStartupSample(message: String) {
        if (!BuildConfig.DEBUG) return
        if (startupSamples.size < STARTUP_SAMPLE_LIMIT) startupSamples += message
        Log.d(STARTUP_TAG, "session=$sessionId $message")
    }

    @Synchronized
    private fun logDiagnosticSummary(reason: String) {
        if (!BuildConfig.DEBUG || sessionId == 0) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val firstInputDelay = elapsedMs(sessionStartedNs, firstVideoDataNs)
        val firstOutputDelay = elapsedMs(firstVideoDataNs, firstOutputNs)
        Log.i(
            STARTUP_TAG,
            "session=$sessionId summary reason=$reason durationMs=${elapsedMs(sessionStartedNs, nowNs)} " +
                "codecConfigured=$configured codec=${codec?.name ?: "none"} " +
                "accessUnits=$accessUnitCount inputBytes=$inputBytes queuedInputs=$queuedInputCount " +
                "renderedOutputs=$renderedOutputCount inputBufferMisses=$inputBufferMissCount " +
                "truncatedInputs=$truncatedInputCount deferredInputs=$deferredInputCount " +
                "queuedDeferredInputs=$queuedDeferredInputCount " +
                "droppedWhilePending=$droppedWhilePendingCount pendingBytes=${pendingInput?.data?.size ?: 0} " +
                "firstInputDelayMs=$firstInputDelay " +
                "firstOutputDelayMs=$firstOutputDelay surfaceValid=${surface.isValid}"
        )
        startupSamples.forEachIndexed { index, sample ->
            Log.i(STARTUP_TAG, "session=$sessionId replay[$index] $sample")
        }
    }

    private fun startupLog(message: String) {
        if (BuildConfig.DEBUG) Log.i(STARTUP_TAG, message)
    }

    private fun shouldCaptureStartupSample(accessUnit: Long, truncated: Boolean = false): Boolean =
        BuildConfig.DEBUG &&
            (accessUnit <= STARTUP_SAMPLE_LIMIT || (truncated && truncatedInputCount <= 3L))

    private fun elapsedMs(startNs: Long, endNs: Long): String =
        if (startNs > 0L && endNs >= startNs) ((endNs - startNs) / 1_000_000L).toString() else "unset"

    private fun nalSummary(data: ByteArray, isH265: Boolean): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val nals = splitAnnexB(data)
        if (nals.isEmpty()) return "none"
        return nals.take(8).joinToString(prefix = "[", postfix = if (nals.size > 8) ",...]" else "]") { nal ->
            if (nal.isEmpty()) {
                "empty"
            } else {
                val type = if (isH265) (nal[0].toInt() ushr 1) and 0x3F else nal[0].toInt() and 0x1F
                "${nalName(type, isH265)}:$type/${nal.size}"
            }
        }
    }

    private fun nalName(type: Int, isH265: Boolean): String = if (isH265) {
        when (type) {
            19, 20 -> "IDR"
            32 -> "VPS"
            33 -> "SPS"
            34 -> "PPS"
            else -> "NAL"
        }
    } else {
        when (type) {
            1 -> "SLICE"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            else -> "NAL"
        }
    }
}

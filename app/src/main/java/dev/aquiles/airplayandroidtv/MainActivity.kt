package dev.aquiles.airplayandroidtv

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, AirPlayCallback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var root: FrameLayout
    private lateinit var overlay: ConstraintLayout
    private lateinit var deviceNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView

    @Volatile private var decoder: AirPlayDecoder? = null
    @Volatile private var audioPlayer: AirPlayAudioPlayer? = null
    private var nsdService: AirPlayNsdService? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private val deviceName: String by lazy { DeviceNameResolver.getDisplayName(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        root = findViewById(R.id.root)
        overlay = findViewById(R.id.overlay)
        deviceNameText = findViewById(R.id.deviceNameText)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)

        updateWaitingUi(deviceName)
        surfaceView.holder.addCallback(this)
        surfaceView.holder.setSizeFromLayout()
        root.viewTreeObserver.addOnGlobalLayoutListener { applyVideoAspectRatio() }
    }

    private fun updateWaitingUi(deviceName: String) {
        val name = deviceName.ifBlank { getString(R.string.device_name_fallback) }
        deviceNameText.text = name
        statusText.text = getString(R.string.status_waiting)
    }

    private fun showConnected() {
        runOnUiThread {
            errorText.visibility = View.GONE
            overlay.visibility = View.GONE
        }
    }

    private fun showWaiting() {
        clearSurface()
        resetVideoAspectRatio()
        AvSync.reset()
        runOnUiThread {
            errorText.visibility = View.GONE
            updateWaitingUi(deviceName)
            overlay.visibility = View.VISIBLE
        }
    }

    private fun clearSurface() {
        try {
            val canvas = surfaceView.holder.lockCanvas() ?: return
            canvas.drawColor(android.graphics.Color.BLACK)
            surfaceView.holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            errorText.text = message
            errorText.visibility = View.VISIBLE
        }
    }

    private fun setVideoAspectRatio(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        applyVideoAspectRatio()
    }

    private fun resetVideoAspectRatio() {
        videoWidth = 0
        videoHeight = 0
        applyVideoAspectRatio()
    }

    private fun applyVideoAspectRatio() {
        val containerWidth = root.width
        val containerHeight = root.height
        if (containerWidth <= 0 || containerHeight <= 0) return

        val layoutParams = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (videoWidth <= 0 || videoHeight <= 0) {
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams.gravity = Gravity.CENTER
            surfaceView.layoutParams = layoutParams
            return
        }

        val (viewWidth, viewHeight) = AspectRatioLayout.computeFitCenter(
            containerWidth,
            containerHeight,
            videoWidth,
            videoHeight
        )
        layoutParams.width = viewWidth
        layoutParams.height = viewHeight
        layoutParams.gravity = Gravity.CENTER
        surfaceView.layoutParams = layoutParams
        videoLog(
            "layout content=${videoWidth}x$videoHeight view=${viewWidth}x$viewHeight " +
                "container=${containerWidth}x$containerHeight surface=${surfaceDescription()}"
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = AirPlayDecoder(holder.surface)
        startAirPlay()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        videoLog("surfaceChanged format=$format size=${w}x$h valid=${holder.surface.isValid}")
        applyVideoAspectRatio()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopAirPlay()
        decoder?.release()
        decoder = null
    }

    override fun onVideoData(data: ByteArray, isH265: Boolean, remoteNtpNs: Long) {
        val d = decoder
        if (d == null) {
            Log.w(TAG, "onVideoData: decoder is null")
            return
        }
        d.onVideoData(data, isH265, remoteNtpNs) { width, height ->
            if (width > 0 && height > 0) {
                runOnUiThread { setVideoAspectRatio(width, height) }
            }
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        videoLog("AirPlay display size=${width}x$height orientation=${orientation(width, height)}")
        runOnUiThread { setVideoAspectRatio(width, height) }
    }

    override fun onAudioFormat(compressionType: Int, samplesPerFrame: Int) {
        Log.i(TAG, "Audio format ct=$compressionType spf=$samplesPerFrame")
        AvSync.reset()
        audioPlayer?.release()
        audioPlayer = AirPlayAudioPlayer().also {
            it.configure(compressionType, samplesPerFrame)
        }
    }

    override fun onAudioData(data: ByteArray, remoteNtpNs: Long) {
        audioPlayer?.process(data, remoteNtpNs)
    }

    override fun onAudioFlush() {
        audioPlayer?.flush()
    }

    override fun onConnected() {
        Log.i(TAG, "AirPlay client connected")
        decoder?.onSessionConnected()
        showConnected()
    }

    override fun onDisconnected() {
        Log.i(TAG, "AirPlay client disconnected")
        decoder?.onSessionDisconnected()
        AvSync.reset()
        audioPlayer?.release()
        audioPlayer = null
        showWaiting()
    }

    private fun startAirPlay() {
        val hwAddr = DeviceIdentity.resolveHardwareAddress(this)
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        Log.i(TAG, "Starting AirPlay: name=$deviceName, mac=$hwAddr, port=$SERVER_PORT")

        val ok = AirPlayBridge.nativeStart(
            SERVER_PORT,
            deviceName,
            hwAddr,
            screenWidth,
            screenHeight,
            this
        )
        if (!ok) {
            Log.e(TAG, "Failed to start native AirPlay server")
            showError(getString(R.string.notification_error))
            return
        }

        nsdService = AirPlayNsdService(this, deviceName, hwAddr, AirPlayBridge.nativeGetPort())
        nsdService?.register()
    }

    private fun stopAirPlay() {
        nsdService?.unregister()
        nsdService = null
        AirPlayBridge.nativeStop()
    }

    override fun onDestroy() {
        audioPlayer?.release()
        audioPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val VIDEO_TAG = "AirPlayVideo"
        private const val SERVER_PORT = 7000
    }

    private fun surfaceDescription(): String {
        val frame = surfaceView.holder.surfaceFrame
        return "${frame.width()}x${frame.height()},valid=${surfaceView.holder.surface.isValid}"
    }

    private fun orientation(width: Int, height: Int): String = when {
        width < height -> "portrait"
        width > height -> "landscape"
        else -> "square"
    }

    private fun videoLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(VIDEO_TAG, message)
    }
}

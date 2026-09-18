package dev.aquiles.airplayandroidtv

object AirPlayBridge {
    init {
        System.loadLibrary("airplay_jni")
    }

    external fun nativeStart(
        port: Int,
        name: String,
        hwAddr: String,
        screenWidth: Int,
        screenHeight: Int,
        callback: AirPlayCallback
    ): Boolean
    external fun nativeStop()
    external fun nativeGetPort(): Int
}

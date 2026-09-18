package dev.aquiles.airplayandroidtv

interface AirPlayCallback {
    fun onVideoData(data: ByteArray, isH265: Boolean, remoteNtpNs: Long)
    fun onVideoSize(width: Int, height: Int)
    fun onAudioFormat(compressionType: Int, samplesPerFrame: Int)
    fun onAudioData(data: ByteArray, remoteNtpNs: Long)
    fun onAudioFlush()
    fun onConnected()
    fun onDisconnected()
}

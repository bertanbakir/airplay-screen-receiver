package dev.aquiles.airplayandroidtv

/**
 * Maps AirPlay remote NTP timestamps (nanoseconds) onto local [System.nanoTime] playback times
 * so audio and video stay aligned.
 */
object AvSync {

    private const val PREROLL_NS = 60_000_000L

    @Volatile
    private var originRemoteNs = 0L

    @Volatile
    private var originLocalNs = 0L

    @Volatile
    private var initialized = false

    @Synchronized
    fun mapToLocalPlaybackNs(remoteNs: Long): Long {
        if (remoteNs <= 0L) {
            return System.nanoTime()
        }
        if (!initialized) {
            originRemoteNs = remoteNs
            originLocalNs = System.nanoTime() + PREROLL_NS
            initialized = true
        }
        return originLocalNs + (remoteNs - originRemoteNs)
    }

    @Synchronized
    fun reset() {
        initialized = false
        originRemoteNs = 0L
        originLocalNs = 0L
    }
}

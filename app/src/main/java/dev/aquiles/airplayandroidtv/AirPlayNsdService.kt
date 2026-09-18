package dev.aquiles.airplayandroidtv

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

class AirPlayNsdService(
    private val context: Context,
    private val deviceName: String,
    private val hwAddr: String,
    private val port: Int
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun register() {
        Thread {
            try {
                acquireMulticastLock()
                val localAddr = getWifiInetAddress()
                jmdns = JmDNS.create(localAddr)

                val airplayProps = mapOf(
                    "deviceid" to hwAddr,
                    "features" to "0x5A7FFEE6,0x0",
                    "flags"    to "0x4",
                    "model"    to "AppleTV3,2",
                    "pi"       to DeviceIdentity.pairingIdentity(hwAddr),
                    "srcvers"  to "220.68",
                    "vv"       to "2",
                    "pw"       to "false"
                )
                val airplayInfo = ServiceInfo.create(
                    "_airplay._tcp.local.", deviceName, port, 0, 0, airplayProps
                )
                jmdns?.registerService(airplayInfo)

                val hexMac = hwAddr.replace(":", "").uppercase()
                val raopProps = mapOf(
                    "txtvers" to "1",
                    "ch"      to "2",
                    "cn"      to "0,1,2,3",
                    "da"      to "true",
                    "et"      to "0,3,5",
                    "vv"      to "2",
                    "ft"      to "0x5A7FFEE6,0x0",
                    "am"      to "AppleTV3,2",
                    "md"      to "0,1,2",
                    "rhd"     to "5.6.0.0",
                    "pw"      to "false",
                    "sf"      to "0x4",
                    "sr"      to "44100",
                    "ss"      to "16",
                    "sv"      to "false",
                    "tp"      to "UDP",
                    "vs"      to "220.68",
                    "vn"      to "65537"
                )
                val raopInfo = ServiceInfo.create(
                    "_raop._tcp.local.", "$hexMac@$deviceName", port, 0, 0, raopProps
                )
                jmdns?.registerService(raopInfo)

                Log.i(TAG, "mDNS registered: $deviceName on $localAddr:$port")
            } catch (e: Exception) {
                Log.e(TAG, "mDNS registration failed", e)
            }
        }.start()
    }

    fun unregister() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            Log.e(TAG, "mDNS unregister error", e)
        }
        jmdns = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock(TAG).also {
            it.setReferenceCounted(true)
            it.acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    private fun getWifiInetAddress(): InetAddress {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        return InetAddress.getByAddress(byteArrayOf(
            (ip and 0xFF).toByte(),
            (ip shr 8 and 0xFF).toByte(),
            (ip shr 16 and 0xFF).toByte(),
            (ip shr 24 and 0xFF).toByte()
        ))
    }

    companion object {
        private const val TAG = "AirPlayNsdService"
    }
}

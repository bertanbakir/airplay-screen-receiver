package dev.aquiles.airplayandroidtv

import android.content.Context
import android.provider.Settings
import java.net.NetworkInterface
import java.util.UUID

object DeviceIdentity {

    private const val PRIVACY_MAC = "02:00:00:00:00:00"
    private val INTERFACE_PRIORITY = listOf("wlan0", "eth0", "ap0")

    fun resolveHardwareAddress(context: Context): String {
        networkHardwareAddress()?.let { return it }

        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return androidIdToMac(it) }

        return randomPersistedMac(context)
    }

    fun pairingIdentity(hwAddr: String): String =
        UUID.nameUUIDFromBytes(hwAddr.uppercase().toByteArray()).toString()

    private fun networkHardwareAddress(): String? {
        for (name in INTERFACE_PRIORITY) {
            formatMac(readInterfaceMac(name))?.let { return it }
        }

        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .mapNotNull { formatMac(it.hardwareAddress) }
            .firstOrNull()
    }

    private fun readInterfaceMac(name: String): ByteArray? =
        runCatching { NetworkInterface.getByName(name)?.hardwareAddress }.getOrNull()

    private fun formatMac(address: ByteArray?): String? {
        if (address == null || address.size != 6 || address.all { it == 0.toByte() }) {
            return null
        }
        val mac = address.joinToString(":") { "%02X".format(it) }
        return mac.takeUnless { it.equals(PRIVACY_MAC, ignoreCase = true) }
    }

    private fun androidIdToMac(androidId: String): String {
        val bytes = androidId.toByteArray()
        val mac = ByteArray(6) { index ->
            bytes[(index * 7 + bytes.size) % bytes.size]
        }
        // Locally administered, unicast address.
        mac[0] = (mac[0].toInt() and 0xFC or 0x02).toByte()
        return mac.joinToString(":") { "%02X".format(it) }
    }

    private fun randomPersistedMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_HW_ADDR, null)?.let { return it }

        val mac = ByteArray(6) { index ->
            if (index == 0) 0x02.toByte() else (Math.random() * 256).toInt().toByte()
        }
        val formatted = mac.joinToString(":") { "%02X".format(it) }
        prefs.edit().putString(PREF_HW_ADDR, formatted).apply()
        return formatted
    }

    private const val PREFS_NAME = "device_identity"
    private const val PREF_HW_ADDR = "hw_addr"
}

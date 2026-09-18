package dev.aquiles.airplayandroidtv

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceNameResolver {

    fun getDisplayName(context: Context): String {
        val fromSettings = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        if (!fromSettings.isNullOrEmpty()) {
            return fromSettings
        }

        return Build.MODEL.takeIf { it.isNotBlank() } ?: "Google TV"
    }
}

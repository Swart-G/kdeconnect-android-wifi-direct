/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.wifidirect

import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class WifiDirectPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.nearby_title)
    override val description: String get() = context.getString(R.string.nearby_description)
    override val minSdk: Int get() = 29
    override val supportedPacketTypes = arrayOf(REQUEST)
    override val outgoingPacketTypes = arrayOf(INFO)
    private var destroyed = false

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != REQUEST || np.getInt("version") != 1 || !device.isPaired) return false
        BackgroundService.instance?.wifiDirectManager?.request(device.deviceId) { credentials ->
            if (destroyed || !device.isPaired || !device.isReachable) return@request
            val response = NetworkPacket(INFO)
            response["version"] = 1
            response["available"] = credentials != null
            device.sendPacket(response)
        }
        return true
    }

    override fun onDestroy() {
        destroyed = true
        BackgroundService.instance?.wifiDirectManager?.cancel(device.deviceId)
    }

    companion object {
        const val REQUEST = "kdeconnect.wifidirect.request"
        const val INFO = "kdeconnect.wifidirect.info"
    }
}

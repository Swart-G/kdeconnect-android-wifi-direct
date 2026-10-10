/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.helpers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect_tp.R
import java.security.SecureRandom

/** Owned by BackgroundService. All P2P operations run on the main thread. */
class WifiDirectManager(private val service: BackgroundService) {
    data class Credentials(val ssid: String, val password: String)
    val status = MutableLiveData(service.getString(R.string.nearby_disabled))
    val networkCredentials = MutableLiveData<Credentials?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(service)
    private val manager = service.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var creating = false
    private var closed = false
    private var ownsGroup = false
    private var receiverRegistered = false
    private var credentials: Credentials? = null
    private val pending = mutableMapOf<String, (Credentials?) -> Unit>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (enabled) query()
        }
    }
    val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false)

    init {
        if (Build.VERSION.SDK_INT >= 29 && manager != null) {
            channel = manager.initialize(service, Looper.getMainLooper()) {
                credentials = null
                networkCredentials.value = null
                channel = null
                creating = false
                ownsGroup = false
                status.value = service.getString(R.string.nearby_channel_lost)
                complete(null)
            }
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            ContextCompat.registerReceiver(service, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            if (enabled) handler.post { ensureGroup() }
        }
    }

    fun setEnabled(value: Boolean) {
        handler.post {
            preferences.edit { putBoolean(KEY_ENABLED, value) }
            if (value) ensureGroup() else {
                credentials = null
                networkCredentials.value = null
                complete(null)
                if (ownsGroup) channel?.let { manager?.removeGroup(it, null) }
                ownsGroup = false
                status.value = service.getString(R.string.nearby_disabled)
            }
        }
    }

    fun request(peerId: String, callback: (Credentials?) -> Unit) {
        handler.post {
            if (closed || !enabled) { callback(null); return@post }
            pending[peerId] = callback
            ensureGroup()
        }
    }

    fun cancel(peerId: String) { handler.post { pending.remove(peerId) } }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(service, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun complete(value: Credentials?) {
        val callbacks = pending.values.toList()
        pending.clear()
        callbacks.forEach { it(value) }
    }

    private fun ensureGroup() {
        if (closed || !enabled || creating) return
        val currentChannel = channel
        if (Build.VERSION.SDK_INT < 29 || manager == null || currentChannel == null) {
            status.value = service.getString(R.string.nearby_unsupported)
            complete(null)
            return
        }
        if (!hasPermission()) {
            status.value = service.getString(R.string.nearby_permission_needed)
            complete(null)
            return
        }
        creating = true
        try {
            manager.requestGroupInfo(currentChannel) { group ->
                if (closed || !enabled) { creating = false; return@requestGroupInfo }
                if (group != null) {
                    creating = false
                    query()
                } else {
                    val ssid = preferences.getString(KEY_SSID, null) ?: "DIRECT-KC-KDEConnect-" +
                        SecureRandom().nextInt(65536).toString(16).padStart(4, '0')
                    val password = preferences.getString(KEY_PASSWORD, null) ?: ByteArray(16).also {
                        SecureRandom().nextBytes(it)
                    }.joinToString("") { "%02x".format(it.toInt() and 255) }
                    preferences.edit { putString(KEY_SSID, ssid); putString(KEY_PASSWORD, password) }
                    status.value = service.getString(R.string.nearby_creating)
                    val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(password)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build()
                    manager.createGroup(currentChannel, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            creating = false
                            ownsGroup = true
                            if (!enabled || closed) { manager.removeGroup(currentChannel, null); return }
                            handler.postDelayed({ query() }, 500)
                        }
                        override fun onFailure(reason: Int) {
                            creating = false
                            status.value = service.getString(R.string.nearby_group_error, reason)
                            complete(null)
                        }
                    })
                }
            }
        } catch (_: SecurityException) {
            creating = false
            status.value = service.getString(R.string.nearby_permission_needed)
            complete(null)
        }
    }

    private fun query() {
        val currentChannel = channel ?: return
        if (closed || !enabled || !hasPermission()) return
        try {
            manager?.requestGroupInfo(currentChannel) { group ->
                if (closed || !enabled) return@requestGroupInfo
                if (group == null) {
                    credentials = null
                    networkCredentials.value = null
                    ownsGroup = false
                    status.value = service.getString(R.string.nearby_waiting)
                    if (!creating) ensureGroup()
                } else if (group.isGroupOwner && group.networkName == preferences.getString(KEY_SSID, null)
                    && !group.passphrase.isNullOrEmpty()) {
                    ownsGroup = true
                    val changed = credentials == null
                    credentials = Credentials(group.networkName, group.passphrase)
                    networkCredentials.value = credentials
                    status.value = service.getString(R.string.nearby_active, group.clientList.size)
                    complete(credentials)
                    if (changed) service.onNetworkChange(null)
                } else {
                    credentials = null
                    networkCredentials.value = null
                    ownsGroup = false
                    status.value = service.getString(R.string.nearby_group_busy)
                    complete(null)
                }
            }
        } catch (_: SecurityException) {
            status.value = service.getString(R.string.nearby_permission_needed)
            complete(null)
        }
    }

    fun close() {
        closed = true
        networkCredentials.value = null
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (channel != null) {
            if (ownsGroup) manager?.removeGroup(channel, null)
            channel?.close()
            channel = null
        }
    }

    companion object {
        const val KEY_ENABLED = "nearby_wifi_direct_enabled"
        private const val KEY_SSID = "nearby_wifi_direct_ssid"
        private const val KEY_PASSWORD = "nearby_wifi_direct_password"
    }
}

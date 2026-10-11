/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.helpers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.UUID

/** Service-owned, manual P2P session. Framework callbacks are serialized on the main looper. */
class WifiDirectManager(private val service: BackgroundService,
                        private val manager: WifiP2pManager? = service.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager,
                        private val interfaceLookup: (String) -> NetworkInterface? = NetworkInterface::getByName,
                        private val interfaces: () -> List<NetworkInterface> = { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }) {
    enum class State { UNAVAILABLE, READY, SEARCHING, CONNECTING, GETTING_IP, VERIFYING, AWAITING_PAIRING, CONNECTED }
    data class Peer(val id: String, val name: String, val available: Boolean)
    val status = MutableLiveData(service.getString(R.string.nearby_disabled))
    val state = MutableLiveData(State.READY)
    val peers = MutableLiveData<List<Peer>>(emptyList())
    val connectedDeviceId = MutableLiveData<String?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var closed = false
    private var registered = false
    private var generation = 0
    private var discovering = false
    private var armed = false
    private var searchPending = false
    private var connectChecking = false
    private var pendingPeer: String? = null
    private var ownedGroup: String? = null
    private var sessionGroup: String? = null
    private var cleanupPending = false
    private var cancelledPeer: String? = null
    private var attemptDeadline = 0L
    private var tlsDeadline = 0L
    private var originalWifi: Network? = null
    private var lastDiscovery = 0L
    private val devices = linkedMapOf<String, Pair<String, WifiP2pDevice>>()
    @Volatile private var interfaceName: String? = null
    @Volatile private var systemGroupInterface: String? = null
    @Volatile var sessionGeneration: Int = 0
        private set
    @Volatile var authenticatedId: String? = null
        private set
    @Volatile var groupOwnerAddress: InetAddress? = null
        private set
    val groupInterface: NetworkInterface?
        get() = try { interfaceName?.let { interfaceLookup(it) } } catch (_: SocketException) { null }

    fun isGroupAddress(address: InetAddress): Boolean =
        groupInterface?.inetAddresses?.toList()?.contains(address.normalizedMappedIpv4Address()) == true

    fun canUseLocalAddress(address: InetAddress): Boolean {
        val name = systemGroupInterface ?: return true
        val iface = try { interfaceLookup(name) } catch (_: SocketException) { null }
        if (iface?.inetAddresses?.toList()?.contains(address.normalizedMappedIpv4Address()) != true) return true
        return interfaceName == name
    }

    fun sourceAddress(peer: InetAddress): InetAddress? = groupInterface?.interfaceAddresses?.firstOrNull {
        it.address is Inet4Address && peer.normalizedMappedIpv4Address() is Inet4Address && sameSubnet(it.address, peer, it.networkPrefixLength.toInt())
    }?.address

    private val searchTimeout = Runnable { stopDiscovery() }
    private val monitor = object : Runnable {
        override fun run() {
            if (closed || (!armed && sessionGroup == null && pendingPeer == null)) return
            val now = SystemClock.elapsedRealtime()
            val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (originalWifi != null && connectivity?.getNetworkCapabilities(originalWifi)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                finish(R.string.nearby_sta_lost)
                return
            }
            if (attemptDeadline > 0 && now >= attemptDeadline && interfaceName == null) {
                finish(R.string.nearby_timeout)
                return
            }
            if (tlsDeadline > 0 && now >= tlsDeadline && authenticatedId == null) {
                finish(R.string.nearby_no_kdeconnect)
                return
            }
            queryGroup()
            if (interfaceName != null && authenticatedId == null && now - lastDiscovery >= 2000) {
                lastDiscovery = now
                service.onNetworkChange(null)
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 0) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        finish(R.string.nearby_wifi_off)
                        state.value = State.UNAVAILABLE
                    } else if (!armed && sessionGroup == null) update(State.READY, R.string.nearby_disabled)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> if (discovering) requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> queryGroup()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (armed && device?.status == WifiP2pDevice.INVITED && attemptDeadline == 0L) {
                        attemptDeadline = SystemClock.elapsedRealtime() + 90000
                        update(State.CONNECTING, R.string.nearby_creating)
                    }
                }
            }
        }
    }

    init {
        val preferences = PreferenceManager.getDefaultSharedPreferences(service)
        if (!preferences.getBoolean("nearby_p2p_v2_migrated", false)) preferences.edit {
            remove("nearby_wifi_direct_enabled")
            remove("nearby_wifi_direct_ssid")
            remove("nearby_wifi_direct_password")
            putBoolean("nearby_p2p_v2_migrated", true)
        }
        if (Build.VERSION.SDK_INT >= 29 && manager != null) {
            initializeChannel()
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(service, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            KdeConnect.getInstance().addDeviceListChangedCallback("WifiDirectManager") {
                handler.post {
                    val id = authenticatedId ?: return@post
                    val device = KdeConnect.getInstance().devices[id]
                    update(if (device?.isPaired == true) State.CONNECTED else State.AWAITING_PAIRING,
                           if (device?.isPaired == true) R.string.nearby_connected else R.string.nearby_pairing)
                }
            }
            // Read existing group metadata without starting discovery or adopting a group.
            handler.post { queryGroup() }
        } else update(State.UNAVAILABLE, R.string.nearby_unsupported)
    }
    private fun initializeChannel() {
        channel = manager?.initialize(service, Looper.getMainLooper()) {
            // Ownership cannot be checked after channel loss; leave the group to the system.
            channel = null
            ownedGroup = null
            finish(R.string.nearby_channel_lost)
            state.value = State.UNAVAILABLE
        }
    }
    private fun update(value: State, message: Int) { state.value = value; status.value = service.getString(message) }
    private fun permitted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(service, permission) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
             ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
    private fun ready(): Boolean {
        if (closed || cleanupPending) return false
        if (Build.VERSION.SDK_INT < 29 || manager == null) {
            update(State.UNAVAILABLE, R.string.nearby_unsupported); return false
        }
        if (!permitted() || (Build.VERSION.SDK_INT < 33 &&
                (service.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled != true)) {
            update(State.UNAVAILABLE, R.string.nearby_permission_needed); return false
        }
        if (channel == null) initializeChannel()
        if (channel == null) { update(State.UNAVAILABLE, R.string.nearby_channel_lost); return false }
        return true
    }
    fun startDiscovery() { handler.post {
        if (!ready() || armed || searchPending || connectChecking || sessionGroup != null || pendingPeer != null) return@post
        val token = ++generation
        searchPending = true
        val current = channel ?: return@post
        try {
            manager?.requestGroupInfo(current) { group ->
                if (closed || token != generation) return@requestGroupInfo
                searchPending = false
                systemGroupInterface = group?.`interface`
                if (group != null) { update(State.UNAVAILABLE, R.string.nearby_group_busy); return@requestGroupInfo }
                val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                originalWifi = connectivity?.allNetworks?.firstOrNull {
                    val caps = connectivity.getNetworkCapabilities(it)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                cancelledPeer = null
                devices.clear()
                peers.value = emptyList()
                armed = true
                discovering = true
                attemptDeadline = 0
                handler.postDelayed(searchTimeout, 60000)
                handler.post(monitor)
                update(State.SEARCHING, R.string.nearby_searching)
                platformCall { manager.discoverPeers(current, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed || token != generation || !discovering) {
                            if (!discovering) platformCall { manager.stopPeerDiscovery(current, null) }
                            return
                        }
                        requestPeers()
                    }
                    override fun onFailure(reason: Int) {
                        if (token == generation && !closed) finish(R.string.nearby_group_error, reason)
                    }
                }) }
            }
        } catch (_: SecurityException) { finish(R.string.nearby_permission_needed) }
    } }
    fun stopDiscovery() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { stopDiscovery() }; return }
        handler.removeCallbacks(searchTimeout)
        if (searchPending) { searchPending = false; ++generation }
        if (discovering) {
            discovering = false
            try { channel?.let { manager?.stopPeerDiscovery(it, null) } }
            catch (_: SecurityException) { finish(R.string.nearby_permission_needed); return }
        }
        if (sessionGroup == null && pendingPeer == null && state.value != State.CONNECTING) {
            armed = false
            handler.removeCallbacks(monitor)
            update(State.READY, R.string.nearby_search_finished)
        }
    }
    private fun requestPeers() {
        val current = channel ?: return
        val token = generation
        try { manager?.requestPeers(current) { list ->
            if (!discovering || token != generation || closed) return@requestPeers
            val found = list.deviceList.associateBy { it.deviceAddress }
            for (device in found.values) {
                val id = devices[device.deviceAddress]?.first ?: UUID.randomUUID().toString()
                devices[device.deviceAddress] = id to device
            }
            peers.value = devices.map { (address, value) -> Peer(value.first, value.second.deviceName.ifBlank {
                service.getString(R.string.nearby_unnamed)
            }, found.containsKey(address)) }
        } } catch (_: SecurityException) { finish(R.string.nearby_permission_needed) }
    }
    fun connectPeer(id: String) { handler.post {
        if (!ready() || searchPending || connectChecking || sessionGroup != null || pendingPeer != null) return@post
        val address = devices.entries.firstOrNull { it.value.first == id }?.key ?: return@post
        connectChecking = true
        val token = generation
        val current = channel ?: return@post
        try { manager?.requestPeers(current) { list ->
            if (closed || token != generation) return@requestPeers
            if (list.deviceList.none { it.deviceAddress == address }) { connectChecking = false; update(State.READY, R.string.nearby_peer_lost); return@requestPeers }
            platformCall { manager.requestGroupInfo(current) { group ->
                if (closed || token != generation) return@requestGroupInfo
                connectChecking = false
                systemGroupInterface = group?.`interface`
                if (group != null) { update(State.UNAVAILABLE, R.string.nearby_group_busy); return@requestGroupInfo }
                pendingPeer = address
                armed = true
                stopDiscovery()
                attemptDeadline = SystemClock.elapsedRealtime() + 90000
                update(State.CONNECTING, R.string.nearby_creating)
                handler.removeCallbacks(monitor)
                handler.post(monitor)
                val config = WifiP2pConfig().apply { deviceAddress = address; wps.setup = WpsInfo.PBC }
                platformCall { manager.connect(current, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (token == generation && !closed) queryGroup()
                        else if (!armed && pendingPeer == null) platformCall { manager.cancelConnect(current, null) }
                    }
                    override fun onFailure(reason: Int) {
                        if (token == generation && !closed) {
                            // A rejected request cannot establish removal ownership of a later group.
                            pendingPeer = null
                            cancelledPeer = null
                            ownedGroup = null
                            finish(R.string.nearby_group_error, reason)
                        }
                    }
                }) }
            } }
        } } catch (_: SecurityException) { finish(R.string.nearby_permission_needed) }
    } }
    private fun fingerprint(group: WifiP2pGroup): String = "${group.`interface`}/${group.owner?.deviceAddress}/${group.networkName}"
    private fun queryGroup() {
        if (closed) return
        if (!permitted()) {
            if (armed || sessionGroup != null) finish(R.string.nearby_permission_needed)
            return
        }
        val current = channel ?: return
        val token = generation
        try { manager?.requestGroupInfo(current) { group ->
            if (closed || token != generation) return@requestGroupInfo
            systemGroupInterface = group?.`interface`
            if (group == null) {
                if (sessionGroup != null) finish(R.string.nearby_disconnected)
                return@requestGroupInfo
            }
            val key = fingerprint(group)
            if (sessionGroup != null && sessionGroup != key) {
                ownedGroup = null
                finish(R.string.nearby_group_busy)
                return@requestGroupInfo
            }
            if (sessionGroup == null && !armed && cancelledPeer != null) {
                val members = group.clientList.map { it.deviceAddress } + listOfNotNull(group.owner?.deviceAddress)
                if (cancelledPeer in members) {
                    cleanupPending = true
                    cleanupOwned(current, key)
                    cancelledPeer = null
                    return@requestGroupInfo
                }
            }
            if (sessionGroup == null) {
                val members = (group.clientList.map { it.deviceAddress } + listOfNotNull(group.owner?.deviceAddress)).toSet()
                if (!armed || (pendingPeer != null && pendingPeer !in members) || members.none { it in devices }) {
                    finish(R.string.nearby_group_busy)
                    state.value = State.UNAVAILABLE
                    return@requestGroupInfo
                }
                sessionGroup = key
                // Only an explicit connect() to the selected member establishes removal ownership.
                if (pendingPeer != null) ownedGroup = key
                if (attemptDeadline == 0L) attemptDeadline = SystemClock.elapsedRealtime() + 90000
                stopDiscovery()
                update(State.GETTING_IP, R.string.nearby_waiting)
            }
            platformCall { manager.requestConnectionInfo(current) { info ->
                if (closed || token != generation || sessionGroup != key || !info.groupFormed) return@requestConnectionInfo
                platformCall { applyGroupInfo(group, info, token) }
            } }
        } } catch (_: SecurityException) { finish(R.string.nearby_permission_needed) }
          catch (_: SocketException) { finish(R.string.nearby_disconnected) }
    }
    private fun applyGroupInfo(group: WifiP2pGroup, info: WifiP2pInfo, token: Int) {
        val iface = try { interfaceLookup(group.`interface`) } catch (_: SocketException) { null }
        val addresses = iface?.interfaceAddresses?.filter { it.address is Inet4Address && !it.address.isLoopbackAddress && it.networkPrefixLength in 1..32 }.orEmpty()
        if (addresses.isEmpty() || info.groupOwnerAddress == null) return
        val conflict = interfaces().any { other ->
            other.name != iface?.name && !other.isLoopback && other.interfaceAddresses.any { otherAddress ->
                otherAddress.address is Inet4Address && addresses.any {
                    sameSubnet(it.address, otherAddress.address, it.networkPrefixLength.toInt()) ||
                        sameSubnet(it.address, otherAddress.address, otherAddress.networkPrefixLength.toInt())
                }
            }
        }
        if (conflict) { finish(R.string.nearby_subnet_conflict); return }
        if (interfaceName == null) {
            interfaceName = group.`interface`
            groupOwnerAddress = info.groupOwnerAddress
            sessionGeneration = token
            tlsDeadline = SystemClock.elapsedRealtime() + 30000
            update(State.VERIFYING, R.string.nearby_verifying)
            service.onNetworkChange(null)
        }
    }
    private inline fun platformCall(block: () -> Unit) {
        try { block() } catch (_: SecurityException) { finish(R.string.nearby_permission_needed) }
          catch (_: SocketException) { finish(R.string.nearby_disconnected) }
    }
    fun onAuthenticated(id: String, token: Int) { handler.post {
        if (closed || interfaceName == null || token != sessionGeneration || (authenticatedId != null && authenticatedId != id)) return@post
        authenticatedId = id
        connectedDeviceId.value = id
        val paired = KdeConnect.getInstance().devices[id]?.isPaired == true
        update(if (paired) State.CONNECTED else State.AWAITING_PAIRING, if (paired) R.string.nearby_connected else R.string.nearby_pairing)
    } }
    fun onLinkLost(id: String, token: Int) { handler.post {
        if (id == authenticatedId && token == sessionGeneration) finish(R.string.nearby_disconnected)
    } }
    fun disconnect() { handler.post { finish(R.string.nearby_disconnected) } }
    private fun finish(message: Int, reason: Int? = null) {
        ++generation
        searchPending = false
        connectChecking = false
        stopDiscovery()
        handler.removeCallbacks(monitor)
        armed = false
        discovering = false
        attemptDeadline = 0
        tlsDeadline = 0
        interfaceName = null
        groupOwnerAddress = null
        authenticatedId = null
        connectedDeviceId.value = null
        ++sessionGeneration
        service.onWifiDirectEnded()
        val owned = ownedGroup
        val unknownOwnership = sessionGroup != null && owned == null
        ownedGroup = null
        sessionGroup = null
        val pending = pendingPeer != null
        if (pending) cancelledPeer = pendingPeer
        pendingPeer = null
        update(State.READY, message)
        if (reason != null) status.value = service.getString(message, reason)
        if (unknownOwnership) status.value = "${status.value} ${service.getString(R.string.nearby_system_group)}"
        val current = channel ?: return
        cleanupPending = owned != null || pending
        try {
            if (pending) manager?.cancelConnect(current, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { cleanupOwned(current, owned) }
                override fun onFailure(reason: Int) { cleanupOwned(current, owned) }
            }) else cleanupOwned(current, owned)
        } catch (_: SecurityException) { cleanupComplete(); update(State.UNAVAILABLE, R.string.nearby_permission_needed) }
    }
    private fun cleanupOwned(current: WifiP2pManager.Channel, owned: String?) {
        if (owned == null) {
            cleanupComplete()
            if (cancelledPeer != null && !closed) queryGroup()
            return
        }
        try { manager?.requestGroupInfo(current) { group ->
            if (group == null || fingerprint(group) != owned) { cleanupComplete(); return@requestGroupInfo }
            try { manager.removeGroup(current, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { cleanupComplete() }
                override fun onFailure(reason: Int) { cleanupComplete(); update(State.UNAVAILABLE, R.string.nearby_system_group) }
            }) } catch (_: SecurityException) { cleanupComplete(); update(State.UNAVAILABLE, R.string.nearby_system_group) }
        } } catch (_: SecurityException) { cleanupComplete(); update(State.UNAVAILABLE, R.string.nearby_system_group) }
    }
    private fun closeChannel() { channel?.close(); channel = null }
    private fun cleanupComplete() {
        cleanupPending = false
        if (closed) closeChannel()
    }
    fun close() {
        if (closed) return
        finish(R.string.nearby_disabled)
        closed = true
        handler.removeCallbacksAndMessages(null)
        KdeConnect.getInstance().removeDeviceListChangedCallback("WifiDirectManager")
        if (registered) { service.unregisterReceiver(receiver); registered = false }
        if (!cleanupPending) closeChannel()
        else handler.postDelayed({ closeChannel() }, 3000)
    }
    companion object {
        internal fun sameSubnet(first: InetAddress, second: InetAddress, prefix: Int): Boolean {
            val a = first.normalizedMappedIpv4Address().address
            val b = second.normalizedMappedIpv4Address().address
            if (a.size != 4 || b.size != 4 || prefix !in 1..32) return false
            val full = prefix / 8
            if ((0 until full).any { a[it] != b[it] }) return false
            val bits = prefix % 8
            return bits == 0 || ((a[full].toInt() xor b[full].toInt()) and (255 shl (8 - bits))) == 0
        }
    }
}

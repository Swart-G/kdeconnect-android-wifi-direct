/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.helpers

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Looper
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.MockSharedPreference
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class WifiDirectManagerTest {
    private lateinit var service: BackgroundService
    private lateinit var framework: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var direct: WifiDirectManager
    private lateinit var prefs: MockSharedPreference
    private var group: WifiP2pGroup? = null
    private var channelLost: WifiP2pManager.ChannelListener? = null
    private var discoverResult: WifiP2pManager.ActionListener? = null
    private var connectResult: WifiP2pManager.ActionListener? = null
    private var formAsOwner = false
    private val address = "02:00:00:00:00:01"
    private lateinit var peer: WifiP2pDevice
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun query() {
        WifiDirectManager::class.java.getDeclaredMethod("queryGroup").apply { isAccessible = true }.invoke(direct)
        idle()
    }
    @Before fun setUp() {
        prefs = MockSharedPreference()
        prefs.edit().putBoolean("nearby_wifi_direct_enabled", true).putString("trusted-certificate", "keep").apply()
        service = mockk(relaxed = true)
        framework = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        every { service.packageName } returns "org.kde.test"
        every { service.getString(any()) } answers { firstArg<Int>().toString() }
        every { service.getString(any(), *anyVararg()) } answers { firstArg<Int>().toString() }
        every { service.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { service.getSharedPreferences(any(), any()) } returns prefs
        every { service.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        mockkObject(KdeConnect.Companion)
        val app = mockk<KdeConnect>(relaxed = true)
        every { KdeConnect.getInstance() } returns app
        every { app.devices } returns java.util.concurrent.ConcurrentHashMap()
        every { framework.initialize(service, any(), any()) } answers { channelLost = thirdArg(); channel }
        every { framework.requestGroupInfo(channel, any()) } answers { secondArg<WifiP2pManager.GroupInfoListener>().onGroupInfoAvailable(group) }
        every { framework.discoverPeers(channel, any()) } answers { discoverResult = secondArg() }
        every { framework.connect(channel, any(), any()) } answers { connectResult = thirdArg() }
        every { framework.cancelConnect(channel, any()) } answers { secondArg<WifiP2pManager.ActionListener?>()?.onSuccess() }
        every { framework.removeGroup(channel, any()) } answers { group = null; secondArg<WifiP2pManager.ActionListener?>()?.onSuccess() }
        peer = WifiP2pDevice().apply { deviceName = "Computer"; deviceAddress = address }
        val list = mockk<WifiP2pDeviceList>()
        every { list.deviceList } returns listOf(peer)
        every { framework.requestPeers(channel, any()) } answers { secondArg<WifiP2pManager.PeerListListener>().onPeersAvailable(list) }
        every { framework.requestConnectionInfo(channel, any()) } answers {
            secondArg<WifiP2pManager.ConnectionInfoListener>().onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true; isGroupOwner = formAsOwner; groupOwnerAddress = InetAddress.getByName("10.77.88.1")
            })
        }
        val iface = mockk<NetworkInterface>()
        val ip = mockk<InterfaceAddress>()
        every { ip.address } returns InetAddress.getByName("10.77.88.2")
        every { ip.networkPrefixLength } returns 24
        every { iface.interfaceAddresses } returns listOf(ip)
        every { iface.inetAddresses } answers { java.util.Collections.enumeration(listOf(ip.address)) }
        every { iface.name } returns "test-p2p"
        direct = WifiDirectManager(service, framework, { iface }, { emptyList() })
    }
    @After fun tearDown() { direct.close(); unmockkAll() }
    private fun search() { direct.startDiscovery(); idle(); discoverResult?.onSuccess(); idle() }
    private fun formed() {
        group = mockk<WifiP2pGroup>().also {
            every { it.`interface` } returns "test-p2p"
            every { it.networkName } returns "system-group"
            every { it.owner } returns peer
            every { it.clientList } returns listOf(peer)
        }
        query()
    }
    @Test fun migrationAndManualStart() {
        assertFalse(prefs.contains("nearby_wifi_direct_enabled"))
        assertEquals("keep", prefs.getString("trusted-certificate", null))
        idle(1000)
        verify(exactly = 0) { framework.discoverPeers(any(), any()) }
        verify(exactly = 0) { framework.createGroup(any(), any<WifiP2pManager.ActionListener>()) }
        search(); assertEquals(WifiDirectManager.State.SEARCHING, direct.state.value)
        assertEquals(1, direct.peers.value?.size)
        idle(60000); assertEquals(WifiDirectManager.State.READY, direct.state.value)
        verify(exactly = 1) { framework.stopPeerDiscovery(channel, any()) }
        idle(60000); verify(exactly = 1) { framework.discoverPeers(channel, any()) }
    }
    @Test fun permissionDenial() {
        every { service.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        direct.startDiscovery(); idle()
        assertEquals(WifiDirectManager.State.UNAVAILABLE, direct.state.value)
        verify(exactly = 0) { framework.discoverPeers(any(), any()) }
    }
    @Test fun legacyAndroidRequiresLocationSwitch() {
        // Keep the sandbox stable while exercising the pre-Android 13 permission branch.
        val sdk = android.os.Build.VERSION.SDK_INT
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 29)
        try {
            direct.startDiscovery(); idle()
            assertEquals(WifiDirectManager.State.UNAVAILABLE, direct.state.value)
            verify(exactly = 0) { framework.discoverPeers(any(), any()) }
            val location = mockk<android.location.LocationManager>()
            every { location.isLocationEnabled } returns true
            every { service.getSystemService(Context.LOCATION_SERVICE) } returns location
            search()
            assertEquals(WifiDirectManager.State.SEARCHING, direct.state.value)
            verify { service.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) }
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }
    @Test fun doesNotAdoptOrRemoveExistingGroup() {
        formed(); direct.startDiscovery(); idle(); direct.disconnect(); idle()
        verify(exactly = 0) { framework.discoverPeers(any(), any()) }
        verify(exactly = 0) { framework.removeGroup(any(), any()) }
        assertNull(direct.groupInterface)
        assertFalse(direct.canUseLocalAddress(InetAddress.getByName("10.77.88.2")))
    }
    @Test fun inboundConnectionPreservesUnknownOwnership() {
        search(); formed()
        assertEquals(WifiDirectManager.State.VERIFYING, direct.state.value)
        assertNotNull(direct.groupInterface)
        assertTrue(direct.canUseLocalAddress(InetAddress.getByName("10.77.88.2")))
        direct.disconnect(); idle()
        verify(exactly = 0) { framework.removeGroup(any(), any()) }
        assertNull(direct.groupInterface)
        assertNull(direct.connectedDeviceId.value)
        assertFalse(direct.canUseLocalAddress(InetAddress.getByName("10.77.88.2")))
    }
    @Test fun bothRolesAndTlsTimeout() {
        for (owner in listOf(false, true)) {
            formAsOwner = owner
            group = null; search()
            direct.connectPeer(direct.peers.value!!.first().id); idle(); connectResult?.onSuccess(); formed()
            assertEquals(WifiDirectManager.State.VERIFYING, direct.state.value)
            assertEquals(InetAddress.getByName("10.77.88.1"), direct.groupOwnerAddress)
            idle(31000)
            assertEquals(WifiDirectManager.State.READY, direct.state.value)
            assertNull(direct.groupInterface)
        }
        verify(exactly = 2) { framework.removeGroup(channel, any()) }
    }
    @Test fun cancelCleansLateOwnedGroup() {
        search(); direct.connectPeer(direct.peers.value!!.first().id); idle()
        direct.disconnect(); idle(); connectResult?.onSuccess(); formed()
        verify(exactly = 1) { framework.removeGroup(channel, any()) }
        assertNull(direct.groupInterface)
    }
    @Test fun lateSuccessDoesNotCancelNewManualAttempt() {
        search(); direct.connectPeer(direct.peers.value!!.first().id); idle()
        val cancelled = connectResult
        direct.disconnect(); idle()
        search(); direct.connectPeer(direct.peers.value!!.first().id); idle()
        clearMocks(framework, answers = false, recordedCalls = true)
        cancelled?.onSuccess(); idle()
        verify(exactly = 0) { framework.cancelConnect(channel, any()) }
        assertEquals(WifiDirectManager.State.CONNECTING, direct.state.value)
    }
    @Test fun rejectedRequestDoesNotOwnALaterForeignGroup() {
        search(); direct.connectPeer(direct.peers.value!!.first().id); idle()
        connectResult?.onFailure(WifiP2pManager.BUSY); idle()
        formed()
        assertNull(direct.groupInterface)
        verify(exactly = 0) { framework.removeGroup(channel, any()) }
        verify(exactly = 0) { framework.cancelConnect(channel, any()) }
    }
    @Test fun channelLossRequiresNewAction() {
        search(); channelLost?.onChannelDisconnected(); idle(120000)
        assertEquals(WifiDirectManager.State.UNAVAILABLE, direct.state.value)
        verify(exactly = 1) { framework.discoverPeers(channel, any()) }
        verify(exactly = 0) { framework.removeGroup(channel, any()) }
        search()
        assertEquals(WifiDirectManager.State.SEARCHING, direct.state.value)
        verify(exactly = 2) { framework.initialize(service, any(), any()) }
    }
    @Test fun negotiationTimeoutStopsWithoutRetry() {
        search(); direct.connectPeer(direct.peers.value!!.first().id); idle()
        idle(91000)
        assertEquals(WifiDirectManager.State.READY, direct.state.value)
        verify(atLeast = 1) { framework.cancelConnect(channel, any()) }
        idle(120000); verify(exactly = 1) { framework.discoverPeers(channel, any()) }
    }
    @Test fun stopCancelsPendingDiscoveryPreflight() {
        var pending: WifiP2pManager.GroupInfoListener? = null
        every { framework.requestGroupInfo(channel, any()) } answers { pending = secondArg() }
        direct.startDiscovery(); idle()
        direct.stopDiscovery(); idle()
        pending?.onGroupInfoAvailable(null); idle()
        verify(exactly = 0) { framework.discoverPeers(channel, any()) }
    }
    @Test fun permissionRevocationInsideCallbackIsHandled() {
        every { framework.discoverPeers(channel, any()) } throws SecurityException("permission revoked")
        direct.startDiscovery(); idle()
        assertEquals(WifiDirectManager.State.READY, direct.state.value)
        assertNull(direct.groupInterface)
    }
    @Test fun permissionRevocationWhileStoppingSearchIsHandled() {
        search()
        every { framework.stopPeerDiscovery(channel, any()) } throws SecurityException("permission revoked")
        direct.stopDiscovery(); idle()
        assertEquals(WifiDirectManager.State.READY, direct.state.value)
        assertNull(direct.groupInterface)
        idle(120000)
        verify(exactly = 1) { framework.discoverPeers(channel, any()) }
    }
    @Test fun wifiOffEndsOperation() {
        search()
        val receiver = WifiDirectManager::class.java.getDeclaredField("receiver").apply { isAccessible = true }.get(direct) as android.content.BroadcastReceiver
        receiver.onReceive(service, android.content.Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED))
        idle(120000)
        assertEquals(WifiDirectManager.State.UNAVAILABLE, direct.state.value)
        verify(exactly = 1) { framework.discoverPeers(channel, any()) }
    }
    @Test fun lostGroupDoesNotRediscover() {
        search(); formed(); group = null; query(); idle(120000)
        assertEquals(WifiDirectManager.State.READY, direct.state.value)
        assertNull(direct.groupInterface)
        verify(exactly = 1) { framework.discoverPeers(channel, any()) }
        verify(exactly = 0) { framework.removeGroup(channel, any()) }
    }
    @Test fun subnetMatchingUsesActualPrefix() {
        assertTrue(WifiDirectManager.sameSubnet(InetAddress.getByName("10.2.3.4"), InetAddress.getByName("10.2.3.200"), 24))
        assertFalse(WifiDirectManager.sameSubnet(InetAddress.getByName("10.2.3.4"), InetAddress.getByName("10.2.4.1"), 24))
        assertTrue(WifiDirectManager.sameSubnet(InetAddress.getByName("10.2.3.4"), InetAddress.getByName("10.2.3.4"), 32))
        assertFalse(WifiDirectManager.sameSubnet(InetAddress.getByName("10.2.3.4"), InetAddress.getByName("10.2.3.4"), 0))
    }
}

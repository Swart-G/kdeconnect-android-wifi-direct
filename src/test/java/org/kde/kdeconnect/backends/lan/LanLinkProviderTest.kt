/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.backends.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.helpers.ThreadHelper
import java.security.cert.Certificate
import javax.net.ssl.SSLSocket
import java.util.concurrent.ConcurrentHashMap
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.TrustedNetworkHelper
import org.kde.kdeconnect.helpers.WifiDirectManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.SocketFactory

class LanLinkProviderTest {
    private val service = mockk<BackgroundService>()
    private val directManager = mockk<WifiDirectManager>()
    private val groupAddress = InetAddress.getByName("192.168.49.1")
    private val otherAddress = InetAddress.getByName("192.168.1.10")
    private lateinit var provider: LanLinkProvider
    private lateinit var identity: String

    @Before
    fun setUp() {
        mockkStatic(Log::class, DeviceHelper::class, TrustedDevices::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any<Throwable>()) } returns 0
        mockkObject(TrustedNetworkHelper.Companion)
        every { DeviceHelper.getDeviceId(service) } returns "a".repeat(32)
        every { TrustedDevices.isTrustedDevice(service, any()) } returns false
        every { TrustedNetworkHelper.isTrustedNetwork(service) } returns false
        every { service.applicationContext } returns service
        every { service.wifiDirectManager } returns directManager
        every { directManager.sourceAddress(any()) } returns null
        every { directManager.canUseLocalAddress(any()) } returns true
        every { directManager.isGroupAddress(any()) } answers { firstArg<InetAddress>() == groupAddress }
        every { service.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>()
        val wifi = mockk<WifiManager>()
        every { service.getSystemService(Context.WIFI_SERVICE) } returns wifi
        every { wifi.createMulticastLock(any()) } returns mockk<WifiManager.MulticastLock>()
        provider = LanLinkProvider(service)
        identity = NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY).apply {
            set("deviceId", "b".repeat(32))
            set("deviceName", "Computer")
            set("protocolVersion", DeviceHelper.PROTOCOL_VERSION)
        }.serialize()
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun acceptsUnpairedDeviceThroughOwnGroup() {
        assertNotNull(provider.unserializeReceivedIdentityPacket(identity, groupAddress))
    }

    @Test
    fun rejectsUnpairedDeviceThroughOtherInterface() {
        assertNull(provider.unserializeReceivedIdentityPacket(identity, otherAddress))
    }

    @Test
    fun rejectsUnpairedDeviceWithoutKnownInterface() {
        assertNull(provider.unserializeReceivedIdentityPacket(identity))
    }

    @Test
    fun rejectsUnpairedDeviceAfterGroupStops() {
        every { directManager.isGroupAddress(any()) } returns false
        assertNull(provider.unserializeReceivedIdentityPacket(identity, groupAddress))
    }

    @Test
    fun acceptsPairedDeviceThroughOtherInterface() {
        every { TrustedDevices.isTrustedDevice(service, any()) } returns true
        assertNotNull(provider.unserializeReceivedIdentityPacket(identity, otherAddress))
    }
    @Test
    fun rejectsPairedDeviceThroughAnInactiveSystemGroup() {
        every { TrustedDevices.isTrustedDevice(service, any()) } returns true
        every { TrustedNetworkHelper.isTrustedNetwork(service) } returns true
        every { directManager.canUseLocalAddress(groupAddress) } returns false
        assertNull(provider.unserializeReceivedIdentityPacket(identity, groupAddress))
    }

    @Test
    fun acceptsUnpairedDeviceOnTrustedNetwork() {
        every { TrustedNetworkHelper.isTrustedNetwork(service) } returns true
        assertNotNull(provider.unserializeReceivedIdentityPacket(identity, otherAddress))
    }

    private fun receiveUdpIdentity() {
        val bytes = identity.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("192.168.49.2"), 1716)
        val receive = LanLinkProvider::class.java.getDeclaredMethod("udpPacketReceived", DatagramPacket::class.java)
        receive.isAccessible = true
        receive.invoke(provider, packet)
    }

    private fun mockOutgoingSocket(localAddress: InetAddress): Pair<SocketFactory, Socket> {
        mockkStatic(SocketFactory::class)
        val factory = mockk<SocketFactory>()
        val socket = mockk<Socket>()
        every { SocketFactory.getDefault() } returns factory
        every { factory.createSocket() } returns socket
        every { socket.localAddress } returns localAddress
        every { socket.connect(any(), any()) } returns Unit
        every { socket.close() } returns Unit
        return factory to socket
    }

    @Test
    fun ignoresUntrustedUdpWhenNoGroupIsActive() {
        val (factory, _) = mockOutgoingSocket(otherAddress)
        every { directManager.groupInterface } returns null
        receiveUdpIdentity()
        verify(exactly = 0) { factory.createSocket() }
    }

    @Test
    fun doesNotSendIdentityThroughUntrustedUdpRoute() {
        val (_, socket) = mockOutgoingSocket(otherAddress)
        every { directManager.groupInterface } returns mockk<NetworkInterface>()
        receiveUdpIdentity()
        verify(exactly = 0) { socket.getOutputStream() }
        verify(exactly = 1) { socket.close() }
    }

    @Test
    fun allowsIdentityThroughOwnGroupUdpRoute() {
        val (_, socket) = mockOutgoingSocket(groupAddress)
        every { directManager.groupInterface } returns mockk<NetworkInterface>()
        every { socket.setKeepAlive(any()) } returns Unit
        every { socket.setSoTimeout(any()) } returns Unit
        val deviceInfo = mockk<DeviceInfo>()
        every { DeviceHelper.getDeviceInfo(service) } returns deviceInfo
        every { deviceInfo.toIdentityPacket() } returns NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY)
        every { socket.getOutputStream() } throws IOException("Stop before the TLS handshake")
        receiveUdpIdentity()
        verify(exactly = 1) { socket.getOutputStream() }
        verify(exactly = 1) { socket.close() }
    }
    private fun existingLink(certificate: Certificate): Pair<LanLink, SSLSocket> {
        mockkStatic(ThreadHelper::class)
        every { ThreadHelper.execute(any()) } returns Unit
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.localAddress } returns otherAddress
        val info = DeviceInfo("verified-id", certificate, "Computer", DeviceType.DESKTOP)
        val link = LanLink(service, info, provider, socket)
        val field = LanLinkProvider::class.java.getDeclaredField("visibleDevices").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(provider) as ConcurrentHashMap<String, LanLink>)[info.id] = link
        return link to socket
    }

    private fun replace(socket: SSLSocket, certificate: Certificate, token: Int?) {
        val info = DeviceInfo("verified-id", certificate, "Computer", DeviceType.DESKTOP)
        val method = LanLinkProvider::class.java.getDeclaredMethod("addOrUpdateLink", SSLSocket::class.java, DeviceInfo::class.java, Integer::class.java)
        method.isAccessible = true
        method.invoke(provider, socket, info, token)
    }

    private fun directSocket(): SSLSocket {
        every { directManager.sessionGeneration } returns 7
        every { directManager.authenticatedId } returns null
        every { directManager.onAuthenticated(any(), any()) } returns Unit
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.localAddress } returns groupAddress
        return socket
    }

    @Test fun refusesCertificateChangeDuringP2pHandover() {
        val (_, oldSocket) = existingLink(mockk<Certificate>())
        val replacement = directSocket()
        replace(replacement, mockk<Certificate>(), 7)
        verify(exactly = 1) { replacement.close() }
        verify(exactly = 0) { oldSocket.close() }
        verify(exactly = 0) { directManager.onAuthenticated(any(), any()) }
    }

    @Test fun prefersP2pAndRefusesLanReplacement() {
        val cert = mockk<Certificate>()
        val (link, oldSocket) = existingLink(cert)
        val replacement = directSocket()
        replace(replacement, cert, 7)
        verify(exactly = 1) { oldSocket.close() }
        assertEquals(groupAddress, link.localAddress)
        val lan = mockk<SSLSocket>(relaxed = true)
        every { lan.localAddress } returns otherAddress
        replace(lan, cert, null)
        verify(exactly = 1) { lan.close() }
        verify(exactly = 0) { replacement.close() }
        assertEquals(groupAddress, link.localAddress)
    }

    @Test fun refusesLateTlsCompletionFromCancelledSession() {
        val cert = mockk<Certificate>()
        val (_, oldSocket) = existingLink(cert)
        val replacement = directSocket()
        every { directManager.sessionGeneration } returns 8
        replace(replacement, cert, 7)
        verify(exactly = 1) { replacement.close() }
        verify(exactly = 0) { oldSocket.close() }
    }

}

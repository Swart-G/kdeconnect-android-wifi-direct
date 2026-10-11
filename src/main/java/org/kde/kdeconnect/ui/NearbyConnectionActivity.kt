/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.WifiDirectManager
import org.kde.kdeconnect_tp.R

class NearbyConnectionActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private var observedManager: WifiDirectManager? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startNearby() else status.setText(R.string.nearby_permission_needed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply { setTitle(R.string.nearby_title) }
        layout.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(ScrollView(this).apply { addView(content) })
        content.addView(TextView(this).apply { setText(R.string.nearby_instructions); textSize = 17f })
        status = TextView(this).apply { setText(R.string.nearby_disabled); setPadding(0, 32, 0, 32) }
        content.addView(status)
        content.addView(Button(this).apply { setText(R.string.nearby_start); setOnClickListener { requestNearby() } })
        content.addView(Button(this).apply {
            setText(R.string.nearby_stop)
            setOnClickListener { BackgroundService.instance?.wifiDirectManager?.stopDiscovery() }
        })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(peerList)
        content.addView(Button(this).apply {
            setText(R.string.nearby_disconnect)
            setOnClickListener { BackgroundService.instance?.wifiDirectManager?.disconnect() }
        })
        content.addView(Button(this).apply {
            setText(R.string.nearby_open_device)
            setOnClickListener {
                val id = BackgroundService.instance?.wifiDirectManager?.connectedDeviceId?.value ?: return@setOnClickListener
                startActivity(android.content.Intent(this@NearbyConnectionActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, id))
            }
        })
        setContentView(layout)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        BackgroundService.Start(this)
        observeManager()
    }

    override fun onResume() {
        super.onResume()
        observeManager()
    }

    private fun observeManager() {
        val manager = BackgroundService.instance?.wifiDirectManager
        if (manager == null) {
            status.postDelayed({ if (!isFinishing && !isDestroyed) observeManager() }, 250)
            return
        }
        if (observedManager === manager) return
        observedManager?.status?.removeObservers(this)
        observedManager?.peers?.removeObservers(this)
        observedManager = manager
        manager.status.observe(this) { status.text = it }
        manager.peers.observe(this) { peers ->
            peerList.removeAllViews()
            peers.forEach { peer ->
                peerList.addView(Button(this).apply {
                    text = getString(R.string.nearby_peer, peer.name)
                    isEnabled = peer.available
                    setOnClickListener { manager.connectPeer(peer.id) }
                })
            }
        }
    }

    private fun requestNearby() {
        if (Build.VERSION.SDK_INT < 29) { status.setText(R.string.nearby_unsupported); return }
        val wifiPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT in 31..32) needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            needed.add(wifiPermission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (needed.isEmpty()) startNearby() else permissions.launch(needed.toTypedArray())
    }

    private fun startNearby() {
        observeManager()
        val manager = BackgroundService.instance?.wifiDirectManager
        if (manager == null) {
            status.setText(R.string.nearby_service_starting)
            return
        }
        manager.startDiscovery()
    }
}

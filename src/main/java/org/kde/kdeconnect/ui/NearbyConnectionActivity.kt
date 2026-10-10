/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
    private lateinit var network: TextView
    private lateinit var password: TextView
    private var observedManager: WifiDirectManager? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startNearby() else status.setText(R.string.nearby_permission_needed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply { setTitle(R.string.nearby_title) }
        layout.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(content)
        content.addView(TextView(this).apply { setText(R.string.nearby_instructions); textSize = 17f })
        status = TextView(this).apply { setText(R.string.nearby_disabled); setPadding(0, 32, 0, 32) }
        content.addView(status)
        network = TextView(this).apply { textSize = 17f; setTextIsSelectable(true) }
        password = TextView(this).apply { textSize = 17f; setTextIsSelectable(true); setPadding(0, 16, 0, 32) }
        content.addView(network)
        content.addView(password)
        content.addView(Button(this).apply { setText(R.string.nearby_start); setOnClickListener { requestNearby() } })
        content.addView(Button(this).apply {
            setText(R.string.nearby_stop)
            setOnClickListener { BackgroundService.instance?.wifiDirectManager?.setEnabled(false) }
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
        observedManager?.networkCredentials?.removeObservers(this)
        observedManager = manager
        manager.status.observe(this) { status.text = it }
        manager.networkCredentials.observe(this) { credentials ->
            network.text = credentials?.let { getString(R.string.nearby_network_name, it.ssid) } ?: ""
            password.text = credentials?.let { getString(R.string.nearby_network_password, it.password) } ?: ""
        }
    }

    private fun requestNearby() {
        if (Build.VERSION.SDK_INT < 29) { status.setText(R.string.nearby_unsupported); return }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(permission))
        } else startNearby()
    }

    private fun startNearby() {
        observeManager()
        val manager = BackgroundService.instance?.wifiDirectManager
        if (manager == null) {
            status.setText(R.string.nearby_service_starting)
            return
        }
        manager.setEnabled(true)
    }
}

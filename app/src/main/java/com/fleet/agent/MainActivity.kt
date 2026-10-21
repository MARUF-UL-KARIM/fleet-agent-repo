package com.fleet.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fleet.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val projLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (isFinishing || !::b.isInitialized) return@registerForActivityResult
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val uri = res.data!!.toUri(Intent.URI_INTENT_SCHEME).toString()
            Prefs.saveProjection(this, res.resultCode, uri)
            b.tvStatus.text = "projection granted — starting service"
            startAgent()
        } else {
            b.tvStatus.text = "projection denied"
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (::b.isInitialized) b.tvStatus.text = "permissions: $it" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.etBroker.setText(Prefs.brokerHost(this))
        b.btnSaveBroker.setOnClickListener {
            val h = b.etBroker.text.toString().trim()
            if (h.isNotEmpty()) {
                Prefs.setBrokerHost(this, h)
                b.tvStatus.text = "broker saved: $h"
            }
        }
        b.btnPermissions.setOnClickListener { requestPerms() }
        b.btnActivate.setOnClickListener { requestProjection() }

        maybeIgnoreBattery()
        b.tvStatus.text = "id=${Prefs.deviceName(this)}  broker=${Prefs.brokerHost(this)}"
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun requestProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startAgent() {
        val i = Intent(this, AgentService::class.java)
            .setAction(AgentService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Throwable) {}
    }

    private fun maybeIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Throwable) {}
        }
    }
}
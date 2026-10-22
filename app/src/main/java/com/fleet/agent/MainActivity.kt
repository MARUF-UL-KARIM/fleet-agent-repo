package com.fleet.agent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val projLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val uri = res.data!!.toUri(Intent.URI_INTENT_SCHEME).toString()
            Prefs.saveProjection(this, res.resultCode, uri)
            startAgent()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
        finish() // অ্যাপ স্ক্রিন বন্ধ করে দেবে
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startAgent() {
        val i = Intent(this, AgentService::class.java).setAction(AgentService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Throwable) {}
    }
}
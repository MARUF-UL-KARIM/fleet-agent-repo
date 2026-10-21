package com.fleet.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

class AgentService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_DRAIN = "drain"
        const val ACTION_STOP  = "stop"
        const val CH = "fleet_channel"
        const val NOTIF_ID = 7
    }

    private val netExec = Executors.newSingleThreadExecutor()
    private val workExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var screen: ScreenCapture
    @Volatile private var mq: MqttClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("booting"))
        screen = ScreenCapture(this)
        val ok = screen.init()
        startForeground(NOTIF_ID, buildNotif(if (ok) "online" else "no projection — reactivate"))
        mq = MqttClient(this, onCommand = ::handleCommand, onConnected = {
            workExec.execute { drainQueue() }
        })
        netExec.execute {
            while (!Thread.currentThread().isInterrupted) {
                try { mq?.connect(); break } catch (_: Throwable) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DRAIN -> workExec.execute { drainQueue() }
            ACTION_STOP  -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { mq?.disconnect() } catch (_: Throwable) {}
        try { screen.release() } catch (_: Throwable) {}
        netExec.shutdownNow()
        workExec.shutdownNow()
        super.onDestroy()
    }

    private fun handleCommand(cmd: JSONObject) {
        when (cmd.optString("action")) {
            "screenshot" -> {
                val q = cmd.optInt("quality", 80)
                workExec.execute {
                    val jpg = screen.screenshotJpeg(q)
                    if (jpg != null) {
                        val ev = JSONObject()
                            .put("kind", "screenshot")
                            .put("data", android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP))
                        mq?.publishEvent(ev)
                    } else {
                        mq?.publishEvent(JSONObject().put("kind", "error").put("reason", "screenshot_failed"))
                    }
                }
            }
            "record" -> {
                val sec = cmd.optInt("duration", 10).coerceIn(1, 300)
                main.post {
                    screen.record(sec) { bytes ->
                        workExec.execute {
                            if (bytes != null) {
                                publishVideo(sec, bytes)
                            } else {
                                mq?.publishEvent(JSONObject().put("kind", "error").put("reason", "record_failed"))
                            }
                        }
                    }
                }
            }
            "ping" -> mq?.publishEvent(JSONObject().put("kind", "pong"))
        }
    }

    private fun publishVideo(durationSec: Int, bytes: ByteArray) {
        val msgId = java.util.UUID.randomUUID().toString()
        val chunkSize = 200 * 1024
        val total = (bytes.size + chunkSize - 1) / chunkSize

        val meta = JSONObject()
            .put("kind", "video_meta")
            .put("msgId", msgId)
            .put("duration", durationSec)
            .put("size", bytes.size)
            .put("chunks", total)
        if (mq?.publishEvent(meta) != true) {
            mq?.publishEvent(JSONObject().put("kind", "error").put("reason", "video_meta_failed"))
            return
        }

        var idx = 0
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(off, end)
            val header = JSONObject()
                .put("msgId", msgId)
                .put("idx", idx)
                .put("total", total)
            val hdr = header.toString().toByteArray(Charsets.UTF_8)
            val payload = ByteArray(4 + hdr.size + chunk.size)
            payload[0] = ((hdr.size ushr 24) and 0xFF).toByte()
            payload[1] = ((hdr.size ushr 16) and 0xFF).toByte()
            payload[2] = ((hdr.size ushr 8) and 0xFF).toByte()
            payload[3] = (hdr.size and 0xFF).toByte()
            System.arraycopy(hdr, 0, payload, 4, hdr.size)
            System.arraycopy(chunk, 0, payload, 4 + hdr.size, chunk.size)
            mq?.publishRaw(payload, qos = 0)
            off = end; idx++
        }
    }

    private fun drainQueue() {
        val items = UploadQueue.drain(this)
        for ((file, obj) in items) {
            val ok = mq?.publishEvent(obj) == true
            if (ok) UploadQueue.remove(file) else break
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CH, "Fleet Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(status: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setContentTitle("Fleet Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }
}
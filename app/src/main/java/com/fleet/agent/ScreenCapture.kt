package com.fleet.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream
import java.io.File

class ScreenCapture(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var shotDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    @Volatile private var recording = false

    fun init(): Boolean {
        val code = Prefs.projectionCode(ctx)
        val data = Prefs.projectionData(ctx)
        if (code < 0 || data == null) return false
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
        val intent = try { Intent.parseUri(data, Intent.URI_INTENT_SCHEME) } catch (_: Throwable) {
            Prefs.clearProjection(ctx); return false
        }
        projection = try { mgr.getMediaProjection(code, intent) } catch (_: Throwable) { null }
            ?: run { Prefs.clearProjection(ctx); return false }

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                try { reader?.close() } catch (_: Throwable) {}
                try { shotDisplay?.release() } catch (_: Throwable) {}
                reader = null; shotDisplay = null; projection = null
                Prefs.clearProjection(ctx)
            }
        }, Handler(Looper.getMainLooper()))

        val dm: DisplayMetrics = ctx.resources.displayMetrics
        width = dm.widthPixels
        height = dm.heightPixels
        dpi = dm.densityDpi

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        shotDisplay = projection!!.createVirtualDisplay(
            "shot", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
        return true
    }

    fun isReady(): Boolean = projection != null && reader != null

    fun screenshotJpeg(quality: Int = 80): ByteArray? {
        val r = reader ?: return null
        var img: android.media.Image? = null
        val deadline = SystemClock.uptimeMillis() + 700
        while (img == null && SystemClock.uptimeMillis() < deadline) {
            img = r.acquireLatestImage()
            if (img == null) Thread.sleep(20)
        }
        if (img == null) return null
        try {
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val rowPadding = rowStride - pixStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle(); cropped.recycle()
            return out.toByteArray()
        } finally {
            img.close()
        }
    }

    fun record(durationSec: Int, onDone: (ByteArray?) -> Unit) {
        val p = projection
        if (p == null) { onDone(null); return }
        if (recording) { onDone(null); return }
        recording = true

        val evenW = width and 0xFFFE
        val evenH = height and 0xFFFE
        val outFile = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.mp4")

        @Suppress("DEPRECATION")
        val rec = MediaRecorder()
        var disp: VirtualDisplay? = null
        try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(evenW, evenH)
            rec.setVideoFrameRate(24)
            rec.setVideoEncodingBitRate(2_000_000)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            disp = p.createVirtualDisplay(
                "rec", evenW, evenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
        } catch (_: Throwable) {
            try { rec.reset() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            try { disp?.release() } catch (_: Throwable) {}
            try { outFile.delete() } catch (_: Throwable) {}
            recording = false
            onDone(null); return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.reset() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            try { disp?.release() } catch (_: Throwable) {}
            val bytes = try { outFile.readBytes() } catch (_: Throwable) { null }
            try { outFile.delete() } catch (_: Throwable) {}
            recording = false
            onDone(bytes)
        }, durationSec * 1000L)
    }

    fun release() {
        try { reader?.close() } catch (_: Throwable) {}
        try { shotDisplay?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        reader = null; shotDisplay = null; projection = null
    }
}
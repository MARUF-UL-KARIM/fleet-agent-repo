package com.fleet.agent

import android.content.Context
import org.json.JSONObject

object UploadQueue {
    private fun dir(ctx: Context) = ctx.filesDir.apply { mkdirs() }

    private fun write(ctx: Context, obj: JSONObject) {
        val f = java.io.File(dir(ctx), "q_${System.currentTimeMillis()}_${(0..999).random()}.json")
        f.writeText(obj.toString())
    }

    fun enqueueSms(ctx: Context, from: String, body: String, ts: Long) {
        val o = JSONObject()
            .put("kind", "sms")
            .put("from", from)
            .put("body", body)
            .put("ts", ts)
        write(ctx, o)
    }

    fun drain(ctx: Context): List<Pair<java.io.File, JSONObject>> {
        val files = dir(ctx).listFiles { f -> f.name.startsWith("q_") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            try { f to JSONObject(f.readText()) } catch (_: Throwable) { null }
        }
    }

    fun remove(f: java.io.File) { try { f.delete() } catch (_: Throwable) {} }
}
package com.fleet.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val i = Intent(ctx, AgentService::class.java).setAction(AgentService.ACTION_START)
        try { ctx.startForegroundService(i) } catch (_: Throwable) {}
    }
}
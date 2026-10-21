package com.fleet.agent

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object Prefs {
    private const val FILE = "fleet_prefs"
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val s = sp(ctx)
        var id = s.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            s.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun deviceName(ctx: Context): String {
        val s = sp(ctx)
        var n = s.getString("device_name", null)
        if (n == null) {
            n = "${android.os.Build.MODEL}-${deviceId(ctx)}"
            s.edit().putString("device_name", n).apply()
        }
        return n
    }

    fun brokerHost(ctx: Context): String =
        sp(ctx).getString("broker_host", BuildConfig.BROKER_HOST)!!

    fun setBrokerHost(ctx: Context, host: String) =
        sp(ctx).edit().putString("broker_host", host).apply()

    fun mqttUser(ctx: Context): String =
        sp(ctx).getString("mqtt_user", BuildConfig.MQTT_USER)!!

    fun mqttPass(ctx: Context): String =
        sp(ctx).getString("mqtt_pass", BuildConfig.MQTT_PASS)!!

    fun projectionCode(ctx: Context): Int = sp(ctx).getInt("proj_code", -1)
    fun projectionData(ctx: Context): String? = sp(ctx).getString("proj_data", null)

    fun saveProjection(ctx: Context, code: Int, data: String) {
        sp(ctx).edit().putInt("proj_code", code).putString("proj_data", data).apply()
    }

    fun clearProjection(ctx: Context) {
        sp(ctx).edit().remove("proj_code").remove("proj_data").apply()
    }
}
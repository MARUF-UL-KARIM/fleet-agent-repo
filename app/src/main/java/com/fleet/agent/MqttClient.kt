package com.fleet.agent

import android.content.Context
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttClient(
    private val ctx: Context,
    private val onCommand: (JSONObject) -> Unit,
    private val onConnected: () -> Unit
) {
    private var client: org.eclipse.paho.client.mqttv3.MqttClient? = null
    val deviceId: String = Prefs.deviceId(ctx)
    private val name: String = Prefs.deviceName(ctx)
    private val cmdTopic  = "fleet/$deviceId/cmd"
    private val evtTopic  = "fleet/$deviceId/event"
    private val blobTopic = "fleet/$deviceId/blob"

    fun connect() {
        val host = Prefs.brokerHost(ctx)
        val c = org.eclipse.paho.client.mqttv3.MqttClient(host, deviceId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                try { c.subscribe(cmdTopic, 1) } catch (_: Throwable) {}
                publishEvent(JSONObject().put("kind", "hello").put("name", name))
                onConnected()
            }
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val p = message?.payload?.toString(Charsets.UTF_8) ?: return
                try { onCommand(JSONObject(p)) } catch (_: Throwable) {}
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        c.connect(opts)
        client = c
    }

    fun publishEvent(obj: JSONObject, qos: Int = 1): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return try {
            c.publish(evtTopic, MqttMessage(obj.toString().toByteArray()).apply { this.qos = qos })
            true
        } catch (_: Throwable) { false }
    }

    fun publishRaw(bytes: ByteArray, qos: Int = 0): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return try {
            c.publish(blobTopic, MqttMessage(bytes).apply { this.qos = qos })
            true
        } catch (_: Throwable) { false }
    }

    fun disconnect() { try { client?.disconnect() } catch (_: Throwable) {} }
}
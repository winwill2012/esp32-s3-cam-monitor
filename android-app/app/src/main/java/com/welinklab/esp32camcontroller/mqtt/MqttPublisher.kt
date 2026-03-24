package com.welinklab.esp32camcontroller.mqtt

import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

data class MqttConfig(
    val brokerUrl: String,
    val username: String,
    val password: String
)

class MqttPublisher(initialConfig: MqttConfig) {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var config: MqttConfig = initialConfig
    @Volatile
    private var client: MqttClient = createClient(initialConfig)

    fun connect() {
        ioExecutor.execute {
            try {
                if (!client.isConnected) {
                    client.connect(buildOptions(config))
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connect failed", e)
            }
        }
    }

    fun publish(topic: String, payload: String) {
        ioExecutor.execute {
            try {
                if (!client.isConnected) {
                    client.connect(buildOptions(config))
                }
                val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 0
                    isRetained = false
                }
                client.publish(topic, message)
            } catch (e: Exception) {
                Log.e(TAG, "MQTT publish failed", e)
            }
        }
    }

    fun updateConfig(newConfig: MqttConfig) {
        ioExecutor.execute {
            try {
                if (client.isConnected) {
                    client.disconnect()
                }
                client.close()
            } catch (e: Exception) {
                Log.e(TAG, "Closing old MQTT client failed", e)
            }
            config = newConfig
            client = createClient(newConfig)
            try {
                client.connect(buildOptions(newConfig))
            } catch (e: Exception) {
                Log.e(TAG, "MQTT reconnect with new config failed", e)
            }
        }
    }

    fun disconnect() {
        ioExecutor.execute {
            try {
                if (client.isConnected) {
                    client.disconnect()
                }
                client.close()
            } catch (e: Exception) {
                Log.e(TAG, "MQTT disconnect failed", e)
            }
        }
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MqttPublisher"

        private fun createClient(config: MqttConfig): MqttClient {
            return MqttClient(config.brokerUrl, "android-esp32-${UUID.randomUUID()}", MemoryPersistence())
        }

        private fun buildOptions(config: MqttConfig): MqttConnectOptions {
            return MqttConnectOptions().apply {
                userName = config.username
                password = config.password.toCharArray()
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 20
                isAutomaticReconnect = true
            }
        }
    }
}

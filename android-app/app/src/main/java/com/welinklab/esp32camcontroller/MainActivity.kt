package com.welinklab.esp32camcontroller

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.view.View
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import com.welinklab.esp32camcontroller.mqtt.MqttConfig
import com.welinklab.esp32camcontroller.mqtt.MqttPublisher
import com.welinklab.esp32camcontroller.net.MjpegHttp
import com.welinklab.esp32camcontroller.net.StreamUrlUtils
import com.welinklab.esp32camcontroller.view.MjpegView
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val repeatIntervalMs = 180L
    private val settingsTestExecutor = Executors.newSingleThreadExecutor()

    private lateinit var mjpegView: MjpegView
    private lateinit var mqttPublisher: MqttPublisher
    private lateinit var prefs: android.content.SharedPreferences
    private var vibrator: Vibrator? = null

    private var mqttTopic: String = DEFAULT_TOPIC
    private var mjpegUrl: String = DEFAULT_MJPEG_URL
    private var activeRepeatRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        mjpegView = findViewById(R.id.mjpegView)
        vibrator = resolveVibrator()
        mqttTopic = prefs.getString(KEY_TOPIC, DEFAULT_TOPIC) ?: DEFAULT_TOPIC
        mjpegUrl = StreamUrlUtils.normalizeStreamUrl(
            prefs.getString(KEY_MJPEG_URL, DEFAULT_MJPEG_URL) ?: DEFAULT_MJPEG_URL
        )
        mqttPublisher = MqttPublisher(readMqttConfig()).apply { connect() }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            performHapticFeedback()
            showSettingsDialog()
        }
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            performHapticFeedback()
            val intent = Intent(this, FullscreenPlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, mjpegUrl)
            }
            startActivity(intent)
        }

        bindRepeatingButton(R.id.btnUp, "up")
        bindRepeatingButton(R.id.btnDown, "down")
        bindRepeatingButton(R.id.btnLeft, "left")
        bindRepeatingButton(R.id.btnRight, "right")
        bindSingleTapButton(R.id.btnReset, "reset")
    }

    override fun onStart() {
        super.onStart()
        mjpegView.startStream(mjpegUrl)
    }

    override fun onStop() {
        super.onStop()
        mjpegView.stopStream()
    }

    override fun onDestroy() {
        stopRepeating()
        mqttPublisher.disconnect()
        settingsTestExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindSingleTapButton(buttonId: Int, command: String) {
        findViewById<ImageButton>(buttonId).setOnClickListener {
            performHapticFeedback()
            mqttPublisher.publish(mqttTopic, command)
        }
    }

    private fun bindRepeatingButton(buttonId: Int, command: String) {
        findViewById<ImageButton>(buttonId).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    performHapticFeedback()
                    startRepeating(command)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopRepeating()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun startRepeating(command: String) {
        stopRepeating()
        activeRepeatRunnable = object : Runnable {
            override fun run() {
                mqttPublisher.publish(mqttTopic, command)
                uiHandler.postDelayed(this, repeatIntervalMs)
            }
        }.also {
            it.run()
        }
    }

    private fun stopRepeating() {
        activeRepeatRunnable?.let { uiHandler.removeCallbacks(it) }
        activeRepeatRunnable = null
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_mqtt_settings, null, false)
        val etBroker = view.findViewById<EditText>(R.id.etBroker)
        val etUsername = view.findViewById<EditText>(R.id.etUsername)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val etTopic = view.findViewById<EditText>(R.id.etTopic)
        val etMjpegUrl = view.findViewById<EditText>(R.id.etMjpegUrl)
        val ivStreamStatus = view.findViewById<ImageView>(R.id.ivStreamStatus)
        val ivMqttStatus = view.findViewById<ImageView>(R.id.ivMqttStatus)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnDialogTest)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnDialogSave)
        var lastPassedSignature: String? = null

        val current = readMqttConfig()
        etBroker.setText(current.brokerUrl)
        etUsername.setText(current.username)
        etPassword.setText(current.password)
        etTopic.setText(mqttTopic)
        etMjpegUrl.setText(mjpegUrl)
        setStatusIcon(ivStreamStatus, null, null, getString(R.string.stream_error_detail_title))
        setStatusIcon(ivMqttStatus, null, null, getString(R.string.mqtt_error_detail_title))

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val clearTestState = {
            lastPassedSignature = null
            setStatusIcon(ivStreamStatus, null, null, getString(R.string.stream_error_detail_title))
            setStatusIcon(ivMqttStatus, null, null, getString(R.string.mqtt_error_detail_title))
        }
        etMjpegUrl.doAfterTextChanged { clearTestState() }
        etBroker.doAfterTextChanged { clearTestState() }
        etUsername.doAfterTextChanged { clearTestState() }
        etPassword.doAfterTextChanged { clearTestState() }
        etTopic.doAfterTextChanged { clearTestState() }

        btnCancel.setOnClickListener {
            performHapticFeedback()
            dialog.dismiss()
        }

        btnTest.setOnClickListener {
            performHapticFeedback()
            val newConfig = MqttConfig(
                brokerUrl = etBroker.text.toString().trim(),
                username = etUsername.text.toString().trim(),
                password = etPassword.text.toString()
            )
            val newTopic = etTopic.text.toString().trim()
            val newMjpegUrl = StreamUrlUtils.normalizeStreamUrl(etMjpegUrl.text.toString().trim())
            if (newConfig.brokerUrl.isEmpty() || newConfig.username.isEmpty() || newTopic.isEmpty() || newMjpegUrl.isEmpty()) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.config_empty_error),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val signature = buildConfigSignature(newConfig, newTopic, newMjpegUrl)
            btnTest.isEnabled = false
            btnSave.isEnabled = false
            btnCancel.isEnabled = false
            Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.config_test_running),
                Snackbar.LENGTH_SHORT
            ).show()

            settingsTestExecutor.execute {
                val mjpegError = testMjpegConnection(newMjpegUrl)
                val mqttError = testMqttConnection(newConfig)
                runOnUiThread {
                    btnTest.isEnabled = true
                    btnSave.isEnabled = true
                    btnCancel.isEnabled = true

                    if (mjpegError == null && mqttError == null) {
                        lastPassedSignature = signature
                        setStatusIcon(ivStreamStatus, true, null, getString(R.string.stream_error_detail_title))
                        setStatusIcon(ivMqttStatus, true, null, getString(R.string.mqtt_error_detail_title))
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.config_test_passed),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        lastPassedSignature = null
                        setStatusIcon(
                            ivStreamStatus,
                            mjpegError == null,
                            mjpegError,
                            getString(R.string.stream_error_detail_title)
                        )
                        setStatusIcon(
                            ivMqttStatus,
                            mqttError == null,
                            mqttError,
                            getString(R.string.mqtt_error_detail_title)
                        )
                        val detail = buildString {
                            if (mjpegError != null) append("${getString(R.string.test_mjpeg_failed)}: $mjpegError")
                            if (mqttError != null) {
                                if (isNotEmpty()) append("；")
                                append("${getString(R.string.test_mqtt_failed)}: $mqttError")
                            }
                        }
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            detail,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            performHapticFeedback()
            val newConfig = MqttConfig(
                brokerUrl = etBroker.text.toString().trim(),
                username = etUsername.text.toString().trim(),
                password = etPassword.text.toString()
            )
            val newTopic = etTopic.text.toString().trim()
            val newMjpegUrl = StreamUrlUtils.normalizeStreamUrl(etMjpegUrl.text.toString().trim())
            if (newConfig.brokerUrl.isEmpty() || newConfig.username.isEmpty() || newTopic.isEmpty() || newMjpegUrl.isEmpty()) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.config_empty_error),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            saveAppConfig(newConfig, newTopic, newMjpegUrl)
            mqttTopic = newTopic
            mjpegUrl = newMjpegUrl
            mqttPublisher.updateConfig(newConfig)
            mjpegView.stopStream()
            mjpegView.startStream(mjpegUrl)
            dialog.dismiss()
            Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.config_saved_all),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        dialog.show()
    }

    private fun setStatusIcon(iconView: ImageView, success: Boolean?, detail: String?, detailTitle: String) {
        when (success) {
            null -> {
                iconView.visibility = View.GONE
                iconView.setOnClickListener(null)
            }

            true -> {
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(R.drawable.ic_status_check)
                iconView.setOnClickListener(null)
            }

            false -> {
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(R.drawable.ic_status_error)
                iconView.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle(detailTitle)
                        .setMessage(detail ?: "unknown")
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }
        }
    }

    private fun buildConfigSignature(config: MqttConfig, topic: String, streamUrl: String): String {
        return listOf(config.brokerUrl, config.username, config.password, topic, streamUrl).joinToString("|")
    }

    private fun testMjpegConnection(urlString: String): String? {
        val url = StreamUrlUtils.normalizeStreamUrl(urlString)
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Connection", "close")
                .header("Accept", "multipart/x-mixed-replace,image/jpeg,*/*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Esp32CamController/1.0")
                .build()
            MjpegHttp.probeClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code !in 200..399) {
                    return@use "HTTP $code"
                }
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val likelyMjpeg = contentType.contains("multipart")
                    || contentType.contains("mjpeg")
                    || contentType.contains("jpeg")
                    || contentType.contains("x-mixed-replace")

                val path = try {
                    URL(url).path?.lowercase().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val pathLooksStream =
                    path.contains("stream") || path.contains("mjpeg") || path.contains("capture")

                if (likelyMjpeg || pathLooksStream) {
                    return@use null
                }

                val stream = response.body?.byteStream() ?: return@use "no response stream"
                val hasBytes = canReadSomeBytes(stream)
                if (hasBytes) null else "stream has no data"
            }
        } catch (e: Exception) {
            val base = e.message ?: e.javaClass.simpleName
            if (e is SocketTimeoutException) {
                "$base（流媒体首帧较慢时可忽略测试，保存后直接在主页播放）"
            } else {
                base
            }
        }
    }

    private fun canReadSomeBytes(inputStream: InputStream): Boolean {
        return try {
            val buffer = ByteArray(64)
            val count = inputStream.read(buffer)
            count > 0
        } catch (_: Exception) {
            false
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun testMqttConnection(config: MqttConfig): String? {
        val testClient = try {
            MqttClient(config.brokerUrl, "android-test-${UUID.randomUUID()}", MemoryPersistence())
        } catch (e: Exception) {
            return e.message ?: "client init failed"
        }

        return try {
            val options = MqttConnectOptions().apply {
                userName = config.username
                password = config.password.toCharArray()
                isCleanSession = true
                connectionTimeout = 6
                keepAliveInterval = 20
                isAutomaticReconnect = false
            }
            testClient.connect(options)
            testClient.disconnect()
            testClient.close()
            null
        } catch (e: Exception) {
            try {
                if (testClient.isConnected) testClient.disconnect()
                testClient.close()
            } catch (_: Exception) {
            }
            e.message ?: "connect failed"
        }
    }

    private fun readMqttConfig(): MqttConfig {
        return MqttConfig(
            brokerUrl = prefs.getString(KEY_BROKER, DEFAULT_BROKER) ?: DEFAULT_BROKER,
            username = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME,
            password = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        )
    }

    private fun saveAppConfig(config: MqttConfig, topic: String, streamUrl: String) {
        prefs.edit()
            .putString(KEY_BROKER, config.brokerUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_TOPIC, topic)
            .putString(KEY_MJPEG_URL, streamUrl)
            .apply()
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun performHapticFeedback() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(28)
        }
    }

    companion object {
        private const val PREFS_NAME = "mqtt_settings"
        private const val KEY_BROKER = "broker"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOPIC = "topic"
        private const val KEY_MJPEG_URL = "mjpeg_url"

        private const val DEFAULT_BROKER = "tcp://iot.welinklab.com:1883"
        private const val DEFAULT_USERNAME = "esp32-cam"
        private const val DEFAULT_PASSWORD = "314159!@#$%"
        private const val DEFAULT_TOPIC = "/command"
        private const val DEFAULT_MJPEG_URL = "http://162.14.83.139:8080/mjpeg"
        const val EXTRA_STREAM_URL = "extra_stream_url"
    }
}


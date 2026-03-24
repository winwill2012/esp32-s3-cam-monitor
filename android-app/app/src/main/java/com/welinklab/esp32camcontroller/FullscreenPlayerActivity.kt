package com.welinklab.esp32camcontroller

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.welinklab.esp32camcontroller.view.MjpegView

class FullscreenPlayerActivity : AppCompatActivity() {

    private lateinit var mjpegView: MjpegView
    private var streamUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContentView(R.layout.activity_fullscreen_player)

        mjpegView = findViewById(R.id.mjpegViewFullscreen)
        streamUrl = intent.getStringExtra(MainActivity.EXTRA_STREAM_URL).orEmpty()

        findViewById<ImageButton>(R.id.btnExitFullscreen).setOnClickListener {
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onStart() {
        super.onStart()
        if (streamUrl.isNotBlank()) {
            mjpegView.startStream(streamUrl)
        }
    }

    override fun onStop() {
        super.onStop()
        mjpegView.stopStream()
    }
}

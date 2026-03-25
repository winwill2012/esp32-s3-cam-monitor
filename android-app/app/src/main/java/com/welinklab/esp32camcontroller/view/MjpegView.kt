package com.welinklab.esp32camcontroller.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatImageView
import com.welinklab.esp32camcontroller.net.MjpegHttp
import com.welinklab.esp32camcontroller.net.StreamUrlUtils
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Request

/**
 * MJPEG over HTTP: ESP32 CameraWebServer uses multipart/x-mixed-replace with Content-Length per part.
 * Using [AppCompatImageView] avoids SurfaceView timing (0-sized view / invalid surface) that drops every frame → black screen.
 */
class MjpegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val running = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameLock = Any()
    private var pendingBitmap: Bitmap? = null
    private val drawPending = AtomicBoolean(false)
    private var displayedBitmap: Bitmap? = null

    private val drawFrameRunnable: Runnable = object : Runnable {
        override fun run() {
            val bitmap = synchronized(frameLock) {
                val b = pendingBitmap
                pendingBitmap = null
                b
            } ?: run {
                drawPending.set(false)
                return
            }

            try {
                if (!running.get()) {
                    bitmap.recycle()
                    return
                }
                displayedBitmap?.recycle()
                displayedBitmap = bitmap
                setImageBitmap(bitmap)
            } finally {
                val hasMore: Boolean = synchronized(frameLock) { pendingBitmap != null }
                if (hasMore) {
                    mainHandler.post(this)
                } else {
                    drawPending.set(false)
                }
            }
        }
    }

    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
    }

    fun startStream(url: String) {
        if (running.getAndSet(true)) return
        val streamUrl = StreamUrlUtils.normalizeStreamUrl(url)
        streamExecutor.execute {
            while (running.get()) {
                try {
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) Esp32CamController/1.0")
                        .header("Connection", "keep-alive")
                        .header("Accept", "multipart/x-mixed-replace,image/jpeg,*/*")
                        .get()
                        .build()
                    val call = MjpegHttp.streamClient.newCall(request)
                    activeCall.set(call)
                    val response = call.execute()
                    if (!running.get()) {
                        response.close()
                        break
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "MJPEG HTTP ${response.code}")
                        response.close()
                        Thread.sleep(800)
                        continue
                    }
                    val body = response.body
                    if (body == null) {
                        response.close()
                        Thread.sleep(800)
                        continue
                    }
                    try {
                        BufferedInputStream(body.byteStream(), 32768).use { input ->
                            decodeMjpegStream(input)
                        }
                    } finally {
                        response.close()
                    }
                } catch (e: IOException) {
                    if (!running.get() || e.message?.contains("Canceled", ignoreCase = true) == true) {
                        break
                    }
                    Log.e(TAG, "MJPEG stream error", e)
                    Thread.sleep(800)
                } catch (e: Exception) {
                    Log.e(TAG, "MJPEG stream error", e)
                    Thread.sleep(800)
                } finally {
                    activeCall.set(null)
                }
            }
            activeCall.set(null)
        }
    }

    fun stopStream() {
        running.set(false)
        activeCall.getAndSet(null)?.cancel()
        mainHandler.post {
            synchronized(frameLock) {
                pendingBitmap?.recycle()
                pendingBitmap = null
            }
            displayedBitmap?.recycle()
            displayedBitmap = null
            setImageDrawable(null)
            drawPending.set(false)
        }
    }

    private fun decodeMjpegStream(input: BufferedInputStream) {
        input.mark(SNIFF_LEN)
        val sniff = ByteArray(SNIFF_LEN)
        val n = input.read(sniff)
        if (n <= 0) return
        input.reset()

        val sniffStr = String(sniff, 0, n, StandardCharsets.ISO_8859_1)
        val useMultipart = sniffStr.contains("Content-Type:", ignoreCase = true)
            || sniffStr.contains("Content-Length:", ignoreCase = true)
            || sniffStr.trimStart().startsWith("--")

        if (useMultipart) {
            decodeMultipartJpeg(input)
        } else {
            decodeJpegMarkers(input)
        }
    }

    private fun decodeMultipartJpeg(input: InputStream) {
        while (running.get()) {
            val headerOut = ByteArrayOutputStream()
            if (!readUntilDoubleCrlf(input, headerOut, MAX_HEADER_BYTES)) break

            val headers = String(headerOut.toByteArray(), StandardCharsets.ISO_8859_1)
            val cl = CONTENT_LENGTH_REGEX.find(headers)?.groupValues?.get(1)?.toIntOrNull()
            if (cl == null || cl <= 0 || cl > MAX_JPEG_PART_BYTES) {
                Log.w(TAG, "multipart: skip part (bad Content-Length), trying marker fallback")
                decodeJpegMarkers(input)
                break
            }

            val body = ByteArray(cl)
            if (!readFully(input, body)) break
            decodeAndEnqueue(body)
        }
    }

    private fun decodeJpegMarkers(input: InputStream) {
        val buffer = ByteArray(16384)
        val jpgBuffer = ByteArrayOutputStream()
        var previous = -1

        while (running.get()) {
            val read = input.read(buffer)
            if (read <= 0) break

            for (i in 0 until read) {
                val current = buffer[i].toInt() and 0xFF
                if (previous == 0xFF && current == 0xD8) {
                    jpgBuffer.reset()
                    jpgBuffer.write(0xFF)
                }

                if (jpgBuffer.size() > 0) {
                    jpgBuffer.write(current)
                    if (previous == 0xFF && current == 0xD9) {
                        val jpegBytes = jpgBuffer.toByteArray()
                        decodeAndEnqueue(jpegBytes)
                        jpgBuffer.reset()
                    }
                }
                previous = current
            }
        }
    }

    private fun decodeAndEnqueue(jpegBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bitmap != null) {
            enqueueFrame(bitmap)
        }
    }

    private fun enqueueFrame(bitmap: Bitmap) {
        synchronized(frameLock) {
            pendingBitmap?.recycle()
            pendingBitmap = bitmap
        }
        if (drawPending.compareAndSet(false, true)) {
            mainHandler.post(drawFrameRunnable)
        }
    }

    private fun readUntilDoubleCrlf(input: InputStream, out: ByteArrayOutputStream, maxBytes: Int): Boolean {
        var w0 = -1
        var w1 = -1
        var w2 = -1
        var w3 = -1
        var total = 0
        while (total < maxBytes && running.get()) {
            val x = input.read()
            if (x < 0) return false
            out.write(x)
            total++
            w0 = w1
            w1 = w2
            w2 = w3
            w3 = x
            if (total >= 4 && w0 == 0x0D && w1 == 0x0A && w2 == 0x0D && w3 == 0x0A) {
                return true
            }
        }
        return false
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var offset = 0
        while (offset < dst.size && running.get()) {
            val n = input.read(dst, offset, dst.size - offset)
            if (n < 0) return false
            offset += n
        }
        return offset == dst.size
    }

    companion object {
        private const val TAG = "MjpegView"
        private const val SNIFF_LEN = 4096
        private const val MAX_HEADER_BYTES = 65536
        private const val MAX_JPEG_PART_BYTES = 16 * 1024 * 1024
        private val CONTENT_LENGTH_REGEX = Regex("(?i)Content-Length:\\s*(\\d+)")
    }
}

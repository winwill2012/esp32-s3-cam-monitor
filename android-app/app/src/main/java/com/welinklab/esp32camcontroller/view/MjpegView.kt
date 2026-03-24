package com.welinklab.esp32camcontroller.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MjpegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val running = AtomicBoolean(false)
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    init {
        holder.addCallback(this)
    }

    fun startStream(url: String) {
        if (running.getAndSet(true)) return
        streamExecutor.execute {
            while (running.get()) {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("Connection", "Keep-Alive")
                    connection.doInput = true
                    connection.connect()
                    decodeMjpegStream(connection.inputStream)
                } catch (e: Exception) {
                    Log.e(TAG, "MJPEG stream error", e)
                    Thread.sleep(800)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    fun stopStream() {
        running.set(false)
    }

    private fun decodeMjpegStream(input: InputStream) {
        val buffer = ByteArray(4096)
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
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bitmap != null) {
                            drawBitmap(bitmap)
                        }
                        jpgBuffer.reset()
                    }
                }
                previous = current
            }
        }
    }

    private fun drawBitmap(bitmap: Bitmap) {
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val srcW = bitmap.width.toFloat()
            val srcH = bitmap.height.toFloat()
            val dstW = width.toFloat()
            val dstH = height.toFloat()
            val scale = minOf(dstW / srcW, dstH / srcH)
            val drawW = srcW * scale
            val drawH = srcH * scale
            val left = (dstW - drawW) / 2f
            val top = (dstH - drawH) / 2f
            val rect = android.graphics.RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bitmap, null, rect, null)
        } catch (e: Exception) {
            Log.e(TAG, "Draw frame failed", e)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    companion object {
        private const val TAG = "MjpegView"
    }
}

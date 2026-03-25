package com.welinklab.esp32camcontroller.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 对 IPv6 字面量 URL、长连接流的处理比 [java.net.HttpURLConnection] 在 Android 上更稳定。
 */
object MjpegHttp {

    /** 持续拉 MJPEG：不设读超时，避免帧间隔触发断开 */
    val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 设置里「测试」连接：有界读超时 */
    val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

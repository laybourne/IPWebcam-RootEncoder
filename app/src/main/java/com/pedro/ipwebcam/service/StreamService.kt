package com.pedro.ipwebcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.ipwebcam.MainActivity
import com.pedro.ipwebcam.utils.NetworkUtils
import com.pedro.ipwebcam.web.WebControlServer
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamService : Service() {

    private val binder = LocalBinder()
    var rtspServerCamera2: RtspServerCamera2? = null
    private var webServer: WebControlServer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var lastSnapshotBytes: ByteArray? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var textFilterRender: TextFilterRender? = null
    private var timestampRunnable: Runnable? = null

    private val connectChecker: ConnectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {}
        override fun onConnectionFailed(reason: String) {}
        override fun onDisconnect() {}
        override fun onAuthError() {}
        override fun onAuthSuccess() {}
        override fun onNewBitrate(bitrate: Long) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        startForeground(1001, createNotification("IP Webcam 服务已启动"))
    }

    /**
     * 启动 RTSP 与 Web 服务
     */
    fun startStreaming(
        openGlView: OpenGlView,
        rtspPort: Int = 8554,
        httpPort: Int = 8080,
        onStatusUpdate: ((String) -> Unit)? = null
    ) {
        try {
            // 1. 初始化 RootEncoder 的 Camera2 RTSP 服务器
            val camera = RtspServerCamera2(openGlView, connectChecker, rtspPort)
            rtspServerCamera2 = camera

            // 1080P, 30fps, 2.5Mbps, 关键帧间隔 2 秒
            camera.prepareVideo(1920, 1080, 30, 2500 * 1024, 2)
            // 44.1kHz, 128kbps AAC 音频
            camera.prepareAudio(128 * 1024, 44100, true)
            // 启动本地预览与 RTSP 推流服务
            camera.startPreview()
            camera.startStream()

            // 2. 启动嵌入式 Web 控制服务器
            webServer = WebControlServer(httpPort) { this }.apply {
                start()
            }

            // 3. 动态时间戳水印 (类似 IP Webcam 视频上的 HUD 水印)
            setupLiveWatermark()

            val ip = NetworkUtils.getLocalIpAddress()
            onStatusUpdate?.invoke("RTSP: rtsp://$ip:$rtspPort/live\nWeb: http://$ip:$httpPort")
        } catch (e: Exception) {
            e.printStackTrace()
            onStatusUpdate?.invoke("启动失败: ${e.localizedMessage}")
        }
    }

    fun stopStreaming() {
        stopWatermarkUpdates()
        rtspServerCamera2?.apply {
            if (isStreaming) stopStream()
            if (isOnPreview) stopPreview()
        }
        webServer?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun toggleFlashlight(): Boolean {
        val server = rtspServerCamera2 ?: return false
        if (server.isLanternEnabled) {
            server.disableLantern()
        } else {
            server.enableLantern()
        }
        return server.isLanternEnabled
    }

    fun switchCamera() {
        rtspServerCamera2?.switchCamera()
    }

    fun takeSnapshot(callback: (ByteArray?) -> Unit) {
        rtspServerCamera2?.glInterface?.takePhoto { bitmap ->
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                lastSnapshotBytes = bytes
                callback(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        } ?: callback(null)
    }

    private fun setupLiveWatermark() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val filter = TextFilterRender().apply {
            setScale(45f, 10f)
            setPosition(TranslateTo.BOTTOM_LEFT)
            setText("CAM 01 | " + dateFormat.format(Date()), 24f, Color.GREEN, Color.TRANSPARENT, null)
        }
        textFilterRender = filter
        rtspServerCamera2?.glInterface?.setFilter(filter)

        timestampRunnable = object : Runnable {
            override fun run() {
                val timeStr = "CAM 01 | " + dateFormat.format(Date())
                textFilterRender?.setText(timeStr, 24f, Color.GREEN, Color.TRANSPARENT, null)
                mainHandler.postDelayed(this, 1000)
            }
        }
        timestampRunnable?.let { mainHandler.postDelayed(it, 1000) }
    }

    private fun stopWatermarkUpdates() {
        timestampRunnable?.let { mainHandler.removeCallbacks(it) }
        rtspServerCamera2?.glInterface?.clearFilters()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPWebcam:WakeLock").apply {
            acquire(12 * 60 * 60 * 1000L /* 最长持锁 12 小时 */)
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IPWebcam:WifiLock").apply {
            acquire()
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "ip_webcam_stream_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "IP Webcam 核心推流通道",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 IP Webcam 后台持续稳定推流与 Web 监听"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("IP Webcam Pro (RootEncoder)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopStreaming()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

package com.pedro.ipwebcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.pedro.ipwebcam.service.StreamService
import com.pedro.ipwebcam.utils.NetworkUtils
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity() {

    private lateinit var openGlView: OpenGlView
    private lateinit var tvRtspUrl: TextView
    private lateinit var tvHttpUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleStream: Button
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnToggleTorch: Button

    private var streamService: StreamService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamService.LocalBinder
            streamService = binder.getService()
            isBound = true
            updateUiState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            streamService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkAndRequestPermissions()

        val serviceIntent = Intent(this, StreamService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun initViews() {
        openGlView = findViewById(R.id.openGlView)
        tvRtspUrl = findViewById(R.id.tvRtspUrl)
        tvHttpUrl = findViewById(R.id.tvHttpUrl)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleStream = findViewById(R.id.btnToggleStream)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnToggleTorch = findViewById(R.id.btnToggleTorch)

        val ip = NetworkUtils.getLocalIpAddress()
        tvRtspUrl.text = "RTSP: rtsp://$ip:8554/live"
        tvHttpUrl.text = "Web 控制台: http://$ip:8080"

        btnToggleStream.setOnClickListener {
            val service = streamService ?: return@setOnClickListener
            val rtsp = service.rtspServerCamera2

            if (rtsp != null && rtsp.isStreaming) {
                service.stopStreaming()
                btnToggleStream.text = getString(R.string.start_stream)
                tvStatus.text = getString(R.string.status_stopped)
                Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
            } else {
                service.startStreaming(openGlView, rtspPort = 8554, httpPort = 8080) { status ->
                    runOnUiThread { tvStatus.text = status }
                }
                btnToggleStream.text = getString(R.string.stop_stream)
                Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
            }
        }

        btnSwitchCamera.setOnClickListener {
            streamService?.switchCamera()
        }

        btnToggleTorch.setOnClickListener {
            val state = streamService?.toggleFlashlight() ?: false
            Toast.makeText(this, if (state) "闪光灯开启" else "闪光灯关闭", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiState() {
        val service = streamService ?: return
        val isRunning = service.rtspServerCamera2?.isStreaming == true
        btnToggleStream.text = if (isRunning) getString(R.string.stop_stream) else getString(R.string.start_stream)
        tvStatus.text = if (isRunning) getString(R.string.status_running) else getString(R.string.status_stopped)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}

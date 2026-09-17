package com.pedro.ipwebcam.web

import com.pedro.ipwebcam.service.StreamService
import com.pedro.ipwebcam.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class WebControlServer(
    port: Int,
    private val serviceProvider: () -> StreamService?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val service = serviceProvider()

        return when {
            // 1. 抓拍接口：返回 JPEG 图片流
            uri == "/shot.jpg" -> {
                if (service == null) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Service unavailable")
                }
                var bytes: ByteArray? = null
                val lock = Object()

                service.takeSnapshot { snapshot ->
                    bytes = snapshot
                    synchronized(lock) { lock.notify() }
                }

                synchronized(lock) {
                    try {
                        lock.wait(1200) // 等待 GPU 渲染帧导出，最多 1.2 秒
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }

                val finalBytes = bytes ?: service.lastSnapshotBytes
                if (finalBytes != null) {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        ByteArrayInputStream(finalBytes),
                        finalBytes.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        addHeader("Pragma", "no-cache")
                        addHeader("Expires", "0")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Snapshot error")
                }
            }

            // 2. 闪光灯控制 API
            uri == "/control/torch" -> {
                val state = service?.toggleFlashlight() ?: false
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"torch\": $state}")
            }

            // 3. 翻转摄像头 API
            uri == "/control/switch_camera" -> {
                service?.switchCamera()
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"ok\"}")
            }

            // 4. 设备与推流状态 JSON
            uri == "/status.json" -> {
                val rtsp = service?.rtspServerCamera2
                val isStreaming = rtsp?.isStreaming ?: false
                val clients = (rtsp?.streamClient as? com.pedro.rtspserver.util.RtspServerStreamClient)?.getNumClients() ?: 0
                val torch = rtsp?.isLanternEnabled ?: false
                val json = """{
                    "isStreaming": $isStreaming,
                    "clients": $clients,
                    "torch": $torch,
                    "ip": "${NetworkUtils.getLocalIpAddress()}",
                    "rtspPort": 8554
                }""".trimIndent()
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            // 5. 默认主页：全功能 Web 控制台
            else -> {
                val ip = NetworkUtils.getLocalIpAddress()
                val html = renderDashboardHtml(ip)
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
        }
    }

    private fun renderDashboardHtml(ip: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>IP Webcam Pro - RootEncoder</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #121212; color: #E0E0E0; display: flex; justify-content: center; padding: 20px; }
        .container { width: 100%; max-width: 680px; background: #1E1E1E; border-radius: 16px; padding: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.4); }
        h1 { font-size: 22px; font-weight: 700; color: #FFFFFF; display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
        .stream-card { position: relative; width: 100%; height: 380px; background: #000; border-radius: 12px; overflow: hidden; display: flex; align-items: center; justify-content: center; }
        #liveImg { max-width: 100%; max-height: 100%; object-fit: contain; }
        .badge { position: absolute; top: 12px; left: 12px; background: rgba(229,57,53,0.9); color: white; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; }
        .info-box { background: #252525; border-radius: 10px; padding: 14px; margin-top: 16px; font-size: 14px; }
        .info-item { display: flex; justify-content: space-between; margin-bottom: 8px; font-family: monospace; }
        .info-item:last-child { margin-bottom: 0; }
        .url-text { color: #4CAF50; font-weight: bold; }
        .actions { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 20px; }
        button { background: #2979FF; color: white; border: none; padding: 12px 16px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s; }
        button:hover { background: #1565C0; transform: translateY(-1px); }
        button:active { transform: translateY(1px); }
        .btn-outline { background: transparent; border: 1px solid #444; color: #FFF; }
        .btn-outline:hover { background: #333; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎥 IP Webcam 控制台 (RootEncoder)</h1>
        <div class="stream-card">
            <span class="badge">LIVE</span>
            <img id="liveImg" src="/shot.jpg" alt="实时画面">
        </div>
        <div class="info-box">
            <div class="info-item"><span>RTSP 拉流地址:</span> <span class="url-text">rtsp://$ip:8554/live</span></div>
            <div class="info-item"><span>抓拍抓图接口:</span> <span class="url-text">http://$ip:8080/shot.jpg</span></div>
            <div class="info-item"><span>状态查询接口:</span> <span class="url-text">http://$ip:8080/status.json</span></div>
        </div>
        <div class="actions">
            <button onclick="control('/control/torch')">💡 开关手电筒</button>
            <button class="btn-outline" onclick="control('/control/switch_camera')">🔄 翻转镜头</button>
            <button class="btn-outline" onclick="downloadSnapshot()">📸 保存截图</button>
        </div>
    </div>
    <script>
        function control(url) {
            fetch(url).then(r => r.json()).then(data => {
                console.log('Control res:', data);
            });
        }
        function downloadSnapshot() {
            window.open('/shot.jpg?t=' + Date.now(), '_blank');
        }
        // 自动轮询拉取截图刷新 Web 预览 (每 800ms)
        const img = document.getElementById('liveImg');
        setInterval(() => {
            const nextImg = new Image();
            nextImg.onload = () => { img.src = nextImg.src; };
            nextImg.src = '/shot.jpg?t=' + Date.now();
        }, 800);
    </script>
</body>
</html>
""".trimIndent()
}

# IPWebcam-RootEncoder

基于 **[RootEncoder](https://github.com/pedroSG94/RootEncoder)** 和 **[RTSP-Server](https://github.com/pedroSG94/RTSP-Server)** 重构的高性能、纯 Java/Kotlin 现代化 Android IP 摄像头方案，用于复刻经典 **IP Webcam**。

---

## 🌟 核心特性与对比优势

| 功能特性 | 原版 IP Webcam（经典版） | 本项目（RootEncoder 重构版） |
| :--- | :--- | :--- |
| **原生依赖** | 携带 FFmpeg、libonvif、libvideoserverjni 等 >30MB .so | **零复杂 C++ 依赖**，全库增量小于 3MB |
| **视频编码** | C++ 软/硬编混合，频繁 JNI 内存拷贝 | **Android 原生 MediaCodec 硬件加速**，低功耗、低发热 |
| **图形与水印** | CPU 离屏计算 | **OpenGL ES 零拷贝流水线**，动态文字/时间戳 HUD 水印 |
| **流协议** | MJPEG / HTTP / RTSP | **RTSP H.264 / AAC 硬件级低延迟推流** |
| **Web 控制台** | 内置 C++ 嵌入式服务器 | **NanoHTTPD 嵌入式 Web 控制台**，支持抓拍与设备控制 |
| **后台保活** | 屏幕外物理坐标悬浮窗 (SYSTEM_ALERT_WINDOW) | **标准 ForegroundService + WakeLock/WifiLock** |

---

## 📂 项目结构

```text
IPWebcam-RootEncoder/
├── app/
│   ├── src/main/
│   │   ├── java/com/pedro/ipwebcam/
│   │   │   ├── MainActivity.kt         // 交互主界面，摄像头预览与快捷操作
│   │   │   ├── service/StreamService.kt// 核心前台服务，管理 RTSP 与 Web 服务生命周期
│   │   │   ├── web/WebControlServer.kt // NanoHTTPD 嵌入式 Web 控制台与 REST 抓拍 API
│   │   │   └── utils/NetworkUtils.kt   // 局域网 IP 自动嗅探
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml// 现代化暗黑风格 UI 界面
│   │   │   └── values/{strings,colors,themes}.xml
│   │   └── AndroidManifest.xml         // 相机、麦克风、前台服务权限配置
│   └── build.gradle                    // 依赖配置 (RootEncoder + RTSP-Server + NanoHTTPD)
├── build.gradle                        // 顶层 Gradle 构建配置
├── settings.gradle                     // JitPack 仓库与模块配置
└── README.md
```

---

## 🚀 编译与运行

1. 打开 **Android Studio** (推荐 Hedgehog 2023.1 或更高版本)。
2. 选择 **File -> Open**，定位并打开 `C:\Users\Administrator\Desktop\Antigravity\IPWebcam-RootEncoder`。
3. 等待 Gradle 自动同步完成依赖下载。
4. 连接 Android 手机（需开启 USB 调试，系统版本 Android 5.0+ / API 21+），点击 **Run 'app'** 即可直接编译安装。

---

## 🌐 接口与使用指南

启动服务后，同一局域网下的电脑或手机即可通过以下方式访问：

### 1. Web 浏览器控制台
在浏览器中打开：
```text
http://<手机IP>:8080
```
* 查看实时抓拍预览（自动以 800ms 间隔轮询刷新）
* 远程开关手机手电筒
* 远程翻转前后置摄像头
* 远程抓拍高清单帧画面

### 2. RTSP 高清拉流 (VLC / PotPlayer / NVR / OBS)
在播放器中输入流地址：
```text
rtsp://<手机IP>:8554/live
```
* **编码格式**：H.264 (AVC) + AAC
* **分辨率**：1920x1080 @ 30fps
* **码率**：2500 kbps

### 3. REST API 接入 (可无缝对接 Home Assistant 等智能家居)
* **抓拍截图**：`GET http://<手机IP>:8080/shot.jpg` (直接返回 JPEG 图像数据)
* **开关手电筒**：`GET http://<手机IP>:8080/control/torch`
* **翻转镜头**：`GET http://<手机IP>:8080/control/switch_camera`
* **状态查询**：`GET http://<手机IP>:8080/status.json`

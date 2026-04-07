# esp32-s3-cam-monitor
基于 ESP32-S3-CAM 的远程监控项目（UDP 推流 + 本地/服务器代理转 HTTP MJPEG + Android 播放与 MQTT 控制）。

# 效果展示
![](https://github.com/winwill2012/esp32-s3-cam-monitor/blob/master/%E6%95%88%E6%9E%9C%E5%9B%BE.png)

# 说明
本项目为微联编程网ESP32视觉实战的视频课程代码，代码开源，想了解详细工作原理讲解的，可以去我网站看视频讲解，视频地址：[https://code.welinklab.com/#/details?id=30](https://code.welinklab.com/#/details?id=30)

## 目录说明

1. `android-app`
   - Android 客户端，负责播放 MJPEG 视频流，并通过 MQTT 下发云台/方向控制指令（上/下/左/右、复位等）。
2. `esp32-s3-camera`
   - ESP32-S3-CAM 固件：采集摄像头画面，并把 JPEG 帧切片后通过 UDP 发到代理端；同时内置 MQTT/舵机控制相关逻辑（取决于你工程里的 `mqtt.h` / `servo.h`）。
3. `esp32-s3-camera-proxy`
   - Python 代理服务：接收 ESP32 发来的 UDP JPEG 分片，重组并输出 HTTP MJPEG（`/mjpeg`）供 Android 播放；也提供 `snapshot.jpg`。

## 快速使用方法（推荐流程）

### 1. 启动代理服务（`esp32-s3-camera-proxy`）
在电脑/服务器上执行（建议使用 Python 3.9+）：

```bash
cd esp32-s3-camera-proxy
pip install pillow
python esp32-cam-proxy.py --udp-port 5000 --http-port 8080
```

默认配置说明：
- UDP 接收：`0.0.0.0:5000`
- HTTP 输出：`0.0.0.0:8080`
- MJPEG 地址：`http://<你的代理IP>:8080/mjpeg`

你可以先用浏览器验证：
- `http://<你的代理IP>:8080/`
- `http://<你的代理IP>:8080/mjpeg`

### 2. 烧录 ESP32 固件（`esp32-s3-camera`）
在 PlatformIO 中编译并上传 `esp32-s3-camera`。

注意：固件里会把 UDP 发到固定地址和端口（当前工程中为 `162.14.83.139:5000`）。如果你的代理 IP 不一样，需要修改：
- `esp32-s3-camera/src/main.cpp` 里 `udp.beginPacket("162.14.83.139", 5000)` 相关位置。

上传后，ESP32 会通过 WiFiManager 自动联网（第一次可能需要按提示完成 WiFi 配网）。

### 3. 安装/运行 Android App（`android-app`）
编译安装 `android-app`。

默认 MJPEG 地址已配置为：
- `http://162.14.83.139:8080/mjpeg`

如果你的代理 IP/端口不同：
- 进入 App 的设置页，把 `MJPEG 视频流地址` 改成你的代理地址。
- 同时确认 MQTT Broker / Topic 配置正确。

## 常见问题排查

1. Android 黑屏
   - 先确认代理端 MJPEG 是否正常：浏览器打开 `/mjpeg` 能看到画面。
   - 检查防火墙：需要放通 UDP `5000`（ESP32 -> 代理）和 TCP `8080`（手机 -> 代理）。
2. 画面间断/抖动
   - 可尝试代理启动参数（例如不做解码校验、或重编码），以适配你的网络质量：
     - `--no-decode-check`
     - `--reencode`

## APK 安装说明（快捷）

如果你不想在 Android Studio 里重新编译，仓库中已经提供编译好的安装包：

- APK 路径：`android-app/app-release.apk`

你可以直接双击该 APK 安装到手机上（或通过 `adb install` 安装）。


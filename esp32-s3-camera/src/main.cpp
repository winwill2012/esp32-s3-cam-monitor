#include <Arduino.h>
#include <esp_camera.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WiFiManager.h>

#include "mqtt.h"
#include "servo.h"

WiFiUDP udp;
#define MAX_UDP_LENGTH 1400

void setup() {
    Serial.begin(115200);
    static camera_config_t camera_example_config = {
        .pin_pwdn = -1,
        .pin_reset = -1,
        .pin_xclk = 15,
        .pin_sccb_sda = 4,
        .pin_sccb_scl = 5,
        .pin_d7 = 16,
        .pin_d6 = 17,
        .pin_d5 = 18,
        .pin_d4 = 12,
        .pin_d3 = 10,
        .pin_d2 = 8,
        .pin_d1 = 9,
        .pin_d0 = 11,
        .pin_vsync = 6,
        .pin_href = 7,
        .pin_pclk = 13,
        .xclk_freq_hz = 20000000,
        .ledc_timer = LEDC_TIMER_0,
        .ledc_channel = LEDC_CHANNEL_0,

        .pixel_format = PIXFORMAT_JPEG,
        .frame_size = FRAMESIZE_SVGA,
        .jpeg_quality = 13,
        .fb_count = 2,
        .grab_mode = CAMERA_GRAB_LATEST
    };

    esp_err_t err = esp_camera_init(&camera_example_config);
    if (err != ESP_OK) {
        Serial.printf("摄像头初始化失败: %0X\n", err);
        return;
    }
    Serial.println("摄像头初始化成功");
    Serial.println("正在连接WiFI");
    WiFiManager wm;
    wm.autoConnect("ESP32摄像头");
    Serial.print("正在连接WiFi");
    while (!WiFi.isConnected()) {
        Serial.print(".");
        delay(1000);
    }
    Serial.print("连接WiFi成功, IP地址: ");
    Serial.println(WiFi.localIP());
    setupServo();
    setupMqtt();
}

void sendFrameViaUdp() {
    const auto frame = esp_camera_fb_get();
    const int total = frame->len; // 总的需要推流的字节数
    int sentBytes = 0; // 当前已经推流出去的字节数
    while (sentBytes < total) {
        const int len = min(MAX_UDP_LENGTH, total - sentBytes);
        // 这里的IP和端口，改成你自己的野火调试助手上显示的
        udp.beginPacket("162.14.83.139", 5000);
        udp.write(frame->buf + sentBytes, len);
        udp.endPacket();
        sentBytes += len;
        // 这里的值灵活调整，太小容易丢包，太大容易延迟或者卡顿
        delay(10);
    }
    esp_camera_fb_return(frame);
}

void loop() {
    sendFrameViaUdp();
}

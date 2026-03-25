#include "mqtt.h"
#include <PubSubClient.h>
#include <WiFiClient.h>
#include "servo.h"
WiFiClient espClient;
PubSubClient client(espClient);

void callback(const char *topic, const byte *payload, unsigned int length) {
    Serial.print("收到云端下发消息: [");
    Serial.print(topic);
    Serial.print("] ");
    char command[length + 1];
    for (int i = 0; i < length; i++) {
        command[i] = static_cast<char>(payload[i]);
    }
    command[length] = '\0';
    Serial.println(command);
    if (strcmp(command, "left") == 0) {
        increaseAngle(X);
    } else if (strcmp(command, "right") == 0) {
        decreaseAngle(X);
    } else if (strcmp(command, "up") == 0) {
        decreaseAngle(Y);
    } else if (strcmp(command, "down") == 0) {
        increaseAngle(Y);
    } else if (strcmp(command, "reset") == 0) {
        turnServo(X, 90);
        turnServo(Y, 75);
    }
}

void reconnect() {
    while (!client.connected()) {
        Serial.print("正在尝试连接到MQTT...");
        String clientId = "ESP32-S3-CAM-";
        clientId += String(random(0xffff), HEX);
        if (client.connect(clientId.c_str(), "esp32-cam", "314159!@#$%")) {
            Serial.println("连接成功");
            client.subscribe("/command");
        } else {
            Serial.print("连接失败: ");
            Serial.print(client.state());
            Serial.println(" 5秒之后再次重试");
            delay(5000);
        }
    }
}

void setupMqtt() {
    client.setServer("iot.welinklab.com", 1883);
    client.setCallback(callback);

    xTaskCreate([](void *ptr) {
        while (true) {
            if (!client.connected()) {
                reconnect();
            }
            client.loop();
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    }, "mqttLoop", 4096, nullptr, 1, nullptr);
}

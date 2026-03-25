#include "servo.h"
#include <Arduino.h>
#include <Preferences.h>
Preferences prefs;

int currentXServoAngle = 90;
int currentYServoAngle = 75;

void setupServo() {
    if (!prefs.begin(PREFERENCE_NAME)) {
        Serial.println("加载配置项失败");
        ESP.restart();
    }
    currentXServoAngle = prefs.getInt(PREFERENCE_X_ANGLE_KEY, 90);
    currentYServoAngle = prefs.getInt(PREFERENCE_Y_ANGLE_KEY, 90);

    prefs.begin("servo");
    ledcSetup(X_SERVO_CHANNEL, 50, 12);
    ledcAttachPin(X_SERVO_PIN, X_SERVO_CHANNEL);

    ledcSetup(Y_SERVO_CHANNEL, 50, 12);
    ledcAttachPin(Y_SERVO_PIN, Y_SERVO_CHANNEL);
    turnServo(X, currentXServoAngle);
    turnServo(Y, currentYServoAngle);
}

int angleToDuration(const ServoType type, int angle) {
    if (type == X) {
        angle = constrain(angle, 0, 180);
    } else {
        angle = constrain(angle, 0, 90);
    }
    // 通过角度映射到高电平持续时间（单位是us）
    return map(angle, 0, 180, 500, 2500);
}

void increaseAngle(const ServoType type) {
    if (type == X) {
        if (currentXServoAngle >= 180) {
            currentXServoAngle = 180;
            return;
        }
        currentXServoAngle = min(180, currentXServoAngle + 1);
        turnServo(type, currentXServoAngle);
    } else {
        if (currentYServoAngle >= 90) {
            currentYServoAngle = 90;
            return;
        }
        currentYServoAngle = min(90, currentYServoAngle + 1);
        turnServo(type, currentYServoAngle);
    }
}

void decreaseAngle(const ServoType type) {
    if (type == X) {
        if (currentXServoAngle <= 0) {
            currentXServoAngle = 0;
            return;
        }
        currentXServoAngle = max(0, currentXServoAngle - 1);
        turnServo(type, currentXServoAngle);
    } else {
        if (currentYServoAngle <= 0) {
            currentYServoAngle = 0;
            return;
        }
        currentYServoAngle = max(0, currentYServoAngle - 1);
        turnServo(type, currentYServoAngle);
    }
}

void turnServo(const ServoType type, const int angle) {
    Serial.printf("控制舵机: %d, %d\n", type, angle);
    ledcWrite(type == X ? X_SERVO_CHANNEL : Y_SERVO_CHANNEL, angleToDuration(type, angle) * 4095 / 20000.0);
    neopixelWrite(48, 0, 255, 0);
    vTaskDelay(200);
    neopixelWrite(48, 0, 0, 0);
    if (type == X) {
        currentXServoAngle = angle;
        prefs.putInt(PREFERENCE_X_ANGLE_KEY, currentXServoAngle);
    } else if (type == Y) {
        currentYServoAngle = angle;
        prefs.putInt(PREFERENCE_Y_ANGLE_KEY, currentYServoAngle);
    }
}

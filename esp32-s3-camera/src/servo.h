#ifndef L3_CAMERA_UDP_LAN_SERVO_H
#define L3_CAMERA_UDP_LAN_SERVO_H

#define X_SERVO_PIN 42
#define X_SERVO_CHANNEL 0

#define Y_SERVO_PIN 2
#define Y_SERVO_CHANNEL 1

#define PREFERENCE_NAME "ServoSettings"
#define PREFERENCE_X_ANGLE_KEY "xAngle"
#define PREFERENCE_Y_ANGLE_KEY "yAngle"

enum ServoType {
    X,
    Y
};

void setupServo();

int angleToDuration(ServoType type, int angle);

void increaseAngle(ServoType type);

void decreaseAngle(ServoType type);

void turnServo(ServoType type, int angle);

#endif //L3_CAMERA_UDP_LAN_SERVO_H

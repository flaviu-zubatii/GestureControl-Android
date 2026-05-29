# Gesture Control Daemon (Android)

An advanced, battery-optimized Android background service that leverages raw hardware sensors and Digital Signal Processing (DSP) to control device features completely hands-free. Built specifically with Android 14+ strict background execution limits in mind.

## 🚀 Key Features

*   **Magic Wave (Light Sensor):** Pass your hand over the screen to skip media tracks. Uses an exponential moving average to auto-calibrate to ambient lighting conditions.
*   **Hover & Hold (Light Sensor):** Hold your hand over the sensor for 1.5s to Play/Pause media.
*   **Smart Back-Tap (Linear Accelerometer):** Double-tap the back of the phone to toggle the Flashlight or take a Global Screenshot. Uses a 15-sample sliding window and statistical variance DSP to differentiate between intentional taps and normal walking motion.
*   **Flip to Shush (Gravity Sensor):** Turn the phone face down to instantly switch the ringer to Vibrate or enable Do Not Disturb.

## 🛠️ Technical Architecture

*   **Foreground Service & Smart WakeLocks:** Ensures the gesture engine survives aggressive OEM battery management (like Samsung's App Killer) while minimizing drain.
*   **Hardware Decoupling:** Automatically unregisters the Light Sensor via an `ACTION_SCREEN_OFF` BroadcastReceiver to save battery when the phone is pocketed, keeping only low-power motion sensors active.
*   **Accessibility API Integration:** Utilizes `AccessibilityService` constrained with minimum privileges (`canPerformGestures="false"`) strictly to dispatch `GLOBAL_ACTION_TAKE_SCREENSHOT` safely.
*   **Native Media Control:** Implements `NotificationListenerService` and `MediaSessionManager` to route universal transport controls (Play/Pause/Next/Prev) directly to the active media player (Spotify, YouTube, etc.) via IPC.
*   **Developer Terminal (Easter Egg):** 7 consecutive taps on the UI header unlocks a live diagnostic terminal displaying raw sensor `lux`, `m/s²`, and DSP variance streams at 5Hz.

## 📋 Requirements
*   Android 8.0 (API 26) Minimum / Optimized for Android 14 (API 34)
*   Permissions Required: `FOREGROUND_SERVICE_SPECIAL_USE`, `BIND_ACCESSIBILITY_SERVICE`, `NOTIFICATION_ACCESS`.

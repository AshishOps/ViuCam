# OpenWebCam 📹

An open-source, high-definition Android webcam streaming app designed to stream your phone's camera feed directly to **OBS Studio**, **VLC**, or any web browser over **Wi-Fi** or **USB**.

- **No subscriptions & no ads**: 100% free and open-source.
- **Full HD Support**: Switch between **1080p (FHD)**, **720p (HD)**, and **480p (SD)**.
- **OBS Studio Native Support**: Ingest via standard MJPEG over HTTP — no proprietary drivers or watermark required.
- **Web Control Panel**: Open `http://<phone-ip>:8080/` on your computer to view the live preview, toggle torch, switch cameras, or take snapshots.
- **Power Saver (Dim Screen)**: Dims your phone's screen to 1% brightness to prevent screen burn-in and phone overheating while keeping the webcam active.
- **USB Cable Support**: Stream over USB via standard ADB port forwarding for zero Wi-Fi jitter.

---

## 🚀 Connecting to OBS Studio

### Option 1: Browser Source (Quickest & Easiest)
1. Open **OBS Studio**.
2. In the **Sources** dock, click **`+`** ➔ **Browser**.
3. Name it `Phone Webcam`.
4. In the URL field, enter the URL displayed on your phone's screen:
   ```
   http://<YOUR-PHONE-IP>:8080/video
   ```
5. Set Width to `1920` and Height to `1080` (or `1280` x `720`).
6. Click **OK**. Your phone's camera feed is now live in OBS!

---

### Option 2: Media Source (FFmpeg / VLC Engine)
1. In OBS Studio, click **`+`** ➔ **Media Source**.
2. **Uncheck** "Local File".
3. In **Input**, enter:
   ```
   http://<YOUR-PHONE-IP>:8080/video
   ```
4. Set **Network Buffering** to `1 MB` for minimal latency.
5. Click **OK**.

---

### Option 3: Zero-Latency USB Connection (No Wi-Fi Needed)
If you want completely latency-free streaming without Wi-Fi dropouts:
1. Connect your phone to your PC via a USB cable.
2. Enable **USB Debugging** on your phone (in Developer Options).
3. Open a terminal (PowerShell or Command Prompt) on your PC and run:
   ```powershell
   adb forward tcp:8080 tcp:8080
   ```
4. In OBS Studio or your browser, connect to:
   ```
   http://localhost:8080/video
   ```

---

## 🌐 Web Control Panel
Open any browser on your computer connected to the same Wi-Fi and navigate to:
```
http://<YOUR-PHONE-IP>:8080/
```
From this dashboard, you can:
- View live video in your browser
- Remotely toggle your phone's flashlight/torch
- Remotely switch between front and rear cameras
- Grab a full-resolution snapshot

---

## 🛠️ How to Build & Install

### Method A: With Android Studio (Recommended)
1. Launch **Android Studio**.
2. Click **File** ➔ **Open...** and select the `OpenWebCam` directory (`C:\Users\ashis\Downloads\Droidcam\OpenWebCam`).
3. Connect your Android phone via USB (with USB debugging enabled) or start an emulator.
4. Click the green **Run (▶)** button in Android Studio.

### Method B: Build APK via Command Line
If you have Gradle or Android Studio's SDK configured:
```powershell
cd C:\Users\ashis\Downloads\Droidcam\OpenWebCam
# If using gradle wrapper in project root:
.\gradlew assembleDebug
```
The compiled APK will be generated at:
```
app\build\outputs\apk\debug\app-debug.apk
```
Install it onto your connected phone:
```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

## 📋 Permissions Explained
- `CAMERA`: Required to capture video frames for streaming.
- `INTERNET`: Required to run the local HTTP/MJPEG streaming server.
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`: Used to automatically detect and display your phone's Wi-Fi IP address.
- `WAKE_LOCK`: Keeps the phone processor awake during live broadcasts.
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CAMERA`: Ensures streaming does not terminate when the screen is dimmed or minimized.

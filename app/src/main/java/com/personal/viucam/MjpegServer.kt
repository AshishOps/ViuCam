package com.personal.viucam

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency embedded HTTP server for streaming MJPEG video.
 * Uses per-client conflated queues (latest-frame-only) and TCP_NODELAY
 * to prevent Wi-Fi bufferbloat and frame lag in OBS Studio and browsers.
 */
class MjpegServer(
    val port: Int = 8080,
    private val onTorchToggle: (() -> Boolean)? = null,
    private val onCameraSwitch: (() -> String)? = null
) {
    private val TAG = "MjpegServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<StreamClient>()

    @Volatile
    private var latestFrame: ByteArray? = null

    /**
     * Dedicated non-blocking sender per connected /video client.
     * Always drops stale intermediate frames so Wi-Fi hiccups never cause lag buildup.
     */
    private inner class StreamClient(
        private val socket: Socket,
        private val outputStream: OutputStream
    ) {
        private val lock = Object()
        private var pendingFrame: ByteArray? = null
        private val active = AtomicBoolean(true)
        private val reusableBuffer = ByteArrayOutputStream(524288)

        private val senderThread = Thread {
            try {
                while (active.get() && isRunning.get()) {
                    val frame: ByteArray?
                    synchronized(lock) {
                        while (pendingFrame == null && active.get() && isRunning.get()) {
                            lock.wait(1000)
                        }
                        frame = pendingFrame
                        pendingFrame = null
                    }

                    if (frame != null) {
                        reusableBuffer.reset()
                        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        reusableBuffer.write(header.toByteArray(Charsets.US_ASCII))
                        reusableBuffer.write(frame)
                        reusableBuffer.write("\r\n".toByteArray(Charsets.US_ASCII))

                        outputStream.write(reusableBuffer.toByteArray())
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected or write timed out
            } finally {
                close()
            }
        }

        init {
            senderThread.name = "MjpegClientSender-${socket.inetAddress?.hostAddress}"
            senderThread.start()
        }

        fun offerFrame(jpegBytes: ByteArray) {
            if (!active.get()) return
            synchronized(lock) {
                pendingFrame = jpegBytes
                lock.notifyAll()
            }
        }

        fun close() {
            if (!active.compareAndSet(true, false)) return
            clients.remove(this)
            synchronized(lock) {
                lock.notifyAll()
            }
            try {
                outputStream.close()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        Thread {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "MjpegServer started on port $port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Thread { handleClient(clientSocket) }.start()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting client connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        for (client in clients) {
            client.close()
        }
        clients.clear()
        Log.i(TAG, "MjpegServer stopped")
    }

    /**
     * Non-blocking broadcast: immediately hands the latest JPEG frame to each client's
     * conflated slot without stalling the camera thread.
     */
    fun broadcastFrame(jpegBytes: ByteArray) {
        latestFrame = jpegBytes
        if (clients.isEmpty()) return

        for (client in clients) {
            client.offerFrame(jpegBytes)
        }
    }

    fun getConnectedClientCount(): Int = clients.size

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000 // 10s timeout for HTTP header read
            socket.tcpNoDelay = true // Disable Nagle's algorithm for instant frame delivery
            val inputStream = BufferedReader(InputStreamReader(socket.getInputStream()))
            val outputStream = socket.getOutputStream()

            val requestLine = inputStream.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Drain remaining HTTP headers
            while (true) {
                val headerLine = inputStream.readLine() ?: break
                if (headerLine.isEmpty()) break
            }

            when {
                path == "/video" || path == "/mjpeg" -> {
                    // Cap send buffer to 256 KB to support 4K frames while preventing multi-frame TCP bufferbloat
                    socket.sendBufferSize = 262144
                    socket.soTimeout = 5000 // Detect stalled/dead Wi-Fi clients quickly

                    val header = (
                        "HTTP/1.1 200 OK\r\n" +
                        "Server: ViuCam\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Expires: 0\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n\r\n"
                    )
                    outputStream.write(header.toByteArray(Charsets.US_ASCII))
                    outputStream.flush()

                    val client = StreamClient(socket, outputStream)
                    clients.add(client)
                    latestFrame?.let { client.offerFrame(it) }
                }

                path == "/snapshot" -> {
                    val frame = latestFrame
                    if (frame != null) {
                        val header = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Server: ViuCam\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n"
                        )
                        outputStream.write(header.toByteArray(Charsets.US_ASCII))
                        outputStream.write(frame)
                        outputStream.flush()
                    } else {
                        val notFound = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
                        outputStream.write(notFound.toByteArray(Charsets.US_ASCII))
                        outputStream.flush()
                    }
                    socket.close()
                }

                path == "/api/torch" && method == "POST" -> {
                    val newState = onTorchToggle?.invoke() ?: false
                    val json = "{\"success\": true, \"torchEnabled\": $newState}"
                    sendJsonResponse(outputStream, json)
                    socket.close()
                }

                path == "/api/camera" && method == "POST" -> {
                    val cameraName = onCameraSwitch?.invoke() ?: "unknown"
                    val json = "{\"success\": true, \"currentCamera\": \"$cameraName\"}"
                    sendJsonResponse(outputStream, json)
                    socket.close()
                }

                path == "/api/status" -> {
                    val json = "{\"running\": ${isRunning.get()}, \"clients\": ${clients.size}}"
                    sendJsonResponse(outputStream, json)
                    socket.close()
                }

                path == "/" || path == "/index.html" -> {
                    val html = getDashboardHtml()
                    val htmlBytes = html.toByteArray(Charsets.UTF_8)
                    val header = (
                        "HTTP/1.1 200 OK\r\n" +
                        "Server: ViuCam\r\n" +
                        "Content-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: ${htmlBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    )
                    outputStream.write(header.toByteArray(Charsets.US_ASCII))
                    outputStream.write(htmlBytes)
                    outputStream.flush()
                    socket.close()
                }

                else -> {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    outputStream.write(notFound.toByteArray(Charsets.US_ASCII))
                    outputStream.flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendJsonResponse(outputStream: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = (
            "HTTP/1.1 200 OK\r\n" +
            "Server: ViuCam\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        )
        outputStream.write(header.toByteArray(Charsets.US_ASCII))
        outputStream.write(bytes)
        outputStream.flush()
    }

    private fun getDashboardHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ViuCam Monitor</title>
                <style>
                    /* Hallmark studio monitor tokens */
                    :root {
                        --bg-chassis: #0a0b0d;
                        --surface-panel: #14161a;
                        --surface-control: #1c1f26;
                        --border-hairline: #272b34;
                        --accent: #2962ff;
                        --rec-red: #ff3b30;
                        --rec-red-soft: rgba(255, 59, 48, 0.15);
                        --text-primary: #f4f5f7;
                        --text-secondary: #8e95a2;
                        --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        --font-mono: ui-monospace, SFMono-Regular, Menlo, monospace;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    html, body { overflow-x: clip; background: var(--bg-chassis); color: var(--text-primary); font-family: var(--font-sans); }
                    body { display: flex; justify-content: center; min-height: 100vh; padding: 24px; }
                    .container { max-width: 920px; width: 100%; display: flex; flex-direction: column; gap: 16px; }
                    header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border-hairline); padding-bottom: 14px; }
                    h1 { font-size: 1.35rem; font-weight: 700; color: var(--text-primary); }
                    .badge { background: var(--rec-red-soft); color: var(--rec-red); padding: 4px 10px; border-radius: 6px; font-size: 0.75rem; font-weight: 700; }
                    .video-wrapper { background: #000; border: 1px solid var(--border-hairline); border-radius: 12px; overflow: hidden; aspect-ratio: 16 / 9; display: flex; justify-content: center; align-items: center; }
                    .video-wrapper img { width: 100%; height: 100%; object-fit: contain; }
                    .controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
                    button { background: var(--surface-control); border: 1px solid var(--border-hairline); color: var(--text-primary); padding: 12px 18px; border-radius: 8px; font-size: 0.9rem; font-weight: 600; cursor: pointer; transition: background-color 0.15s ease, border-color 0.15s ease; }
                    button:hover { background: var(--surface-panel); border-color: var(--accent); }
                    button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
                    button.primary { background: var(--accent); color: var(--text-primary); border-color: var(--accent); }
                    .card { background: var(--surface-panel); border-radius: 10px; padding: 16px; border: 1px solid var(--border-hairline); }
                    .card h2 { font-size: 1rem; margin-bottom: 8px; color: var(--text-primary); }
                    .code-block { background: var(--bg-chassis); border: 1px solid var(--border-hairline); padding: 10px 14px; border-radius: 6px; font-family: var(--font-mono); font-size: 0.85rem; color: var(--text-primary); word-break: break-all; margin-top: 6px; }
                    @media (prefers-reduced-motion: reduce) { button { transition: none; } }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>ViuCam Monitor</h1>
                        <span class="badge">LIVE</span>
                    </header>
                    <div class="video-wrapper">
                        <img src="/video" alt="ViuCam Live Stream" />
                    </div>
                    <div class="controls">
                        <button onclick="toggleTorch()">Toggle flashlight</button>
                        <button onclick="switchCamera()">Switch camera</button>
                        <button class="primary" onclick="window.open('/snapshot', '_blank')">Capture snapshot</button>
                    </div>
                    <div class="card">
                        <h2>OBS Studio setup</h2>
                        <p style="color: var(--text-secondary); font-size: 0.9rem;">Add a Browser Source or Media Source in OBS Studio with this stream URL:</p>
                        <div class="code-block" id="streamUrl"></div>
                    </div>
                </div>
                <script>
                    document.getElementById('streamUrl').textContent = window.location.origin + '/video';
                    async function toggleTorch() {
                        await fetch('/api/torch', { method: 'POST' });
                    }
                    async function switchCamera() {
                        await fetch('/api/camera', { method: 'POST' });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}

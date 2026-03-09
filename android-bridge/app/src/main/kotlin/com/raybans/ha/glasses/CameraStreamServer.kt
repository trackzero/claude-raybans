package com.raybans.ha.glasses

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "CameraStreamServer"
private const val BOUNDARY = "raybans_frame_boundary"
// Poll for a new snapshot at ~5 fps while MJPEG clients are connected.
private const val SNAPSHOT_INTERVAL_MS = 200L

/**
 * NanoHTTPD-based HTTP server that serves the glasses camera as an MJPEG stream.
 *
 * Endpoints:
 *   GET /stream   → multipart/x-mixed-replace MJPEG (for HA camera entity)
 *   GET /snapshot → single latest JPEG frame
 *
 * The server is started by [GlassesBridgeService] on a configurable [port]
 * (default 8080). The HA camera entity's MJPEG URL is:
 *   http://<phone-lan-ip>:<port>/stream
 *
 * Frame acquisition strategy:
 *   mwdat v0.4.0 exposes [StreamSession.capturePhoto] (suspend) for JPEG snapshots.
 *   We poll it at [SNAPSHOT_INTERVAL_MS] and cache the last frame.
 *   VideoFrame is H.265-encoded; re-encoding on device is deferred to a future update.
 *
 * Only works on the local LAN; Bluetooth bandwidth is insufficient for remote streaming.
 */
class CameraStreamServer(
    private val manager: MetaGlassesManager,
    private val scope: CoroutineScope,
    port: Int = 8080,
) : NanoHTTPD(port) {

    /** Latest JPEG frame — shared by /snapshot and all active MJPEG clients. */
    private val latestFrame = MutableStateFlow<ByteArray?>(null)
    private var snapshotJob: Job? = null

    /**
     * Start polling for JPEG snapshots. Called from [MetaGlassesManager.Listener.onConnected]
     * after a [com.meta.wearable.dat.camera.StreamSession] has been started.
     */
    fun startCollecting() {
        snapshotJob = scope.launch {
            while (true) {
                val jpeg = manager.captureJpegSnapshot()
                if (jpeg != null) latestFrame.value = jpeg
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Snapshot collection started")
    }

    fun stopCollecting() {
        snapshotJob?.cancel()
        snapshotJob = null
        latestFrame.value = null
        Log.i(TAG, "Snapshot collection stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/stream" -> serveMjpegStream()
            "/snapshot" -> serveSnapshot()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveSnapshot(): Response {
        val frame = latestFrame.value
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "No frame available"
            )
        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong()
        )
    }

    private fun serveMjpegStream(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 512 * 1024)

        scope.launch {
            try {
                // Emit each new frame as an MJPEG part
                latestFrame.collect { jpeg ->
                    jpeg ?: return@collect
                    val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                    pipedOut.write(header.toByteArray())
                    pipedOut.write(jpeg)
                    pipedOut.write("\r\n".toByteArray())
                    pipedOut.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "MJPEG stream ended: ${e.message}")
            } finally {
                pipedOut.close()
            }
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            pipedIn
        )
    }

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "MJPEG server started on port $listeningPort")
    }

    override fun stop() {
        super.stop()
        stopCollecting()
        Log.i(TAG, "MJPEG server stopped")
    }
}

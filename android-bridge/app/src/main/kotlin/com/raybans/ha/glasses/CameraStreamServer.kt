package com.raybans.ha.glasses

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "CameraStreamServer"
private const val BOUNDARY = "raybans_frame_boundary"

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
 * Only works on the local LAN; Bluetooth bandwidth is insufficient for
 * reliable remote streaming.
 */
class CameraStreamServer(
    private val manager: MetaGlassesManager,
    private val scope: CoroutineScope,
    port: Int = 8080,
) : NanoHTTPD(port) {

    /** Latest JPEG frame — used by /snapshot and new MJPEG stream connections. */
    private val latestFrame = MutableStateFlow<ByteArray?>(null)
    private var frameCollectJob: Job? = null

    fun startCollecting() {
        frameCollectJob = scope.launch {
            manager.getCameraFrameStream().collect { jpeg ->
                latestFrame.value = jpeg
            }
        }
    }

    fun stopCollecting() {
        frameCollectJob?.cancel()
        frameCollectJob = null
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/stream" -> serveMjpegStream()
            "/snapshot" -> serveSnapshot()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }
    }

    private fun serveSnapshot(): Response {
        val frame = latestFrame.value
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "No frame yet"
            )
        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong()
        )
    }

    private fun serveMjpegStream(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 512 * 1024)

        // Pump frames into the piped stream on a background coroutine
        scope.launch {
            try {
                manager.getCameraFrameStream().collect { jpeg ->
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

        val contentType = "multipart/x-mixed-replace; boundary=$BOUNDARY"
        return newChunkedResponse(Response.Status.OK, contentType, pipedIn)
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

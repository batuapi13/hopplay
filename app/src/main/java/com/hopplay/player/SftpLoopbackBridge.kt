package com.hopplay.player

import com.hopplay.fs.RemoteFs
import com.hopplay.fs.FsEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private 127.0.0.1 HTTP Range gateway, embedded in the app. LibVLC reads
 * localhost byte ranges; the actual file is fetched using HopPlay's existing
 * authenticated and TOFU-verified SSHJ SFTP session. No remote hosting,
 * passwords in URLs, or full-file downloads.
 */
class SftpLoopbackBridge(
    private val fs: RemoteFs,
    private val files: List<FsEntry>,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4)
    private val acceptor = Thread({
        while (running.get()) {
            try {
                val socket = server.accept()
                workers.execute { serve(socket) }
            } catch (_: Exception) {
                if (running.get()) log("Loopback accept failed")
            }
        }
    }, "hopplay-loopback").apply { isDaemon = true; start() }

    fun url(index: Int): String =
        "http://127.0.0.1:${server.localPort}/${token}/${index}"

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 25_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            try {
                val first = readLine(input) ?: return
                val request = first.split(' ')
                if (request.size < 2) return
                val method = request[0]
                val index = request[1].substringBefore('?')
                    .removePrefix("/${token}/").toIntOrNull()
                var range: String? = null
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                }
                if ((method != "GET" && method != "HEAD") ||
                    !request[1].startsWith("/${token}/") || index == null || index !in files.indices
                ) {
                    reply(output, 404, "Not Found", 0L, 0L, 0L, false)
                    return
                }
                fs.open(files[index].path).use { file ->
                    val size = file.size
                    if (size <= 0) {
                        reply(output, 200, "OK", 0L, 0L, 0L, false)
                        return
                    }
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')
                        ?.toLongOrNull() ?: 0L
                    val requestedEnd = range?.substringAfter('-', "")?.substringBefore(',')
                        ?.toLongOrNull() ?: (size - 1)
                    if (start >= size || start < 0 || requestedEnd < start) {
                        val headers = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n"
                        output.write(headers.toByteArray(Charsets.US_ASCII))
                        output.flush()
                        return
                    }
                    val end = requestedEnd.coerceAtMost(size - 1)
                    reply(output, if (range == null) 200 else 206,
                        if (range == null) "OK" else "Partial Content", start, end, size, range != null)
                    if (method == "HEAD") return
                    val buffer = ByteArray(64 * 1024)
                    var position = start
                    val began = System.nanoTime()
                    var slowReads = 0
                    while (position <= end && running.get()) {
                        val count = minOf(buffer.size.toLong(), end - position + 1).toInt()
                        val readStart = System.nanoTime()
                        val read = file.read(position, buffer, 0, count)
                        if (System.nanoTime() - readStart > 2_000_000_000L) slowReads++
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        position += read
                    }
                    output.flush()
                    log("SFTP VLC range served bytes=" + (position - start) +
                        " ms=" + (System.nanoTime() - began) / 1_000_000 + " slowReads=" + slowReads)
                }
            } catch (e: Exception) {
                log("SFTP VLC bridge error: " + e.javaClass.simpleName)
            }
        }
    }

    private fun reply(
        out: BufferedOutputStream, status: Int, description: String,
        start: Long, end: Long, size: Long, partial: Boolean
    ) {
        val length = if (size == 0L) 0L else end - start + 1
        val headers = buildString {
            append("HTTP/1.1 $status $description\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(256)
        while (bytes.size < 8192) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) break
            if (b != 13) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        workers.shutdownNow()
    }
}

package com.khodroyar.app.team

import android.app.Application
import com.khodroyar.app.CrashGuard
import com.khodroyar.app.data.Backup
import com.khodroyar.app.data.Db
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Manager-side LAN server. Employees POST their changes to /sync and receive
 * the authoritative snapshot back. Runs on port 8765 over the shared Wi-Fi.
 */
object SyncServer {

    const val PORT = 8765

    @Volatile
    var running = false
        private set

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** (timestamp, deviceName, summary) — newest first */
    val events = Collections.synchronizedList(ArrayList<Triple<Long, String, String>>())

    fun start(app: Application): Boolean {
        if (running) return true
        running = true
        thread = Thread({ loop(app) }, "manian-sync-server").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        thread = null
    }

    private fun loop(app: Application) {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            while (running) {
                val cl = try { ss.accept() } catch (e: Exception) { break }
                Thread({ handle(app, cl) }, "manian-sync-handler").apply { isDaemon = true }.start()
            }
        } catch (e: Throwable) {
            CrashGuard.log(e)
        } finally {
            running = false
        }
    }

    private fun handle(app: Application, cl: Socket) {
        try {
            cl.soTimeout = 60_000
            val ins = cl.getInputStream()
            val first = readLine(ins) ?: return
            val parts = first.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }
            var contentLength = 0
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0 && h.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = h.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                ByteArray(contentLength).also { buf ->
                    var off = 0
                    while (off < contentLength) {
                        val n = ins.read(buf, off, contentLength - off)
                        if (n < 0) break
                        off += n
                    }
                }
            } else ByteArray(0)

            if (path.startsWith("/ping")) {
                respond(cl, """{"ok":true,"app":"manian-khodro"}""".toByteArray())
                return
            }

            if (method == "POST" && path.startsWith("/sync")) {
                val db = Db.get(app)
                val text = String(body, Charsets.UTF_8)
                val incoming = Backup.fromJson(text)
                val incomingPhotos = Backup.photosFromJson(text)
                val device = try { JSONObject(text).optString("device") } catch (_: Exception) { "" }

                val result = Merge.applyFaults(db, incoming)

                val dir = File(app.filesDir, "photos").apply { mkdirs() }
                var photosAdded = 0
                for (p in incomingPhotos) {
                    val name = p.second.ifBlank { "p" + System.currentTimeMillis() + ".jpg" }
                    val f = File(dir, name)
                    if (!f.exists()) { f.writeBytes(p.third); photosAdded++ }
                    val exists = db.photosFor(p.first).any { it.path.endsWith("/" + name) }
                    if (!exists) db.addPhoto(p.first, f.absolutePath)
                }

                val allPhotos = db.allPhotos().mapNotNull { pr ->
                    try {
                        val f = File(pr.path)
                        Triple(pr.faultId, f.name,
                            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                    } catch (_: Exception) { null }
                }
                val root = JSONObject(Backup.toJson(db.allFaults(), allPhotos))
                val remap = JSONArray()
                result.remap.forEach { (from, to) ->
                    remap.put(JSONObject().put("from", from).put("to", to))
                }
                root.put("remap", remap)

                events.add(0, Triple(
                    System.currentTimeMillis(),
                    device.ifBlank { "?" },
                    "+${result.added}  ~${result.updated}  📷$photosAdded"
                ))
                if (events.size > 60) events.subList(60, events.size).clear()

                respond(cl, root.toString().toByteArray(Charsets.UTF_8))
            } else {
                respond(cl, """{"error":"not found"}""".toByteArray(), 404)
            }
        } catch (e: Throwable) {
            CrashGuard.log(e)
            try { respond(cl, """{"error":"exception"}""".toByteArray(), 500) } catch (_: Throwable) {}
        } finally {
            try { cl.close() } catch (_: Throwable) {}
        }
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun respond(cl: Socket, bytes: ByteArray, code: Int = 200) {
        val head = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        cl.getOutputStream().use { os ->
            os.write(head.toByteArray(Charsets.US_ASCII))
            os.write(bytes)
            os.flush()
        }
    }

    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val n = en.nextElement()
                if (!n.isUp) continue
                for (a in n.inetAddresses) {
                    if (!a.isLoopbackAddress && a is Inet4Address) a.hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {}
        return out.filter { it.isNotBlank() }
    }
}

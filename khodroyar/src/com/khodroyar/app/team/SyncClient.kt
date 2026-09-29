package com.khodroyar.app.team

import android.content.Context
import com.khodroyar.app.data.Backup
import com.khodroyar.app.data.Db
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Employee-side sync: pushes local changes to the manager and merges the
 * authoritative snapshot back (last-write-wins).
 */
object SyncClient {

    class Outcome(val sentFaults: Int, val gotFaults: Int, val gotPhotos: Int)

    fun sync(ctx: Context, ip: String, device: String, lastSync: Long): Outcome {
        val db = Db.get(ctx)

        val changed = db.allFaults().filter { it.updatedAt > lastSync }
        val dir = File(ctx.filesDir, "photos")
        val photos = db.allPhotos().mapNotNull { pr ->
            val f = File(pr.path)
            if (!f.exists()) return@mapNotNull null
            if (f.lastModified() > lastSync || changed.any { it.id == pr.faultId }) {
                try {
                    Triple(pr.faultId, f.name,
                        android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                } catch (_: Exception) { null }
            } else null
        }

        val root = JSONObject(Backup.toJson(changed, photos))
            .put("device", device)
            .put("since", lastSync)
        val payload = root.toString().toByteArray(Charsets.UTF_8)

        val conn = URL("http://$ip:${SyncServer.PORT}/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")

            val resp = JSONObject(text)

            // server reassigned colliding ids -> drop our stale local copies
            val remapArr = resp.optJSONArray("remap")
            if (remapArr != null) {
                for (i in 0 until remapArr.length()) {
                    val o = remapArr.getJSONObject(i)
                    val from = o.getLong("from"); val to = o.getLong("to")
                    if (db.getFault(from) != null && db.getFault(to) != null) db.deleteFault(from)
                }
            }

            // merge the authoritative snapshot (LWW)
            val snapshot = Backup.fromJson(text)
            Merge.applyFaults(db, snapshot)

            // download photos we do not have yet
            val photoDir = File(ctx.filesDir, "photos").apply { mkdirs() }
            var gotPhotos = 0
            for (p in Backup.photosFromJson(text)) {
                val f = File(photoDir, p.second)
                if (!f.exists() && p.third.isNotEmpty()) { f.writeBytes(p.third); gotPhotos++ }
                val exists = db.photosFor(p.first).any { it.path.endsWith("/" + p.second) }
                if (!exists) db.addPhoto(p.first, f.absolutePath)
            }

            return Outcome(changed.size, snapshot.size, gotPhotos)
        } finally {
            conn.disconnect()
        }
    }
}

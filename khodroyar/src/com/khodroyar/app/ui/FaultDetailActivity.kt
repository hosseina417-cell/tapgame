package com.khodroyar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.CrashGuard
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.DbExec
import com.khodroyar.app.data.Fault
import com.khodroyar.app.data.Severity
import com.khodroyar.app.data.Status
import com.khodroyar.app.util.Jalali
import java.io.File
import java.io.FileOutputStream

class FaultDetailActivity : Activity() {

    private lateinit var db: Db
    private var id: Long = -1
    private var fault: Fault? = null
    private var pendingCamUri: Uri? = null

    private val stLabels = intArrayOf(R.string.st_new, R.string.st_checking, R.string.st_repairing, R.string.st_fixed)
    private val stColors = intArrayOf(R.color.stNew, R.color.stChecking, R.color.stRepairing, R.color.stFixed)
    private val sevColors = intArrayOf(R.color.sevLow, R.color.sevMedium, R.color.sevHigh, R.color.sevCritical)

    private val REQ_CAM = 31
    private val REQ_GAL = 32

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        db = Db.get(this)
        id = intent.getLongExtra("id", -1)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnEdit).setOnClickListener {
            val f = fault
            if (f != null) startActivity(
                Intent(this, FaultEditActivity::class.java).putExtra("id", f.id)
            )
        }
        findViewById<TextView>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        findViewById<TextView>(R.id.btnShare).setOnClickListener { share() }
        findViewById<TextView>(R.id.btnCam).setOnClickListener { takePhoto() }
        findViewById<TextView>(R.id.btnGallery).setOnClickListener { pickPhoto() }

        val row = findViewById<LinearLayout>(R.id.rowQuickStatus)
        val labels = listOf(
            getString(R.string.st_new), getString(R.string.st_checking),
            getString(R.string.st_repairing), getString(R.string.st_fixed)
        )
        row.post {
            ChipGroup.build(this, row, labels, fault?.status ?: 0) { newSt -> setStatus(newSt) }
        }
    }

    override fun onResume() {
        super.onResume()
        fault = db.getFault(id)
        if (fault == null) {
            Toast.makeText(this, R.string.err_not_found, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        render()
        loadPhotos()
    }

    private fun render() {
        val f = fault!!
        val dot = findViewById<View>(R.id.dotSeverity)
        dot.background.mutate().setTint(resources.getColor(sevColors[f.severity.coerceIn(0, 3)]))
        findViewById<TextView>(R.id.txtDetailTitle).text = f.title

        val st = findViewById<TextView>(R.id.txtDetailStatus)
        st.text = getString(stLabels[f.status.coerceIn(0, 3)])
        st.background.mutate().setTint(resources.getColor(stColors[f.status.coerceIn(0, 3)]))
        st.setTextColor(android.graphics.Color.WHITE)

        val obd = findViewById<TextView>(R.id.txtDetailObd)
        obd.visibility = if (f.obdCode.isBlank()) View.GONE else View.VISIBLE
        obd.text = "OBD: " + f.obdCode
        obd.setOnLongClickListener {
            copyToClipboard(f.obdCode, "OBD")
            true
        }

        val vin = findViewById<TextView>(R.id.txtDetailVin)
        vin.visibility = if (f.vin.isBlank()) View.GONE else View.VISIBLE
        vin.text = "VIN: " + f.vin
        vin.setOnClickListener { copyToClipboard(f.vin, "VIN") }
        vin.setOnLongClickListener {
            copyToClipboard(f.vin, "VIN")
            true
        }

        findViewById<TextView>(R.id.txtDetailMeta).text = buildString {
            if (f.carName.isNotBlank()) append("🚗 " + f.carName + "\n")
            append("🗓 " + Jalali.formatFull(f.createdAt))
            if (f.fixedAt != null) append("\n✓ تعمیر: " + Jalali.formatShort(f.fixedAt!!))
        }

        setOrHide(R.id.txtDetailSymptoms, f.symptoms)
        setOrHide(R.id.txtDetailDesc, f.description)
        setOrHide(R.id.txtDetailRepair, f.repairMethod)
        setOrHide(R.id.txtDetailTools, f.tools)
        setOrHide(R.id.txtDetailParts, f.parts)

        // refresh quick chips selection
        val row = findViewById<LinearLayout>(R.id.rowQuickStatus)
        if (row.childCount > 0) ChipGroup.setSelected(this, row, f.status)
    }

    // ================================================================= photos
    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun photoDir(): File = File(filesDir, "photos").apply { mkdirs() }

    private fun takePhoto() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "manian-" + System.currentTimeMillis() + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ManianKhodro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, R.string.photo_fail, Toast.LENGTH_SHORT).show(); return
            }
            pendingCamUri = uri
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(i, REQ_CAM)
        } catch (e: Exception) {
            pendingCamUri = null
            Toast.makeText(this, R.string.photo_no_cam, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickPhoto() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_GAL)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.photo_fail, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAM) dropPendingCam()
            return
        }
        when (requestCode) {
            REQ_CAM -> {
                val uri = pendingCamUri
                pendingCamUri = null
                if (uri == null) return
                DbExec.async(this, {
                    val path = importFromUri(uri)
                    dropPendingCam(uri)
                    path
                }, onDone = { p ->
                    if (p != null) {
                        val pid = id
                        DbExec.async(this, { db.addPhoto(pid, p) }, onDone = { loadPhotos() })
                    } else Toast.makeText(this, R.string.photo_fail, Toast.LENGTH_SHORT).show()
                })
            }
            REQ_GAL -> {
                val uri = data?.data ?: return
                DbExec.async(this, { importFromUri(uri) }, onDone = { p ->
                    if (p != null) {
                        val pid = id
                        DbExec.async(this, { db.addPhoto(pid, p) }, onDone = { loadPhotos() })
                    } else Toast.makeText(this, R.string.photo_fail, Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    /** removes the temporary MediaStore row created for camera output */
    private fun dropPendingCam(uri: Uri? = pendingCamUri) {
        val u = uri ?: return
        try { contentResolver.delete(u, null, null) } catch (e: Throwable) { CrashGuard.log(e) }
    }

    private fun importFromUri(uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null as android.graphics.Rect?, bounds) }?.recycle()
        if (bounds.outWidth <= 0) null
        else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null as android.graphics.Rect?, opts)
            } ?: return null
            val rotated = rotateByExif(bmp, uri)
            val f = File(photoDir(), id.toString() + "_" + System.currentTimeMillis() + ".jpg")
            FileOutputStream(f).use { rotated.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (rotated !== bmp) bmp.recycle()
            f.absolutePath
        }
    } catch (e: Throwable) {
        CrashGuard.log(e); null
    }

    private fun rotateByExif(b: Bitmap, uri: Uri): Bitmap = try {
        val ins = contentResolver.openInputStream(uri) ?: return b
        val ex = ExifInterface(ins)
        ins.close()
        val deg = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) b
        else {
            val m = Matrix().apply { postRotate(deg) }
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            r
        }
    } catch (e: Throwable) {
        CrashGuard.log(e); b
    }

    private fun decodeBmp(path: String, maxSide: Int): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var s = 1
        while (b.outWidth / (s * 2) >= maxSide || b.outHeight / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { CrashGuard.log(e); null }

    private fun loadPhotos() {
        val wrap = findViewById<LinearLayout>(R.id.photosWrap)
        val did = id
        DbExec.async(this, {
            val rows = db.photosFor(did)
            val thumbs = ArrayList<Pair<Db.PhotoRow, Bitmap?>>(rows.size)
            for (r in rows) thumbs.add(r to decodeBmp(r.path, 320))
            thumbs
        }, onDone = { thumbs ->
            wrap.removeAllViews()
            if (thumbs.isEmpty()) return@async
            var rowLayout: LinearLayout? = null
            thumbs.forEachIndexed { i, pair ->
                if (i % 3 == 0) {
                    rowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    wrap.addView(rowLayout)
                }
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFE4E9F0.toInt())
                    contentDescription = getString(R.string.photo_take)
                }
                pair.second?.let { iv.setImageBitmap(it) }
                iv.setOnClickListener { openFull(pair.first.path) }
                iv.setOnLongClickListener { confirmPhotoDelete(pair.first); true }
                rowLayout!!.addView(iv)
            }
        })
    }

    private fun openFull(path: String) {
        DbExec.async(this, { decodeBmp(path, 1400) }, onDone = { bmp ->
            if (bmp == null) {
                Toast.makeText(this, R.string.photo_fail, Toast.LENGTH_SHORT).show(); return@async
            }
            val iv = ImageView(this)
            iv.adjustViewBounds = true
            iv.setImageBitmap(bmp)
            val pad = dp(10)
            AlertDialog.Builder(this)
                .setView(iv)
                .setPositiveButton(android.R.string.ok, null)
                .show()
                .window?.setLayout(dp(340) + pad * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun confirmPhotoDelete(pr: Db.PhotoRow) {
        AlertDialogs.confirm(
            this, getString(R.string.photo_del_title), getString(R.string.photo_del_msg),
            getString(R.string.delete)
        ) {
            val pid = pr.id
            val p = pr.path
            DbExec.async(this, {
                db.deletePhoto(pid)
                File(p).delete()
            }, onDone = { loadPhotos() })
        }
    }

    // ================================================================= rest
    private fun copyToClipboard(text: String, label: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun setOrHide(viewId: Int, text: String) {
        val tv = findViewById<TextView>(viewId)
        if (text.isBlank()) {
            tv.text = getString(R.string.not_set)
            tv.setTextColor(resources.getColor(R.color.textSec))
        } else {
            tv.text = text
            tv.setTextColor(resources.getColor(R.color.textMain))
        }
    }

    private fun setStatus(newStatus: Int) {
        val f = fault ?: return
        f.status = newStatus
        f.updatedAt = System.currentTimeMillis()
        if (newStatus == Status.FIXED && f.fixedAt == null) f.fixedAt = f.updatedAt
        if (newStatus != Status.FIXED) f.fixedAt = null
        val snapshot = f.copy()
        DbExec.async(this, { db.updateFault(snapshot) }, onDone = {
            Toast.makeText(this, getString(R.string.changed_to, getString(stLabels[newStatus])), Toast.LENGTH_SHORT).show()
            render()
        })
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                DbExec.async(this, { db.deleteFault(id) }, onDone = { finish() })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun share() {
        val f = fault ?: return
        val sb = StringBuilder()
        sb.appendLine("🚗 گزارش خطای خودرو — خط ریورک مانیان خودرو")
        sb.appendLine("━━━━━━━━━━━━━━━━━━")
        sb.appendLine("📌 " + f.title)
        if (f.carName.isNotBlank()) sb.appendLine("🚘 خودرو: " + f.carName)
        if (f.vin.isNotBlank()) sb.appendLine("🔢 شماره VIN: " + f.vin)
        if (f.obdCode.isNotBlank()) sb.appendLine("🔧 کد OBD: " + f.obdCode)
        sb.appendLine("⚠️ سطح اهمیت: " + sevName(f.severity))
        sb.appendLine("📌 وضعیت: " + getString(stLabels[f.status.coerceIn(0, 3)]))
        sb.appendLine("🗓 تاریخ ثبت: " + Jalali.formatFull(f.createdAt))
        if (f.symptoms.isNotBlank()) sb.appendLine("\n▫️ علائم:\n" + f.symptoms)
        if (f.description.isNotBlank()) sb.appendLine("\n▫️ توضیحات:\n" + f.description)
        if (f.repairMethod.isNotBlank()) sb.appendLine("\n🛠 روش تعمیر:\n" + f.repairMethod)
        if (f.tools.isNotBlank()) sb.appendLine("\n🧰 ابزار لازم:\n" + f.tools)
        if (f.parts.isNotBlank()) sb.appendLine("\n⚙️ قطعات:\n" + f.parts)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString())
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun sevName(s: Int): String = when (s) {
        Severity.LOW -> getString(R.string.sev_low)
        Severity.HIGH -> getString(R.string.sev_high)
        Severity.CRITICAL -> getString(R.string.sev_critical)
        else -> getString(R.string.sev_medium)
    }
}

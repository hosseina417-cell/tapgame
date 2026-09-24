package com.khodroyar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.DbExec
import com.khodroyar.app.data.Fault
import com.khodroyar.app.data.Severity
import com.khodroyar.app.data.Status
import com.khodroyar.app.util.Jalali

class FaultDetailActivity : Activity() {

    private lateinit var db: Db
    private var id: Long = -1
    private var fault: Fault? = null

    private val stLabels = intArrayOf(R.string.st_new, R.string.st_checking, R.string.st_repairing, R.string.st_fixed)
    private val stColors = intArrayOf(R.color.stNew, R.color.stChecking, R.color.stRepairing, R.color.stFixed)
    private val sevColors = intArrayOf(R.color.sevLow, R.color.sevMedium, R.color.sevHigh, R.color.sevCritical)

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
        DbExec.async(this, { db.updateFault(snapshot) }, {
            Toast.makeText(this, getString(R.string.changed_to, getString(stLabels[newStatus])), Toast.LENGTH_SHORT).show()
            render()
        })
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                DbExec.async(this, { db.deleteFault(id) }, { finish() })
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

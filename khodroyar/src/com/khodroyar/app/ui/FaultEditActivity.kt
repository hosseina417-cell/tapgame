package com.khodroyar.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.Fault
import com.khodroyar.app.data.Severity
import com.khodroyar.app.data.Status

class FaultEditActivity : Activity() {

    private lateinit var db: Db
    private var editId: Long = -1
    private var severity = Severity.MEDIUM
    private var status = Status.NEW

    private lateinit var edtTitle: EditText
    private lateinit var edtCar: EditText
    private lateinit var edtObd: EditText
    private lateinit var edtSymptoms: EditText
    private lateinit var edtDesc: EditText
    private lateinit var edtRepair: EditText
    private lateinit var edtTools: EditText
    private lateinit var edtParts: EditText
    private lateinit var edtCost: EditText

    private val quickTools = listOf(
        "آچار فرانسه", "آچار بکس", "جک + پایه", "مولتی‌متر", "دیاگ (اسکنر OBD)",
        "پیچ‌گوشتی", "آچار آلن", "پنجه‌غازی", "بالانسر", "چراغ قوه"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        db = Db.get(this)

        edtTitle = findViewById(R.id.edtTitle)
        edtCar = findViewById(R.id.edtCar)
        edtObd = findViewById(R.id.edtObd)
        edtSymptoms = findViewById(R.id.edtSymptoms)
        edtDesc = findViewById(R.id.edtDesc)
        edtRepair = findViewById(R.id.edtRepair)
        edtTools = findViewById(R.id.edtTools)
        edtParts = findViewById(R.id.edtParts)
        edtCost = findViewById(R.id.edtCost)

        editId = intent.getLongExtra("id", -1)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }

        val sevLabels = listOf(
            getString(R.string.sev_low), getString(R.string.sev_medium),
            getString(R.string.sev_high), getString(R.string.sev_critical)
        )
        val stLabels = listOf(
            getString(R.string.st_new), getString(R.string.st_checking),
            getString(R.string.st_repairing), getString(R.string.st_fixed)
        )
        val rowSev = findViewById<LinearLayout>(R.id.rowSeverity)
        val rowSt = findViewById<LinearLayout>(R.id.rowStatus)

        ChipGroup.build(this, rowSev, sevLabels, severity) { severity = it }
        ChipGroup.build(this, rowSt, stLabels, status) { status = it }

        // quick tool chips
        val rowChips = findViewById<LinearLayout>(R.id.rowToolChips)
        quickTools.forEach { t ->
            val tv = TextView(this)
            tv.text = "+ " + t
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = ChipGroup.dp(this, 8)
            lp.bottomMargin = ChipGroup.dp(this, 8)
            tv.layoutParams = lp
            tv.setPadding(ChipGroup.dp(this, 12), ChipGroup.dp(this, 5), ChipGroup.dp(this, 12), ChipGroup.dp(this, 5))
            tv.setTextSize(12f)
            tv.setBackgroundResource(R.drawable.bg_chip)
            tv.setTextColor(resources.getColor(R.color.textSec))
            tv.setOnClickListener {
                val cur = edtTools.text.toString()
                edtTools.setText(if (cur.isBlank()) t else cur.trimEnd() + "\n" + t)
                edtTools.setSelection(edtTools.text.length)
            }
            rowChips.addView(tv)
        }

        // edit mode?
        findViewById<TextView>(R.id.txtEditTitle).text =
            if (editId > 0) getString(R.string.edit) else getString(R.string.save) + " " + getString(R.string.faults_title)

        if (editId > 0) {
            val f = db.getFault(editId)
            if (f != null) {
                edtTitle.setText(f.title)
                edtCar.setText(f.carName)
                edtObd.setText(f.obdCode)
                edtSymptoms.setText(f.symptoms)
                edtDesc.setText(f.description)
                edtRepair.setText(f.repairMethod)
                edtTools.setText(f.tools)
                edtParts.setText(f.parts)
                if (f.cost > 0) edtCost.setText(f.cost.toLong().toString())
                severity = f.severity
                status = f.status
                ChipGroup.build(this, rowSev, sevLabels, severity) { severity = it }
                ChipGroup.build(this, rowSt, stLabels, status) { status = it }
            }
        }
    }

    private fun save() {
        val title = edtTitle.text.toString().trim()
        if (title.isEmpty()) {
            edtTitle.error = getString(R.string.title_required)
            Toast.makeText(this, R.string.title_required, Toast.LENGTH_SHORT).show()
            return
        }
        val cost = edtCost.text.toString().replace(",", "").trim()
        val costVal = if (cost.isEmpty()) 0.0 else cost.toDoubleOrNull() ?: run {
            Toast.makeText(this, R.string.cost_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val f: Fault
        if (editId > 0) {
            f = db.getFault(editId)!!
            f.title = title
            f.carName = edtCar.text.toString().trim()
            f.obdCode = edtObd.text.toString().trim().uppercase()
            f.severity = severity
            f.status = status
            f.symptoms = edtSymptoms.text.toString().trim()
            f.description = edtDesc.text.toString().trim()
            f.repairMethod = edtRepair.text.toString().trim()
            f.tools = edtTools.text.toString().trim()
            f.parts = edtParts.text.toString().trim()
            f.cost = costVal
            f.updatedAt = now
            if (status == Status.FIXED && f.fixedAt == null) f.fixedAt = now
            db.updateFault(f)
        } else {
            f = Fault(
                title = title,
                carName = edtCar.text.toString().trim(),
                obdCode = edtObd.text.toString().trim().uppercase(),
                severity = severity,
                status = status,
                symptoms = edtSymptoms.text.toString().trim(),
                description = edtDesc.text.toString().trim(),
                repairMethod = edtRepair.text.toString().trim(),
                tools = edtTools.text.toString().trim(),
                parts = edtParts.text.toString().trim(),
                cost = costVal,
                createdAt = now,
                updatedAt = now,
                fixedAt = if (status == Status.FIXED) now else null
            )
            db.insertFault(f)
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

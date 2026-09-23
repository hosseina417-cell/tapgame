package com.khodroyar.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.Backup
import com.khodroyar.app.data.DbExec
import com.khodroyar.app.data.Fault
import com.khodroyar.app.data.Status
import com.khodroyar.app.util.Fmt
import com.khodroyar.app.util.Jalali
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var adapter: FaultAdapter
    private val items = ArrayList<Fault>()
    private lateinit var listView: ListView
    private lateinit var boxEmpty: View
    private lateinit var emptyTitle: TextView
    private lateinit var txtEmptyHint: TextView
    private lateinit var statOpen: TextView
    private lateinit var statFixed: TextView
    private lateinit var statCost: TextView
    private lateinit var boxStats: View
    private var query: String = ""

    private val REQ_NEW_FAULT = 10
    private val REQ_EXPORT = 21
    private val REQ_IMPORT = 22

    private var searchPending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        listView = findViewById(R.id.listFaults)
        boxEmpty = findViewById(R.id.boxEmpty)
        emptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptyHint = findViewById(R.id.txtEmptyHint)
        statOpen = findViewById(R.id.statOpen)
        statFixed = findViewById(R.id.statFixed)
        statCost = findViewById(R.id.statCost)
        boxStats = findViewById(R.id.boxStats)

        adapter = FaultAdapter(this, items)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            startActivityForResult(
                Intent(this, FaultDetailActivity::class.java).putExtra("id", items[pos].id),
                REQ_NEW_FAULT
            )
        }

        findViewById<TextView>(R.id.fabAdd).apply {
            contentDescription = getString(R.string.new_fault)
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, FaultEditActivity::class.java), REQ_NEW_FAULT)
            }
        }
        findViewById<TextView>(R.id.btnExport).setOnClickListener { exportBackup() }
        findViewById<TextView>(R.id.btnImport).setOnClickListener { importBackup() }

        val search = findViewById<EditText>(R.id.edtSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // debounce: don't hit the DB for every keystroke
                searchPending?.let { findViewById<View>(R.id.listFaults).handler?.removeCallbacks(it) }
                val r = Runnable { query = s?.toString()?.trim() ?: ""; reload() }
                searchPending = r
                findViewById<View>(R.id.listFaults).handler?.postDelayed(r, 220)
            }
        })
        search.setOnEditorActionListener { v, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** All faults from DB (cached) filtered by current query, off the UI thread. */
    private var cacheAll: List<Fault> = emptyList()

    private fun reload() {
        DbExec.async(this, { db.allFaults() }) { all ->
            cacheAll = all
            applyFilterAndRender()
        }
    }

    private fun applyFilterAndRender() {
        items.clear()
        if (query.isEmpty()) {
            items.addAll(cacheAll)
        } else {
            val q = Fmt.normalize(query)
            for (f in cacheAll) {
                val hay = Fmt.normalize(
                    f.title + " " + f.carName + " " + f.obdCode + " " +
                            f.symptoms + " " + f.repairMethod + " " + f.tools + " " + f.parts
                )
                if (hay.contains(q)) items.add(f)
            }
        }
        adapter.notifyDataSetChanged()
        boxEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyTitle.text = getString(if (query.isEmpty()) R.string.empty_title else R.string.no_results)
        txtEmptyHint.text = getString(if (query.isEmpty()) R.string.empty_hint else R.string.no_results_hint)

        // stats (always over the whole dataset, not the filtered list)
        val open = cacheAll.count { it.status != Status.FIXED }
        val fixed = cacheAll.count { it.status == Status.FIXED }
        val total = cacheAll.sumOf { it.cost }
        statOpen.text = getString(R.string.stats_open_fmt, Fmt.faDigits(open.toString()))
        statFixed.text = getString(R.string.stats_fixed_fmt, Fmt.faDigits(fixed.toString()))
        statCost.text = getString(R.string.stats_total_fmt, Fmt.money(total))
        boxStats.visibility = if (cacheAll.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ backup I/O
    private fun exportBackup() {
        if (cacheAll.isEmpty() && query.isEmpty()) {
            // make sure cache is fresh before claiming "nothing to back up"
            DbExec.async(this, { db.allFaults() }) { all ->
                if (all.isEmpty()) {
                    Toast.makeText(this, R.string.backup_empty, Toast.LENGTH_SHORT).show()
                } else {
                    cacheAll = all
                    launchExportPicker()
                }
            }
            return
        }
        launchExportPicker()
    }

    private fun launchExportPicker() {
        val name = "khodroyar-backup-" + Jalali.formatShort(System.currentTimeMillis())
            .replace("/", "-") + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importBackup() {
        AlertDialogs.confirm(
            this, getString(R.string.import_title), getString(R.string.import_msg),
            getString(R.string.import_go)
        ) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_IMPORT
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        when (requestCode) {
            REQ_EXPORT -> DbExec.async(this, {
                val json = Backup.toJson(cacheAll.ifEmpty { db.allFaults() })
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
            }, {
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            })
            REQ_IMPORT -> DbExec.async(this, {
                val text = contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                } ?: return@async -1
                val list = Backup.fromJson(text)
                if (list.isEmpty()) return@async -1
                var n = 0
                for (f in list) {
                    val existing = db.getFault(f.id)
                    if (existing != null) { db.updateFault(f); n++ } else { db.insertFault(f); n++ }
                }
                n
            }, onDone = { n ->
                Toast.makeText(
                    this,
                    if (n >= 0) getString(R.string.import_done, Fmt.faDigits(n.toString()))
                    else getString(R.string.import_failed),
                    Toast.LENGTH_LONG
                ).show()
                reload()
            })
        }
    }
}

class FaultAdapter(private val act: Activity, private val items: List<Fault>) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id

    private val sevColors = intArrayOf(
        R.color.sevLow, R.color.sevMedium, R.color.sevHigh, R.color.sevCritical
    )
    private val sevLabels = intArrayOf(
        R.string.sev_low, R.string.sev_medium, R.string.sev_high, R.string.sev_critical
    )
    private val stLabels = intArrayOf(
        R.string.st_new, R.string.st_checking, R.string.st_repairing, R.string.st_fixed
    )
    private val stColors = intArrayOf(
        R.color.stNew, R.color.stChecking, R.color.stRepairing, R.color.stFixed
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v: View
        val h: Holder
        if (convertView == null) {
            v = act.layoutInflater.inflate(R.layout.item_fault, parent, false)
            h = Holder(
                dot = v.findViewById(R.id.dotSeverity),
                title = v.findViewById(R.id.txtItemTitle),
                car = v.findViewById(R.id.txtItemCar),
                status = v.findViewById(R.id.txtItemStatus),
                cost = v.findViewById(R.id.txtItemCost)
            )
            v.tag = h
        } else {
            v = convertView
            h = v.tag as Holder
        }
        val f = items[position]

        h.dot.background.setTint(act.resources.getColor(sevColors[f.severity.coerceIn(0, 3)]))
        h.title.text = f.title
        val meta = arrayListOf<String>()
        if (f.carName.isNotBlank()) meta.add(f.carName)
        if (f.obdCode.isNotBlank()) meta.add("OBD " + f.obdCode)
        meta.add(Jalali.formatShort(f.createdAt))
        h.car.text = meta.joinToString("  •  ")

        if (f.status == Status.FIXED) {
            val d = f.fixedAt?.let { " " + Jalali.formatShort(it) } ?: ""
            h.status.text = "✓ " + act.getString(stLabels[3]) + d
            h.status.setTextColor(act.resources.getColor(R.color.stFixed))
        } else {
            h.status.text = act.getString(stLabels[f.status.coerceIn(0, 3)])
            h.status.setTextColor(act.resources.getColor(stColors[f.status.coerceIn(0, 3)]))
        }

        h.cost.text = if (f.cost > 0) act.getString(R.string.cost_value, Fmt.money(f.cost)) else ""

        v.contentDescription = f.title + " - " + act.getString(sevLabels[f.severity.coerceIn(0, 3)])
        return v
    }

    private class Holder(
        val dot: View,
        val title: TextView,
        val car: TextView,
        val status: TextView,
        val cost: TextView
    )
}

/** tiny helper to avoid repeating AlertDialog boilerplate */
object AlertDialogs {
    fun confirm(act: Activity, title: String, msg: String, posText: String, onYes: () -> Unit) {
        android.app.AlertDialog.Builder(act)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(posText) { _, _ -> onYes() }
            .setNegativeButton(act.getString(R.string.cancel), null)
            .show()
    }
}

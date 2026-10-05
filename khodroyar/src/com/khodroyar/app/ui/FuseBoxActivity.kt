package com.khodroyar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.DbExec
import com.khodroyar.app.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Fuse-box registry per car: fuse number, amperage, circuit description.
 * Includes search and bulk import/export (JSON) so whole fuse charts
 * (e.g. Maxus) can be loaded from a file in one go.
 */
class FuseBoxActivity : Activity() {

    private lateinit var db: Db
    private lateinit var prefs: SharedPreferences
    private lateinit var listFuses: ListView
    private lateinit var txtFuseEmpty: TextView
    private lateinit var scrollCars: View
    private lateinit var rowCars: LinearLayout
    private lateinit var edtFuseSearch: EditText
    private val fuses = ArrayList<Db.Fuse>()
    private val shown = ArrayList<Db.Fuse>()
    private lateinit var adapter: FuseAdapter
    private var carFilter: String = ""
    private var carNames: List<String> = emptyList()
    private var query: String = ""

    private val REQ_IMPORT = 41
    private val REQ_EXPORT = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fusebox)
        db = Db.get(this)
        prefs = getSharedPreferences("khodroyar", MODE_PRIVATE)
        carFilter = prefs.getString("fuse_car", "") ?: ""

        listFuses = findViewById(R.id.listFuses)
        txtFuseEmpty = findViewById(R.id.txtFuseEmpty)
        scrollCars = findViewById(R.id.scrollFuseCars)
        rowCars = findViewById(R.id.rowFuseCars)
        edtFuseSearch = findViewById(R.id.edtFuseSearch)

        adapter = FuseAdapter(this, shown)
        listFuses.adapter = adapter
        listFuses.setOnItemClickListener { _, _, pos, _ -> editFuseDialog(shown[pos]) }
        listFuses.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(shown[pos]); true
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddCar).setOnClickListener { newCarDialog() }
        findViewById<TextView>(R.id.btnAddFuse).setOnClickListener { addFuseDialog() }
        findViewById<TextView>(R.id.btnFuseImport).setOnClickListener { importFromFile() }
        findViewById<TextView>(R.id.btnFuseExport).setOnClickListener { exportToFile() }

        edtFuseSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refilter()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        ensurePresetLoaded(force = false) { reload() }
    }

    /**
     * Seeds the built-in Maxus fuse chart automatically on first open
     * (flag is only set on real success so failures are retried).
     */
    private fun ensurePresetLoaded(force: Boolean, done: () -> Unit) {
        if (!force && prefs.getBoolean("fuse_preset_v2_loaded", false)) { done(); return }
        DbExec.async(this, {
            try {
                val text = assets.open("preset_fusebox.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                importJson(text)
            } catch (e: Exception) { -1 }
        }, onDone = { n ->
            if (n > 0) {
                prefs.edit().putBoolean("fuse_preset_v2_loaded", true).apply()
                Toast.makeText(this, getString(R.string.fuse_import_ok, Fmt.faDigits(n.toString())), Toast.LENGTH_SHORT).show()
            }
            done()
        })
    }

    private fun reload() {
        DbExec.async(this, {
            // only cars that actually have fuses (not every car from the faults list)
            val fuseCars = db.fuseCars().sorted()
            // keep a pending new-car selection; only auto-pick when nothing is chosen
            if (carFilter.isEmpty() && fuseCars.isNotEmpty()) carFilter = fuseCars[0]
            val fs = if (carFilter.isEmpty()) emptyList() else db.fusesFor(carFilter)
            fuseCars to fs
        }, onDone = { (names, fs) ->
            carNames = names
            if (carNames.isEmpty()) {
                scrollCars.visibility = View.GONE
            } else {
                scrollCars.visibility = View.VISIBLE
                val labels = carNames.map { "🚗 " + it }
                ChipGroup.build(this, rowCars, labels, carNames.indexOf(carFilter)) { idx ->
                    carFilter = carNames[idx]
                    prefs.edit().putString("fuse_car", carFilter).apply()
                    reload()
                }
            }
            fuses.clear(); fuses.addAll(fs)
            refilter()
        })
    }

    /** Applies the search query over [fuses] into [shown]. */
    private fun refilter() {
        shown.clear()
        if (query.isEmpty()) {
            shown.addAll(fuses)
        } else {
            val q = Fmt.normalize(query)
            for (f in fuses) {
                val hay = Fmt.normalize(f.fuseNo + " " + f.amp + " " + f.circuit)
                if (hay.contains(q)) shown.add(f)
            }
        }
        adapter.notifyDataSetChanged()
        txtFuseEmpty.visibility =
            if (shown.isEmpty() || carFilter.isEmpty()) View.VISIBLE else View.GONE
        if (query.isNotEmpty() && fuses.isNotEmpty()) {
            txtFuseEmpty.text = getString(R.string.fuse_no_result)
        } else {
            txtFuseEmpty.text = getString(R.string.fuse_empty)
        }
    }

    // ---------------------------------------------------------------- dialogs
    private fun inputDialog(title: String, existing: Db.Fuse?, onOk: (String, String, String) -> Unit) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(hint: String, text: String): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(text)
                setSingleLine(true)
            }
        val edtNo = field(getString(R.string.fuse_no_hint), existing?.fuseNo ?: "")
        val edtAmp = field(getString(R.string.fuse_amp_hint), existing?.amp ?: "")
        val edtCircuit = field(getString(R.string.fuse_circuit_hint), existing?.circuit ?: "")
        listOf(edtNo, edtAmp, edtCircuit).forEach { e ->
            box.addView(e)
            (e.layoutParams as LinearLayout.LayoutParams).setMargins(pad, pad / 2, pad, 0)
        }
        box.setPadding(0, pad / 2, 0, 0)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val no = edtNo.text.toString().trim()
                if (no.isEmpty()) {
                    Toast.makeText(this, R.string.fuse_no_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onOk(no, edtAmp.text.toString().trim(), edtCircuit.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addFuseDialog() {
        if (carFilter.isEmpty()) {
            // no car selected yet -> create one on the fly
            newCarDialog()
            return
        }
        inputDialog(getString(R.string.fuse_add) + " — " + carFilter, null) { no, amp, circuit ->
            val car = carFilter
            DbExec.async(this, { db.addFuse(Db.Fuse(carName = car, fuseNo = no, amp = amp, circuit = circuit)) },
                onDone = { reload() })
        }
    }

    /** Creates a brand-new car for the fuse box and jumps straight to its first fuse. */
    private fun newCarDialog() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.fuse_new_car_hint)
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(input)
        (input.layoutParams as LinearLayout.LayoutParams).setMargins(pad, pad / 2, pad, 0)

        AlertDialog.Builder(this)
            .setTitle(R.string.fuse_new_car_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.fuse_need_name_car, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                carFilter = name
                prefs.edit().putString("fuse_car", name).apply()
                addFuseDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editFuseDialog(f: Db.Fuse) {
        inputDialog(getString(R.string.fuse_edit), f) { no, amp, circuit ->
            val id = f.id
            DbExec.async(this, {
                f.fuseNo = no; f.amp = amp; f.circuit = circuit
                db.updateFuse(f)
                id
            }, onDone = { reload() })
        }
    }

    private fun confirmDelete(f: Db.Fuse) {
        AlertDialogs.confirm(
            this, getString(R.string.fuse_delete_title),
            getString(R.string.fuse_delete_msg, f.fuseNo), getString(R.string.delete)
        ) {
            val id = f.id
            DbExec.async(this, { db.deleteFuse(id) }, onDone = { reload() })
        }
    }

    // ------------------------------------------------------- bulk import/export
    /** JSON shape: {"cars":[{"car":"مکسوس T60","fuses":[{"no":"F12","amp":"15","circuit":"فن رادیاتور"}]}]} */
    private fun importFromFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_IMPORT
        )
    }

    private fun exportToFile() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "fusebox-" +
                    carFilter.ifBlank { "all" }.replace(" ", "-") + ".json")
            }, REQ_EXPORT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        when (requestCode) {
            REQ_IMPORT -> DbExec.async(this, {
                val text = contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                } ?: return@async -1
                importJson(text)
            }, onDone = { n ->
                if (n > 0) {
                    Toast.makeText(this, getString(R.string.fuse_import_ok, Fmt.faDigits(n.toString())), Toast.LENGTH_LONG).show()
                    reload()
                } else {
                    Toast.makeText(this, R.string.fuse_import_bad, Toast.LENGTH_LONG).show()
                }
            })
            REQ_EXPORT -> DbExec.async(this, {
                val root = JSONObject()
                val cars = JSONArray()
                val list = if (carFilter.isBlank()) carNames else listOf(carFilter)
                for (car in list) {
                    val o = JSONObject()
                    o.put("car", car)
                    val arr = JSONArray()
                    for (f in db.fusesFor(car)) {
                        arr.put(JSONObject().put("no", f.fuseNo).put("amp", f.amp).put("circuit", f.circuit))
                    }
                    o.put("fuses", arr)
                    cars.put(o)
                }
                root.put("cars", cars)
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
            }, onDone = {
                Toast.makeText(this, R.string.fuse_export_ok, Toast.LENGTH_SHORT).show()
            })
        }
    }

    /** Parses import JSON and inserts (skipping exact duplicates). Returns count added (-1 = bad file). */
    private fun importJson(text: String): Int {
        var added = 0
        val carsArr: JSONArray = try {
            val root = JSONObject(text)
            root.optJSONArray("cars") ?: if (root.has("car") && root.has("fuses")) {
                JSONArray().put(root)
            } else return -1
        } catch (e: Exception) { return -1 }

        for (i in 0 until carsArr.length()) {
            try {
                val o = carsArr.getJSONObject(i)
                val car = o.optString("car").trim()
                val arr = o.optJSONArray("fuses") ?: continue
                if (car.isEmpty() || arr.length() == 0) continue
                val existing = db.fusesFor(car).map {
                    Triple(Fmt.normalize(it.fuseNo), Fmt.normalize(it.amp), Fmt.normalize(it.circuit))
                }.toHashSet()
                for (j in 0 until arr.length()) {
                    val f = arr.getJSONObject(j)
                    val no = f.optString("no").trim()
                    if (no.isEmpty()) continue
                    val amp = f.optString("amp").trim()
                    val circuit = f.optString("circuit").trim()
                    val key = Triple(Fmt.normalize(no), Fmt.normalize(amp), Fmt.normalize(circuit))
                    if (key in existing) continue
                    existing.add(key)
                    db.addFuse(Db.Fuse(carName = car, fuseNo = no, amp = amp, circuit = circuit))
                    added++
                }
            } catch (e: Exception) { /* skip bad entry */ }
        }
        return added
    }
}

class FuseAdapter(private val act: Activity, private val items: List<Db.Fuse>) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v: View
        val h: Holder
        if (convertView == null) {
            v = act.layoutInflater.inflate(R.layout.item_fuse, parent, false)
            h = Holder(
                badge = v.findViewById(R.id.txtFuseBadge),
                amp = v.findViewById(R.id.txtFuseAmp),
                circuit = v.findViewById(R.id.txtFuseCircuit)
            )
            v.tag = h
        } else {
            v = convertView
            h = v.tag as Holder
        }
        val f = items[position]
        h.badge.text = Fmt.faDigits("فیوز " + f.fuseNo)
        h.badge.background.mutate().setTint(act.resources.getColor(R.color.primary))
        h.badge.setTextColor(android.graphics.Color.WHITE)
        h.amp.text = if (f.amp.isNotBlank()) "⚡ " + Fmt.faDigits(f.amp) + " آمپر" else ""
        h.circuit.text = f.circuit.ifBlank { act.getString(R.string.fuse_no_circuit) }
        return v
    }

    private class Holder(val badge: TextView, val amp: TextView, val circuit: TextView)
}

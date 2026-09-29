package com.khodroyar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
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

/**
 * Fuse-box registry per car: fuse number, amperage, circuit description.
 */
class FuseBoxActivity : Activity() {

    private lateinit var db: Db
    private lateinit var prefs: SharedPreferences
    private lateinit var listFuses: ListView
    private lateinit var txtFuseEmpty: TextView
    private lateinit var scrollCars: View
    private lateinit var rowCars: LinearLayout
    private val fuses = ArrayList<Db.Fuse>()
    private lateinit var adapter: FuseAdapter
    private var carFilter: String = ""
    private var carNames: List<String> = emptyList()

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

        adapter = FuseAdapter(this, fuses)
        listFuses.adapter = adapter
        listFuses.setOnItemClickListener { _, _, pos, _ -> editFuseDialog(fuses[pos]) }
        listFuses.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(fuses[pos]); true
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddFuse).setOnClickListener { addFuseDialog() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        DbExec.async(this, {
            val fuseCars = db.fuseCars()
            val faultCars = db.allFaults().map { it.carName.trim() }.filter { it.isNotBlank() }.distinct()
            val names = (fuseCars + faultCars).distinct().sorted()
            if (names.isEmpty()) carFilter = ""
            else if (!names.contains(carFilter)) carFilter = names[0]
            val fs = if (carFilter.isEmpty()) emptyList() else db.fusesFor(carFilter)
            names to fs
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
            adapter.notifyDataSetChanged()
            txtFuseEmpty.visibility =
                if (fuses.isEmpty() || carFilter.isEmpty()) View.VISIBLE else View.GONE
        })
    }

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
            Toast.makeText(this, R.string.fuse_need_car, Toast.LENGTH_SHORT).show()
            return
        }
        inputDialog(getString(R.string.fuse_add) + " — " + carFilter, null) { no, amp, circuit ->
            val car = carFilter
            DbExec.async(this, { db.addFuse(Db.Fuse(carName = car, fuseNo = no, amp = amp, circuit = circuit)) },
                onDone = { reload() })
        }
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

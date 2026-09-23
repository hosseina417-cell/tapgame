package com.khodroyar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.Db
import com.khodroyar.app.data.Fault
import com.khodroyar.app.data.Status
import com.khodroyar.app.util.Fmt
import com.khodroyar.app.util.Jalali

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var adapter: FaultAdapter
    private val items = ArrayList<Fault>()
    private lateinit var listView: ListView
    private lateinit var boxEmpty: View
    private lateinit var emptyTitle: TextView
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        listView = findViewById(R.id.listFaults)
        boxEmpty = findViewById(R.id.boxEmpty)
        emptyTitle = findViewById(R.id.txtEmptyTitle)

        adapter = FaultAdapter(this, items)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val f = items[pos]
            startActivityForResult(
                Intent(this, FaultDetailActivity::class.java).putExtra("id", f.id), 10
            )
        }

        findViewById<TextView>(R.id.fabAdd).setOnClickListener {
            startActivityForResult(Intent(this, FaultEditActivity::class.java), 10)
        }

        findViewById<EditText>(R.id.edtSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                reload()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        val all = db.allFaults()
        if (query.isEmpty()) {
            items.addAll(all)
        } else {
            val q = query.lowercase()
            for (f in all) {
                val hay = (f.title + " " + f.carName + " " + f.obdCode + " " +
                        f.symptoms + " " + f.repairMethod + " " + f.tools + " " + f.parts).lowercase()
                if (hay.contains(q)) items.add(f)
            }
        }
        adapter.notifyDataSetChanged()
        boxEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyTitle.text = getString(
            if (query.isEmpty()) R.string.empty_title else R.string.no_results
        )
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
        val v: View = convertView ?: act.layoutInflater.inflate(R.layout.item_fault, parent, false)
        val f = items[position]

        val dot = v.findViewById<View>(R.id.dotSeverity)
        dot.background.setTint(act.resources.getColor(sevColors[f.severity.coerceIn(0, 3)]))

        v.findViewById<TextView>(R.id.txtItemTitle).text = f.title
        v.findViewById<TextView>(R.id.txtItemCar).text =
            listOf(f.carName, Jalali.formatShort(f.createdAt)).filter { it.isNotBlank() }.joinToString("  •  ")

        val st = v.findViewById<TextView>(R.id.txtItemStatus)
        if (f.status == Status.FIXED) {
            st.text = "✓ " + act.getString(stLabels[f.status.coerceIn(0, 3)])
            st.setTextColor(act.resources.getColor(R.color.stFixed))
        } else {
            st.text = act.getString(stLabels[f.status.coerceIn(0, 3)])
            st.setTextColor(act.resources.getColor(stColors[f.status.coerceIn(0, 3)]))
        }

        val cost = v.findViewById<TextView>(R.id.txtItemCost)
        cost.text = if (f.cost > 0) act.getString(R.string.cost_value, Fmt.money(f.cost)) else ""

        // severity label next to title via contentDescription for now
        v.contentDescription = f.title + " - " + act.getString(sevLabels[f.severity.coerceIn(0, 3)])
        return v
    }
}

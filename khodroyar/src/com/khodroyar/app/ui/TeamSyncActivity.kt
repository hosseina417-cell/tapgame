package com.khodroyar.app.ui

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.khodroyar.app.R
import com.khodroyar.app.data.DbExec
import com.khodroyar.app.team.SyncClient
import com.khodroyar.app.team.SyncServer
import com.khodroyar.app.util.Fmt
import com.khodroyar.app.util.Jalali

/**
 * Team sync screen: manager runs the LAN server, employees connect and push/pull.
 */
class TeamSyncActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var edtDeviceName: EditText
    private lateinit var edtManagerIp: EditText
    private lateinit var btnServerToggle: TextView
    private lateinit var txtServerStatus: TextView
    private lateinit var txtServerIps: TextView
    private lateinit var btnSyncNow: TextView
    private lateinit var txtSyncResult: TextView
    private lateinit var txtLogEmpty: TextView
    private lateinit var listLog: ListView

    private val handler = Handler(Looper.getMainLooper())
    private var logAdapter: ArrayAdapter<String>? = null
    private val logRows = ArrayList<String>()

    private val refresher = object : Runnable {
        override fun run() {
            if (SyncServer.running) {
                refreshServerUi()
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        prefs = getSharedPreferences("khodroyar", MODE_PRIVATE)

        edtDeviceName = findViewById(R.id.edtDeviceName)
        edtManagerIp = findViewById(R.id.edtManagerIp)
        btnServerToggle = findViewById(R.id.btnServerToggle)
        txtServerStatus = findViewById(R.id.txtServerStatus)
        txtServerIps = findViewById(R.id.txtServerIps)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        txtSyncResult = findViewById(R.id.txtSyncResult)
        txtLogEmpty = findViewById(R.id.txtLogEmpty)
        listLog = findViewById(R.id.listLog)

        edtDeviceName.setText(prefs.getString("device_name", ""))
        edtManagerIp.setText(prefs.getString("manager_ip", ""))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        btnServerToggle.setOnClickListener { toggleServer() }
        btnSyncNow.setOnClickListener { doSync() }

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logRows)
        listLog.adapter = logAdapter

        refreshServerUi()
    }

    override fun onResume() {
        super.onResume()
        refreshServerUi()
        handler.postDelayed(refresher, 2000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun saveBasics() {
        prefs.edit()
            .putString("device_name", edtDeviceName.text.toString().trim())
            .putString("manager_ip", edtManagerIp.text.toString().trim())
            .apply()
    }

    private fun toggleServer() {
        if (!SyncServer.running) {
            saveBasics()
            if (edtDeviceName.text.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.team_need_name, Toast.LENGTH_SHORT).show()
                return
            }
            SyncServer.start(application)
            Toast.makeText(this, R.string.team_server_started, Toast.LENGTH_SHORT).show()
        } else {
            SyncServer.stop()
        }
        refreshServerUi()
        if (SyncServer.running) handler.postDelayed(refresher, 2000)
    }

    private fun refreshServerUi() {
        if (SyncServer.running) {
            btnServerToggle.text = getString(R.string.team_stop_server)
            txtServerStatus.text = getString(R.string.team_server_running, Fmt.faDigits(SyncServer.PORT.toString()))
            val ips = SyncServer.localIps()
            txtServerIps.visibility = View.VISIBLE
            txtServerIps.text = ips.joinToString("\n") { Fmt.faDigits(it) + ":${SyncServer.PORT}" }
            renderLog()
        } else {
            btnServerToggle.text = getString(R.string.team_start_server)
            txtServerStatus.text = getString(R.string.team_server_stopped)
            txtServerIps.visibility = View.GONE
            txtServerIps.text = ""
        }
        val has = SyncServer.events.isNotEmpty()
        txtLogEmpty.visibility = if (has) View.GONE else View.VISIBLE
        if (!has) { logRows.clear(); logAdapter?.notifyDataSetChanged() }
    }

    private fun renderLog() {
        val fresh = SyncServer.events.map { (ts, dev, summary) ->
            "👤 " + dev + "   🔄 " + Jalali.formatFull(ts) + "\n" + summary
        }
        if (fresh == logRows) return
        logRows.clear()
        logRows.addAll(fresh)
        logAdapter?.notifyDataSetChanged()
    }

    private fun doSync() {
        saveBasics()
        val name = edtDeviceName.text.toString().trim()
        val ip = edtManagerIp.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, R.string.team_need_name, Toast.LENGTH_SHORT).show(); return }
        if (ip.isEmpty()) { Toast.makeText(this, R.string.team_need_ip, Toast.LENGTH_SHORT).show(); return }

        btnSyncNow.isEnabled = false
        btnSyncNow.text = getString(R.string.team_syncing)
        val lastSync = prefs.getLong("last_sync", 0)

        DbExec.async(this, {
            SyncClient.sync(applicationContext, ip, name, lastSync)
        }, onError = { t ->
            btnSyncNow.isEnabled = true
            btnSyncNow.text = getString(R.string.team_sync_now)
            txtSyncResult.text = getString(R.string.team_sync_fail) + "\n(" + t.message + ")"
        }, onDone = { outcome ->
            btnSyncNow.isEnabled = true
            btnSyncNow.text = getString(R.string.team_sync_now)
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            val msg = getString(
                R.string.team_sync_ok,
                Fmt.faDigits(outcome.gotFaults.toString()),
                Fmt.faDigits(outcome.sentFaults.toString())
            )
            txtSyncResult.text = msg + "\n" + getString(
                R.string.team_last_sync, Jalali.formatFull(System.currentTimeMillis())
            )
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        })
    }
}

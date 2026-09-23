package com.khodroyar.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "khodroyar.db", null, DB_VERSION) {

    companion object {
        const val DB_VERSION = 1

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context.applicationContext).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE faults(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                car_name TEXT DEFAULT '',
                obd_code TEXT DEFAULT '',
                severity INTEGER DEFAULT 1,
                status INTEGER DEFAULT 0,
                symptoms TEXT DEFAULT '',
                description TEXT DEFAULT '',
                repair_method TEXT DEFAULT '',
                tools TEXT DEFAULT '',
                parts TEXT DEFAULT '',
                cost REAL DEFAULT 0,
                created_at INTEGER,
                updated_at INTEGER,
                fixed_at INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX idx_faults_status ON faults(status)")
        db.execSQL("CREATE INDEX idx_faults_created ON faults(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // future migrations
    }

    // ---------------------------------------------------------------- CRUD
    fun insertFault(f: Fault): Long {
        val cv = toValues(f)
        return writableDatabase.insert("faults", null, cv)
    }

    fun updateFault(f: Fault): Int {
        val cv = toValues(f)
        return writableDatabase.update("faults", cv, "id=?", arrayOf(f.id.toString()))
    }

    fun deleteFault(id: Long): Int =
        writableDatabase.delete("faults", "id=?", arrayOf(id.toString()))

    fun getFault(id: Long): Fault? =
        readableDatabase.rawQuery("SELECT * FROM faults WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) fromCursor(c) else null
        }

    fun allFaults(): MutableList<Fault> {
        val out = ArrayList<Fault>()
        readableDatabase.rawQuery(
            "SELECT * FROM faults ORDER BY (status=3), updated_at DESC", null
        ).use { c -> while (c.moveToNext()) out.add(fromCursor(c)) }
        return out
    }

    // ---------------------------------------------------------------- helpers
    private fun toValues(f: Fault) = ContentValues().apply {
        put("title", f.title)
        put("car_name", f.carName)
        put("obd_code", f.obdCode)
        put("severity", f.severity)
        put("status", f.status)
        put("symptoms", f.symptoms)
        put("description", f.description)
        put("repair_method", f.repairMethod)
        put("tools", f.tools)
        put("parts", f.parts)
        put("cost", f.cost)
        put("created_at", f.createdAt)
        put("updated_at", f.updatedAt)
        put("fixed_at", f.fixedAt)
    }

    private fun fromCursor(c: android.database.Cursor) = Fault(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        carName = c.getString(c.getColumnIndexOrThrow("car_name")) ?: "",
        obdCode = c.getString(c.getColumnIndexOrThrow("obd_code")) ?: "",
        severity = c.getInt(c.getColumnIndexOrThrow("severity")),
        status = c.getInt(c.getColumnIndexOrThrow("status")),
        symptoms = c.getString(c.getColumnIndexOrThrow("symptoms")) ?: "",
        description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
        repairMethod = c.getString(c.getColumnIndexOrThrow("repair_method")) ?: "",
        tools = c.getString(c.getColumnIndexOrThrow("tools")) ?: "",
        parts = c.getString(c.getColumnIndexOrThrow("parts")) ?: "",
        cost = c.getDouble(c.getColumnIndexOrThrow("cost")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        fixedAt = if (c.isNull(c.getColumnIndexOrThrow("fixed_at"))) null else c.getLong(c.getColumnIndexOrThrow("fixed_at"))
    )
}

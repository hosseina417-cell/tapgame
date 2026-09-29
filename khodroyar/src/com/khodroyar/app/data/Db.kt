package com.khodroyar.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "khodroyar.db", null, DB_VERSION) {

    companion object {
        const val DB_VERSION = 3

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
                vin TEXT DEFAULT '',
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
        db.execSQL(
            """CREATE TABLE fuses(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                car_name TEXT NOT NULL,
                fuse_no TEXT DEFAULT '',
                amp TEXT DEFAULT '',
                circuit TEXT DEFAULT ''
            )"""
        )
        db.execSQL(
            """CREATE TABLE photos(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fault_id INTEGER NOT NULL,
                path TEXT NOT NULL,
                created_at INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX idx_photos_fault ON photos(fault_id)")
        db.execSQL("CREATE INDEX idx_fuses_car ON fuses(car_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE faults ADD COLUMN vin TEXT DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS fuses(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    car_name TEXT NOT NULL,
                    fuse_no TEXT DEFAULT '',
                    amp TEXT DEFAULT '',
                    circuit TEXT DEFAULT ''
                )"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS photos(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fault_id INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    created_at INTEGER
                )"""
            )
        }
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

    // ---------------------------------------------------------------- fuses
    data class Fuse(var id: Long = 0, var carName: String = "", var fuseNo: String = "", var amp: String = "", var circuit: String = "")

    fun addFuse(f: Fuse): Long {
        val cv = ContentValues().apply {
            put("car_name", f.carName); put("fuse_no", f.fuseNo)
            put("amp", f.amp); put("circuit", f.circuit)
        }
        return writableDatabase.insert("fuses", null, cv)
    }

    fun updateFuse(f: Fuse): Int {
        val cv = ContentValues().apply {
            put("car_name", f.carName); put("fuse_no", f.fuseNo)
            put("amp", f.amp); put("circuit", f.circuit)
        }
        return writableDatabase.update("fuses", cv, "id=?", arrayOf(f.id.toString()))
    }

    fun deleteFuse(id: Long) = writableDatabase.delete("fuses", "id=?", arrayOf(id.toString()))

    fun fusesFor(carName: String): List<Fuse> {
        val out = ArrayList<Fuse>()
        readableDatabase.rawQuery(
            "SELECT * FROM fuses WHERE car_name=? ORDER BY id", arrayOf(carName)
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fuse(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    carName = c.getString(c.getColumnIndexOrThrow("car_name")),
                    fuseNo = c.getString(c.getColumnIndexOrThrow("fuse_no")),
                    amp = c.getString(c.getColumnIndexOrThrow("amp")),
                    circuit = c.getString(c.getColumnIndexOrThrow("circuit"))
                )
            )
        }
        return out
    }

    fun fuseCars(): List<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT car_name FROM fuses", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- photos
    fun addPhoto(faultId: Long, path: String): Long {
        val cv = ContentValues().apply {
            put("fault_id", faultId); put("path", path)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("photos", null, cv)
    }

    fun deletePhoto(id: Long) = writableDatabase.delete("photos", "id=?", arrayOf(id.toString()))

    data class PhotoRow(var id: Long = 0, var faultId: Long = 0, var path: String = "")

    fun photosFor(faultId: Long): List<PhotoRow> {
        val out = ArrayList<PhotoRow>()
        readableDatabase.rawQuery(
            "SELECT * FROM photos WHERE fault_id=? ORDER BY id", arrayOf(faultId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                PhotoRow(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    faultId = c.getLong(c.getColumnIndexOrThrow("fault_id")),
                    path = c.getString(c.getColumnIndexOrThrow("path"))
                )
            )
        }
        return out
    }

    fun allPhotos(): List<PhotoRow> {
        val out = ArrayList<PhotoRow>()
        readableDatabase.rawQuery("SELECT * FROM photos ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(
                PhotoRow(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    faultId = c.getLong(c.getColumnIndexOrThrow("fault_id")),
                    path = c.getString(c.getColumnIndexOrThrow("path"))
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- helpers
    private fun toValues(f: Fault) = ContentValues().apply {
        put("title", f.title)
        put("car_name", f.carName)
        put("vin", f.vin)
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
        vin = c.getString(c.getColumnIndexOrThrow("vin")) ?: "",
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

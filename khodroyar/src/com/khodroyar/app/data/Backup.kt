package com.khodroyar.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON backup serialization (uses org.json bundled with Android).
 * Format: {"app":"khodroyar","version":1,"faults":[...]}
 */
object Backup {
    private const val FMT_VERSION = 1

    fun toJson(faults: List<Fault>): String {
        val arr = JSONArray()
        for (f in faults) {
            arr.put(JSONObject().apply {
                put("id", f.id)
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
                if (f.fixedAt != null) put("fixed_at", f.fixedAt)
            })
        }
        return JSONObject().apply {
            put("app", "khodroyar")
            put("backup_version", FMT_VERSION)
            put("faults", arr)
        }.toString(2)
    }

    fun fromJson(text: String): List<Fault> {
        val root = JSONObject(text)
        if (root.optString("app") != "khodroyar") return emptyList()
        val arr = root.optJSONArray("faults") ?: return emptyList()
        val out = ArrayList<Fault>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Fault(
                    id = o.optLong("id", 0),
                    title = o.optString("title"),
                    carName = o.optString("car_name"),
                    obdCode = o.optString("obd_code"),
                    severity = o.optInt("severity", Severity.MEDIUM),
                    status = o.optInt("status", Status.NEW),
                    symptoms = o.optString("symptoms"),
                    description = o.optString("description"),
                    repairMethod = o.optString("repair_method"),
                    tools = o.optString("tools"),
                    parts = o.optString("parts"),
                    cost = o.optDouble("cost", 0.0),
                    createdAt = o.optLong("created_at", System.currentTimeMillis()),
                    updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
                    fixedAt = if (o.has("fixed_at") && !o.isNull("fixed_at")) o.optLong("fixed_at") else null
                )
            )
        }
        return out
    }
}

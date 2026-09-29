package com.khodroyar.app.team

import com.khodroyar.app.data.Db
import com.khodroyar.app.data.Fault

/**
 * Shared merge logic used by both the manager (server) and employees (client).
 * Rule: last-write-wins by updatedAt for the *same* record (same createdAt),
 * but a colliding id that is actually a *different* record (different createdAt —
 * happens when two devices create records offline) is inserted as a brand-new row
 * and reported back as a remap so the source device can drop its stale copy.
 */
object Merge {

    class Result {
        var added = 0
        var updated = 0
        var skipped = 0
        val remap = ArrayList<Pair<Long, Long>>() // (client-side id, real id on server)
    }

    fun applyFaults(db: Db, incoming: List<Fault>): Result {
        val r = Result()
        for (f in incoming) {
            val local = db.getFault(f.id)
            when {
                local == null -> {
                    db.insertFaultWithId(f)
                    r.added++
                }
                local.createdAt == f.createdAt -> {
                    if (f.updatedAt > local.updatedAt) {
                        db.updateFault(f)
                        r.updated++
                    } else r.skipped++
                }
                else -> {
                    val newId = db.insertFault(f) // new auto id
                    r.remap.add(f.id to newId)
                    r.added++
                }
            }
        }
        return r
    }
}

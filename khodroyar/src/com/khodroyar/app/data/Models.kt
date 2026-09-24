package com.khodroyar.app.data

object Severity {
    const val LOW = 0; const val MEDIUM = 1; const val HIGH = 2; const val CRITICAL = 3
}

object Status {
    const val NEW = 0; const val CHECKING = 1; const val REPAIRING = 2; const val FIXED = 3
}

data class Fault(
    var id: Long = 0,
    var title: String = "",
    var carName: String = "",
    var vin: String = "",
    var obdCode: String = "",
    var severity: Int = Severity.MEDIUM,
    var status: Int = Status.NEW,
    var symptoms: String = "",
    var description: String = "",
    var repairMethod: String = "",
    var tools: String = "",
    var parts: String = "",
    var cost: Double = 0.0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var fixedAt: Long? = null
)

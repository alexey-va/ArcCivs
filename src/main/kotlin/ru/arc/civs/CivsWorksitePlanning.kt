package ru.arc.civs

/** Shared action description before the existing native/item receipt is built. */
internal data class CivWorkAction(val changes: List<CivPlanBlock>, val inputs: Map<String, Long> = emptyMap(),
    val outputs: Map<String, Long> = emptyMap(), val status: String = "working-field",
    val duration: Long = 3_000, val focus: CivPosition? = null)
internal data class CivWorkNeed(val status: String, val material: String? = null, val amount: Long = 1,
    val point: CivPosition? = null, val reason: String? = null, val blockMaterial: String? = null)
internal data class CivWorkApproach(val standing: CivPosition, val remoteWork: Boolean = false,
    val focus: CivPosition? = null, val capturedWork: CivWorldWork? = null)
internal data class CivWorkAccess(val approach: CivWorkApproach?, val pending: Boolean)

/** The existing aggregate discovery ledger is shared by field refresh, forestry and native access. */
internal class CivWorkDiscoveryBudget(private val nanoTime: () -> Long) {
    var forestUnits = 0
    var columns = 0
    var deadline = 0L
    fun forestAvailable(): Boolean = forestUnits > 0 && nanoTime() < deadline
}

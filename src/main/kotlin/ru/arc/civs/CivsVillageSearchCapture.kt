package ru.arc.civs

import com.google.gson.Gson
import org.bukkit.Bukkit
import ru.arc.persistence.AtomicFileStore
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

internal data class CivVillageSearchTemplate(val current: CivBuilding, val maximum: CivBuilding)

internal data class CivVillageSearchCaptureRecord(val schema: Int, val operation: UUID, val reference: UUID,
    val point: CivPosition, val style: String, val pluginVersion: String, val capturedAtUtc: String,
    val draft: Settlement, val surfaceOrigin: String, val surface: CivTerrainSurface,
    val templates: Map<BuildingType, CivVillageSearchTemplate>, val partial: CivSettlementPlan,
    val reason: String, val details: Map<String, String>)

/** Two fixed detached rejection witnesses; called synchronously by the existing single planning worker. */
internal class CivsVillageSearchCapture(private val root: Path, private val pluginVersion: String,
    private val logger: Logger, private val clock: () -> Instant = Instant::now) {
    private var operation: UUID? = null
    private var attempts = 0

    @Synchronized
    fun <T> prove(operation: UUID, reference: UUID, draft: Settlement, surface: CivTerrainSurface,
        templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
        proof: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit) -> T): T {
        if (this.operation != operation) { this.operation = operation; attempts = 0 }
        var partial: CivSettlementPlan? = null
        try {
            return proof { plan, _ -> partial = plan }
        } catch (failure: Throwable) {
            val observed = partial
            if (observed != null && attempts < 2) {
                val slot = attempts++ // Failed writes consume the same finite admission, without retry spam.
                val path = Path.of("debug", "village-search", "rejected-$slot.json")
                val captureFailure = runCatching { write(path, operation, reference, draft, surface, templates, observed, failure) }.exceptionOrNull()
                runCatching {
                    if (captureFailure == null) logger.info("ArcCivs village search capture operation=$operation path=$path state=written")
                    else {
                        logger.warning("ArcCivs village search capture operation=$operation path=$path state=failed reason=" +
                            (CivsDiagnostics.rejection(captureFailure)?.reason ?: captureFailure.javaClass.simpleName).take(64))
                    }
                }
            }
            throw failure
        }
    }

    private fun write(path: Path, operation: UUID, reference: UUID, draft: Settlement,
        surface: CivTerrainSurface, templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
        partial: CivSettlementPlan, failure: Throwable) {
        check(!Bukkit.isPrimaryThread()) { "Village search capture serialization and IO must run off Main" }
        if (surface.columns.size !in 1..MAXIMUM_COLUMNS || surface.minHeight >= surface.maxHeight)
            throw CivsRejection("village-capture-invalid-surface")
        // Only an unfounded draft is eligible: no resident bags, corpses, native cells or item-bearing receipts.
        if (draft.workers.isNotEmpty() || !draft.corpses.isNullOrEmpty() || !draft.forestryJobs.isNullOrEmpty() ||
            !draft.minePlans.isNullOrEmpty() || draft.construction != null || !draft.pendingControls.isNullOrEmpty() ||
            !draft.constructionQueue.isNullOrEmpty() || draft.pendingDeletion != null || draft.autonomy == null ||
            draft.autonomy.plan != null || draft.buildings.singleOrNull()?.type != BuildingType.TOWN_HALL)
            throw CivsRejection("village-capture-invalid-draft")
        CivsRules.validate(draft)
        if (templates.keys != CivsAutonomy.profile.toSet() || templates.size > BuildingType.entries.size ||
            templates.any { (type, pair) -> pair.first.type != type || pair.second.type != type ||
                pair.first.id != pair.second.id || pair.first.level !in 1..type.maxLevel ||
                pair.second.level !in pair.first.level..type.maxLevel })
            throw CivsRejection("village-capture-invalid-templates")
        if (pluginVersion.length !in 1..64 || partial.plots.size > CivsRules.MAX_BUILDINGS + CivsRules.MAX_AUTONOMOUS_DECORATIONS ||
            partial.routes.size > CivsRules.MAX_BUILDINGS + CivsRules.MAX_INFRASTRUCTURE ||
            partial.routes.values.any { it.size > CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH })
            throw CivsRejection("village-capture-invalid-plan")
        val rejection = CivsDiagnostics.rejection(failure)
        val record = CivVillageSearchCaptureRecord(1, operation, reference, draft.center, draft.style,
            pluginVersion, clock().toString(), draft, "native-survey-with-prepared-hall", surface.copy(columns = surface.columns.toMap()),
            templates.mapValues { (_, pair) -> CivVillageSearchTemplate(pair.first.copy(transit = pair.first.transit?.toList()),
                pair.second.copy(transit = pair.second.transit?.toList())) },
            checkNotNull(CivsSettlementPlans.detached(partial)),
            rejection?.reason?.take(128) ?: "village-search-failed",
            CivsVillageSites.rejectionDetails(rejection?.values.orEmpty()))
        val json = BoundedJsonWriter().also { gson.toJson(record, it) }.toString().toByteArray(Charsets.UTF_8)
        if (json.size > MAXIMUM_BYTES) throw CivsRejection("village-capture-too-large")
        prepareDirectory()
        AtomicFileStore(root, path, MAXIMUM_BYTES.toLong(), { value: ByteArray -> value }, { it }).write(json)
    }

    private fun prepareDirectory() {
        Files.createDirectories(root)
        var directory = root.toRealPath()
        for (name in listOf("debug", "village-search")) {
            directory = directory.resolve(name)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                throw CivsRejection("village-capture-unsafe-path")
        }
    }

    private class BoundedJsonWriter : Writer() {
        private val value = StringBuilder()
        private fun admit(length: Int) {
            if (length.toLong() > MAXIMUM_BYTES - value.length)
                throw CivsRejection("village-capture-too-large")
        }
        override fun write(chars: CharArray, offset: Int, length: Int) { admit(length); value.append(chars, offset, length) }
        override fun write(text: String, offset: Int, length: Int) { admit(length); value.append(text, offset, offset + length) }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = value.toString()
    }

    companion object {
        const val MAXIMUM_COLUMNS = 65_536
        const val MAXIMUM_BYTES = 16 * 1024 * 1024
        private val gson = Gson()
    }
}

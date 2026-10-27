package ru.arc.civs

import java.util.UUID

enum class CivResearchId { MATERIALS, METALLURGY, GLASSWORK, ENGINEERING }
enum class CivIndustryProduct { RAW_IRON, CHARCOAL, GRAVEL, SAND, GLASS, IRON_INGOT, IRON_TOOL_KIT }

/** Distinct currencies. Project acceptance freezes the exact bill in its original city mode. */
data class CivResearchCost(
    val stock: Stock = Stock(),
    val products: Map<CivIndustryProduct, Long> = emptyMap(),
    val materials: Map<String, Long> = emptyMap(),
)

data class CivResearchProject(
    val id: UUID,
    val research: CivResearchId,
    val cost: CivResearchCost,
    val workRequired: Int,
    val workDone: Int = 0,
    val funded: Boolean = false,
    val paused: Boolean = false,
    val termsVersion: Int = 1,
)

data class CivResearchState(val completed: Set<CivResearchId> = emptySet(), val project: CivResearchProject? = null)

data class CivResearchOffer(val research: CivResearchId, val cost: CivResearchCost, val workRequired: Int,
    val available: Boolean, val reason: String?)
data class CivResearchView(val cityId: UUID, val autonomous: Boolean, val completed: Set<CivResearchId>,
    val project: CivResearchProject?, val universityId: UUID?, val offers: List<CivResearchOffer>)
data class CivIndustryRecipeView(val id: String, val inputStock: Stock, val inputProducts: Map<CivIndustryProduct, Long>,
    val inputs: Map<String, Long>, val output: String, val outputAmount: Long)
data class CivIndustryBuildingView(val buildingId: UUID, val type: BuildingType, val production: String?,
    val choices: List<String>, val activeRecipe: String?, val waitingReason: String?,
    val recipes: List<CivIndustryRecipeView> = emptyList())
data class CivIndustryView(val cityId: UUID, val autonomous: Boolean, val products: Map<CivIndustryProduct, Long>,
    val buildings: List<CivIndustryBuildingView>)

/** Four fixed discoveries; no dynamic graph or wall-clock research scheduler. */
object CivsResearch {
    private const val CURRENT_TERMS_VERSION = 1
    fun sessions(id: CivResearchId): Int = sessionsV1(id)

    private fun sessionsV1(id: CivResearchId): Int = when (id) {
        CivResearchId.MATERIALS -> 3
        CivResearchId.METALLURGY, CivResearchId.GLASSWORK -> 5
        CivResearchId.ENGINEERING -> 8
    }

    fun cost(city: Settlement, id: CivResearchId): CivResearchCost = costV1(city.autonomy != null, id)

    /** Supported historical terms are immutable when a future catalog introduces another version. */
    private fun costV1(autonomous: Boolean, id: CivResearchId): CivResearchCost = if (autonomous) when (id) {
        CivResearchId.MATERIALS -> CivResearchCost(stock = Stock(24, 16, 4))
        CivResearchId.METALLURGY -> CivResearchCost(stock = Stock(8, 0, 2),
            products = mapOf(CivIndustryProduct.RAW_IRON to 8L, CivIndustryProduct.CHARCOAL to 2L))
        CivResearchId.GLASSWORK -> CivResearchCost(stock = Stock(8, 0, 2),
            products = mapOf(CivIndustryProduct.SAND to 8L, CivIndustryProduct.CHARCOAL to 2L))
        CivResearchId.ENGINEERING -> CivResearchCost(stock = Stock(16, 16, 4),
            products = mapOf(CivIndustryProduct.IRON_INGOT to 8L, CivIndustryProduct.GLASS to 8L, CivIndustryProduct.IRON_TOOL_KIT to 1L))
    } else CivResearchCost(materials = when (id) {
        CivResearchId.MATERIALS -> mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L)
        CivResearchId.METALLURGY -> mapOf("BOOK" to 1L, "RAW_IRON" to 8L, "CHARCOAL" to 2L)
        CivResearchId.GLASSWORK -> mapOf("BOOK" to 1L, "SAND" to 8L, "CHARCOAL" to 2L)
        CivResearchId.ENGINEERING -> mapOf("BOOK" to 2L, "IRON_INGOT" to 8L, "GLASS" to 8L)
    })

    fun view(city: Settlement) = CivResearchView(city.id, city.autonomy != null,
        city.research?.completed.orEmpty().toSet(), detached(city.research)?.project, university(city)?.id,
        CivResearchId.entries.map { id -> CivResearchOffer(id, cost(city, id), sessions(id), reason(city, id) == null, reason(city, id)) })
    fun prerequisites(id: CivResearchId): Set<CivResearchId> = when (id) {
        CivResearchId.MATERIALS -> emptySet()
        CivResearchId.METALLURGY, CivResearchId.GLASSWORK -> setOf(CivResearchId.MATERIALS)
        CivResearchId.ENGINEERING -> setOf(CivResearchId.METALLURGY, CivResearchId.GLASSWORK)
    }

    fun has(city: Settlement, research: CivResearchId): Boolean = research in city.research?.completed.orEmpty()

    /** Only the new processors are gated; every previously accepted building keeps its contract. */
    fun required(type: BuildingType, level: Int): CivResearchId? = when (type) {
        BuildingType.SMELTERY, BuildingType.CRUSHER ->
            if (level >= 3) CivResearchId.ENGINEERING else CivResearchId.MATERIALS
        else -> null
    }

    internal fun university(city: Settlement): CivBuilding? {
        val available = city.buildings.filter { it.type == BuildingType.UNIVERSITY && it.phase == BuildingPhase.ACTIVE }
        return available.filter { building -> city.workers.any { it.assignment == building.id } }.minByOrNull { it.id.toString() }
            ?: available.minByOrNull { it.id.toString() }
    }

    internal fun reason(city: Settlement, id: CivResearchId): String? = when {
        has(city, id) -> "research-completed"
        city.research?.project != null -> "research-busy"
        university(city) == null -> "university-required"
        id == CivResearchId.ENGINEERING && requireNotNull(university(city)).level < 2 -> "university-level-required"
        !prerequisites(id).all { has(city, it) } -> "research-prerequisites"
        id == CivResearchId.MATERIALS && listOf(BuildingType.FORESTER, BuildingType.QUARRY).any { type ->
            city.buildings.none { it.type == type && it.phase == BuildingPhase.ACTIVE }
        } -> "research-prerequisites"
        else -> null
    }

    internal fun inputs(city: Settlement, building: CivBuilding): Map<String, Long> =
        city.research?.project?.takeIf { city.autonomy == null && !it.funded && !it.paused &&
            building.id == university(city)?.id }?.cost?.materials.orEmpty()

    internal fun begin(city: Settlement, research: CivResearchId, projectId: UUID, now: Long): Settlement {
        available(city)
        reason(city, research)?.let { throw CivsRejection(it) }
        val project = CivResearchProject(projectId, research, cost(city, research), sessions(research), termsVersion = CURRENT_TERMS_VERSION)
        return revise(city, city.copy(research = CivResearchState(city.research?.completed.orEmpty(), project),
            workers = invalidate(city, now)))
    }

    internal fun setPaused(city: Settlement, projectId: UUID, paused: Boolean, now: Long): Settlement {
        available(city)
        val project = project(city, projectId)
        if (project.paused == paused) return city
        return revise(city, city.copy(research = requireNotNull(city.research).copy(project = project.copy(paused = paused)),
            workers = invalidate(city, now)))
    }

    internal fun canFund(city: Settlement): Boolean {
        val project = city.research?.project ?: return false
        return !project.funded && !project.paused && city.autonomy?.paused != true && university(city) != null &&
            (city.autonomy == null || city.stock.covers(project.cost.stock) &&
                project.cost.products.all { (product, amount) -> CivsResearch.product(city, product) >= amount })
    }

    /** Native callers must carry their existing exact inventory receipt; Auto never issues native items. */
    internal fun fund(city: Settlement, projectId: UUID, now: Long, nativePaid: Boolean = false): Settlement {
        available(city)
        val project = project(city, projectId)
        if (project.funded) return city
        requireState(canFund(city), if (project.paused) "research-paused" else "research-unfunded")
        requireState(city.autonomy != null || nativePaid, "materials-required")
        val autonomy = city.autonomy?.let { auto ->
            val products = auto.products.orEmpty().toMutableMap()
            project.cost.products.forEach { (product, count) ->
                val remaining = (products[product] ?: 0L) - count
                if (remaining == 0L) products.remove(product) else products[product] = remaining
            }
            auto.copy(products = products.takeIf { it.isNotEmpty() })
        }
        return revise(city, city.copy(stock = if (autonomy == null) city.stock else city.stock - project.cost.stock,
            autonomy = autonomy, research = requireNotNull(city.research).copy(project = project.copy(funded = true)),
            workers = invalidate(city, now)))
    }

    /** One observed actor session, its cycle and training are published by one city revision. */
    internal fun complete(city: Settlement, workerId: UUID, cycle: Long, nextAt: Long, projectId: UUID, now: Long): Settlement {
        available(city)
        val project = project(city, projectId)
        requireState(project.funded, "research-unfunded")
        requireState(!project.paused && city.autonomy?.paused != true, "research-paused")
        val university = university(city) ?: throw CivsRejection("university-required")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        requireState(worker.assignment == university.id && worker.cargo == null && !CivsResidents.isChild(worker) &&
            worker.completedCycles == cycle && worker.nextDeliveryAt == nextAt && now >= nextAt, "stale-delivery")
        val updated = CivsResidents.trained(worker.copy(completedCycles = Math.addExact(cycle, 1),
            nextDeliveryAt = Math.addExact(now, CivsResidents.workDuration(worker, CivsRules.PRODUCTION_INTERVAL))), BuildingType.UNIVERSITY, city)
        val done = project.workDone + 1
        val research = requireNotNull(city.research).let { state ->
            if (done == project.workRequired) state.copy(completed = state.completed + project.research, project = null)
            else state.copy(project = project.copy(workDone = done))
        }
        return revise(city, city.copy(research = research, workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    internal fun project(city: Settlement, projectId: UUID): CivResearchProject = city.research?.project
        ?.takeIf { it.id == projectId } ?: throw CivsRejection("stale-research")

    internal fun product(city: Settlement, product: CivIndustryProduct): Long = city.autonomy?.products?.get(product) ?: 0L

    private fun invalidate(city: Settlement, now: Long): List<CivWorker> = city.workers.map { worker ->
        if (worker.assignment?.let(city::building)?.type == BuildingType.UNIVERSITY) worker.copy(
            nextDeliveryAt = maxOf(Math.addExact(worker.nextDeliveryAt, 1), Math.addExact(now, CivsRules.PRODUCTION_INTERVAL))) else worker
    }

    private fun available(city: Settlement) {
        CivsRules.validate(city)
        requireState(city.pendingDeletion == null, "deletion-pending")
    }

    private fun requireState(value: Boolean, reason: String) { if (!value) throw CivsRejection(reason) }
    private fun revise(before: Settlement, after: Settlement): Settlement =
        CivsRules.validate(after.copy(revision = Math.addExact(before.revision, 1)))

    internal fun detached(state: CivResearchState?): CivResearchState? = state?.copy(completed = state.completed.toSet(),
        project = state.project?.let { it.copy(cost = it.cost.copy(products = it.cost.products.toMap(), materials = it.cost.materials.toMap())) })

    internal fun validate(city: Settlement) {
        city.autonomy?.products?.let { products ->
            require(products.size <= CivIndustryProduct.entries.size && products.all { (key, amount) -> key != null && amount in 1..64 })
        }
        val state = city.research ?: return
        require(state.completed.size <= CivResearchId.entries.size && state.completed.all { it != null })
        require(state.completed.all { prerequisites(it).all(state.completed::contains) })
        state.project?.let { project ->
            require(project.id != null && project.research != null && project.research !in state.completed)
            require(prerequisites(project.research).all(state.completed::contains))
            require(project.workRequired in 1..64 && project.workDone in 0 until project.workRequired)
            require(project.funded || project.workDone == 0)
            val cost = project.cost
            require(project.termsVersion == 1 && cost == costV1(city.autonomy != null, project.research) &&
                project.workRequired == sessionsV1(project.research))
            require(cost.stock.wood in 0..CivInventory.MAX_AMOUNT && cost.stock.stone in 0..CivInventory.MAX_AMOUNT &&
                cost.stock.food in 0..CivInventory.MAX_AMOUNT)
            require(cost.products.size <= CivIndustryProduct.entries.size && cost.products.all { (key, amount) -> key != null && amount in 1..64 })
            CivInventory(CivStorageMode.VIRTUAL, cost.materials).validated()
            require(if (city.autonomy == null) cost.stock == Stock() && cost.products.isEmpty() else cost.materials.isEmpty())
        }
    }
}

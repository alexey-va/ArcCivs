package ru.arc.civs

import java.util.UUID

enum class CivResearchId {
    MATERIALS, METALLURGY, GLASSWORK, ENGINEERING,
    BUILDING_METHODS_I, BUILDING_METHODS_II, BUILDING_METHODS_III,
    FURNACE_PRACTICE_I, FURNACE_PRACTICE_II, FURNACE_PRACTICE_III,
    COMMUNAL_MEALS_I, COMMUNAL_MEALS_II, COMMUNAL_MEALS_III,
    CITY_WATCH_I, CITY_WATCH_II, CITY_WATCH_III,
}

enum class CivResearchBranch { INDUSTRY, BUILDING_METHODS, FURNACE_PRACTICE, COMMUNAL_MEALS, CITY_WATCH }

data class CivResearchEffect(val builderSessionRows: Int? = null, val smelteryWorkSeconds: Int? = null,
    val breadNutrition: Int? = null, val regenerationHunger: Int? = null, val patrolRadius: Int? = null)
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

/** Fixed paid discoveries; all progress remains observed actor work, never elapsed or offline time. */
object CivsResearch {
    private data class Extension(val branch: CivResearchBranch, val tier: Int,
        val buildings: Map<BuildingType, Int>, val prerequisites: Set<CivResearchId>,
        val materials: Map<String, Long>, val effect: CivResearchEffect)

    private val extensions = mapOf(
        CivResearchId.BUILDING_METHODS_I to Extension(CivResearchBranch.BUILDING_METHODS, 1,
            mapOf(BuildingType.BUILDER_HUT to 1, BuildingType.QUARRY to 1), setOf(CivResearchId.MATERIALS),
            mapOf("COBBLESTONE" to 64L, "BREAD" to 8L), CivResearchEffect(builderSessionRows = 80)),
        CivResearchId.BUILDING_METHODS_II to Extension(CivResearchBranch.BUILDING_METHODS, 2,
            mapOf(BuildingType.BUILDER_HUT to 2, BuildingType.WAREHOUSE to 2), setOf(CivResearchId.BUILDING_METHODS_I, CivResearchId.METALLURGY),
            mapOf("COBBLESTONE" to 128L, "IRON_INGOT" to 8L), CivResearchEffect(builderSessionRows = 96)),
        CivResearchId.BUILDING_METHODS_III to Extension(CivResearchBranch.BUILDING_METHODS, 3,
            mapOf(BuildingType.BUILDER_HUT to 2, BuildingType.QUARRY to 2), setOf(CivResearchId.BUILDING_METHODS_II, CivResearchId.ENGINEERING),
            mapOf("COBBLESTONE" to 256L, "IRON_INGOT" to 16L, "GLASS" to 16L), CivResearchEffect(builderSessionRows = 128)),
        CivResearchId.FURNACE_PRACTICE_I to Extension(CivResearchBranch.FURNACE_PRACTICE, 1,
            mapOf(BuildingType.SMELTERY to 1), setOf(CivResearchId.METALLURGY),
            mapOf("RAW_IRON" to 8L, "CHARCOAL" to 8L), CivResearchEffect(smelteryWorkSeconds = 54)),
        CivResearchId.FURNACE_PRACTICE_II to Extension(CivResearchBranch.FURNACE_PRACTICE, 2,
            mapOf(BuildingType.SMELTERY to 2, BuildingType.CRUSHER to 2), setOf(CivResearchId.FURNACE_PRACTICE_I, CivResearchId.GLASSWORK),
            mapOf("RAW_IRON" to 16L, "CHARCOAL" to 16L, "GLASS" to 8L), CivResearchEffect(smelteryWorkSeconds = 48)),
        CivResearchId.FURNACE_PRACTICE_III to Extension(CivResearchBranch.FURNACE_PRACTICE, 3,
            mapOf(BuildingType.SMELTERY to 3), setOf(CivResearchId.FURNACE_PRACTICE_II, CivResearchId.ENGINEERING),
            mapOf("RAW_IRON" to 32L, "CHARCOAL" to 32L, "GLASS" to 16L), CivResearchEffect(smelteryWorkSeconds = 42)),
        CivResearchId.COMMUNAL_MEALS_I to Extension(CivResearchBranch.COMMUNAL_MEALS, 1,
            mapOf(BuildingType.FARM to 1, BuildingType.TAVERN to 1), emptySet(),
            mapOf("BREAD" to 16L, "COBBLESTONE" to 32L), CivResearchEffect(breadNutrition = 7)),
        CivResearchId.COMMUNAL_MEALS_II to Extension(CivResearchBranch.COMMUNAL_MEALS, 2,
            mapOf(BuildingType.FARM to 2, BuildingType.TAVERN to 2), setOf(CivResearchId.COMMUNAL_MEALS_I, CivResearchId.GLASSWORK),
            mapOf("BREAD" to 32L, "COBBLESTONE" to 64L, "GLASS" to 8L), CivResearchEffect(breadNutrition = 8)),
        CivResearchId.COMMUNAL_MEALS_III to Extension(CivResearchBranch.COMMUNAL_MEALS, 3,
            mapOf(BuildingType.TAVERN to 2, BuildingType.HOUSE to 2), setOf(CivResearchId.COMMUNAL_MEALS_II, CivResearchId.ENGINEERING),
            mapOf("BREAD" to 64L, "COBBLESTONE" to 128L, "GLASS" to 16L), CivResearchEffect(breadNutrition = 8, regenerationHunger = 16)),
        CivResearchId.CITY_WATCH_I to Extension(CivResearchBranch.CITY_WATCH, 1,
            mapOf(BuildingType.GUARD_TOWER to 1), setOf(CivResearchId.METALLURGY),
            mapOf("IRON_INGOT" to 8L, "COBBLESTONE" to 64L), CivResearchEffect(patrolRadius = 40)),
        CivResearchId.CITY_WATCH_II to Extension(CivResearchBranch.CITY_WATCH, 2,
            mapOf(BuildingType.GUARD_TOWER to 2, BuildingType.GATE to 1), setOf(CivResearchId.CITY_WATCH_I, CivResearchId.GLASSWORK),
            mapOf("IRON_INGOT" to 16L, "COBBLESTONE" to 128L, "GLASS" to 8L), CivResearchEffect(patrolRadius = 48)),
        CivResearchId.CITY_WATCH_III to Extension(CivResearchBranch.CITY_WATCH, 3,
            mapOf(BuildingType.GUARD_TOWER to 2), setOf(CivResearchId.CITY_WATCH_II, CivResearchId.ENGINEERING),
            mapOf("IRON_INGOT" to 32L, "COBBLESTONE" to 256L, "GLASS" to 16L), CivResearchEffect(patrolRadius = 64)),
    )

    fun branch(id: CivResearchId): CivResearchBranch = extensions[id]?.branch ?: CivResearchBranch.INDUSTRY
    fun tier(id: CivResearchId): Int = extensions[id]?.tier ?: when (id) {
        CivResearchId.MATERIALS -> 1
        CivResearchId.METALLURGY, CivResearchId.GLASSWORK -> 2
        else -> 3
    }
    fun universityLevel(id: CivResearchId): Int = extensions[id]?.tier ?: if (id == CivResearchId.ENGINEERING) 2 else 1
    fun buildingRequirements(id: CivResearchId): Map<BuildingType, Int> = extensions[id]?.buildings?.toMap() ?:
        if (id == CivResearchId.MATERIALS) mapOf(BuildingType.FORESTER to 1, BuildingType.QUARRY to 1) else emptyMap()
    fun effect(id: CivResearchId): CivResearchEffect = extensions[id]?.effect ?: CivResearchEffect()
    fun extended(id: CivResearchId): Boolean = id in extensions
    fun sessions(id: CivResearchId): Int = extensions[id]?.let { listOf(30, 60, 120)[it.tier - 1] } ?: sessionsV1(id)

    private fun completedEffect(city: Settlement, branch: CivResearchBranch): CivResearchEffect? = city.research?.completed.orEmpty()
        .mapNotNull { extensions[it] }.filter { it.branch == branch }.maxByOrNull { it.tier }?.effect
    fun builderSessionRows(city: Settlement): Int = completedEffect(city, CivResearchBranch.BUILDING_METHODS)?.builderSessionRows ?: 64
    fun smelteryWorkMillis(city: Settlement): Long = (completedEffect(city, CivResearchBranch.FURNACE_PRACTICE)?.smelteryWorkSeconds ?: 60) * 1_000L
    fun breadNutrition(city: Settlement): Int? = completedEffect(city, CivResearchBranch.COMMUNAL_MEALS)?.breadNutrition
    fun regenerationHunger(city: Settlement): Int = completedEffect(city, CivResearchBranch.COMMUNAL_MEALS)?.regenerationHunger ?: 18
    fun patrolRadius(city: Settlement): Int = completedEffect(city, CivResearchBranch.CITY_WATCH)?.patrolRadius ?: 32

    /** Only the historical projects keep their existing automatic simulation supplies. Courier demand is separate. */
    internal fun automaticGrantAllowed(city: Settlement, buildingId: UUID): Boolean =
        city.building(buildingId)?.type != BuildingType.UNIVERSITY || city.research?.project?.termsVersion !in setOf(3, 4)

    internal fun automatic(city: Settlement, availableMaterials: Map<String, Long>? = null): CivResearchId? {
        CivResearchId.entries.firstOrNull { !extended(it) && reason(city, it) == null }?.let { return it }
        if (availableMaterials == null || CivsAutonomy.legacyStock(city)) return null
        return extensions.keys.sortedWith(compareBy<CivResearchId> { tier(it) }.thenBy { branch(it).ordinal }.thenBy { it.ordinal })
            .firstOrNull { id -> reason(city, id) == null && cost(city, id).materials.all { (material, count) ->
                availableMaterials.getOrDefault(material, 0) >= count
            } }
    }

    private fun sessionsV1(id: CivResearchId): Int = when (id) {
        CivResearchId.MATERIALS -> 3
        CivResearchId.METALLURGY, CivResearchId.GLASSWORK -> 5
        CivResearchId.ENGINEERING -> 8
        else -> throw CivsRejection("invalid-research")
    }

    fun cost(city: Settlement, id: CivResearchId): CivResearchCost = if (extended(id))
        costV3(CivsAutonomy.legacyStock(city), id) else costV1(CivsAutonomy.legacyStock(city), id)

    private fun costV3(legacy: Boolean, id: CivResearchId): CivResearchCost {
        val materials = requireNotNull(extensions[id]).materials
        if (!legacy) return CivResearchCost(materials = materials.toMap())
        return CivResearchCost(stock = Stock(stone = materials["COBBLESTONE"] ?: 0L, food = materials["BREAD"] ?: 0L),
            products = materials.filterKeys { it !in setOf("COBBLESTONE", "BREAD") }
                .mapKeys { CivIndustryProduct.valueOf(it.key) })
    }

    /** Supported historical terms are immutable when a future catalog introduces another version. */
    private fun costV1(autonomous: Boolean, id: CivResearchId): CivResearchCost = if (autonomous) when (id) {
        CivResearchId.MATERIALS -> CivResearchCost(stock = Stock(24, 16, 4))
        CivResearchId.METALLURGY -> CivResearchCost(stock = Stock(8, 0, 2),
            products = mapOf(CivIndustryProduct.RAW_IRON to 8L, CivIndustryProduct.CHARCOAL to 2L))
        CivResearchId.GLASSWORK -> CivResearchCost(stock = Stock(8, 0, 2),
            products = mapOf(CivIndustryProduct.SAND to 8L, CivIndustryProduct.CHARCOAL to 2L))
        CivResearchId.ENGINEERING -> CivResearchCost(stock = Stock(16, 16, 4),
            products = mapOf(CivIndustryProduct.IRON_INGOT to 8L, CivIndustryProduct.GLASS to 8L, CivIndustryProduct.IRON_TOOL_KIT to 1L))
        else -> throw CivsRejection("invalid-research")
    } else CivResearchCost(materials = when (id) {
        CivResearchId.MATERIALS -> mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L)
        CivResearchId.METALLURGY -> mapOf("BOOK" to 1L, "RAW_IRON" to 8L, "CHARCOAL" to 2L)
        CivResearchId.GLASSWORK -> mapOf("BOOK" to 1L, "SAND" to 8L, "CHARCOAL" to 2L)
        CivResearchId.ENGINEERING -> mapOf("BOOK" to 2L, "IRON_INGOT" to 8L, "GLASS" to 8L)
        else -> throw CivsRejection("invalid-research")
    })

    fun view(city: Settlement) = CivResearchView(city.id, city.autonomy != null,
        city.research?.completed.orEmpty().toSet(), detached(city.research)?.project, university(city)?.id,
        CivResearchId.entries.map { id -> CivResearchOffer(id, cost(city, id), sessions(id), reason(city, id) == null, reason(city, id)) })
    fun prerequisites(id: CivResearchId): Set<CivResearchId> = when (id) {
        CivResearchId.MATERIALS -> emptySet()
        CivResearchId.METALLURGY, CivResearchId.GLASSWORK -> setOf(CivResearchId.MATERIALS)
        CivResearchId.ENGINEERING -> setOf(CivResearchId.METALLURGY, CivResearchId.GLASSWORK)
        else -> requireNotNull(extensions[id]).prerequisites.toSet()
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
        requireNotNull(university(city)).level < universityLevel(id) -> "university-level-required"
        !prerequisites(id).all { has(city, it) } -> "research-prerequisites"
        buildingRequirements(id).any { (type, level) -> city.buildings.none {
            it.type == type && it.phase == BuildingPhase.ACTIVE && it.level >= level
        } } -> "research-prerequisites"
        else -> null
    }

    internal fun inputs(city: Settlement, building: CivBuilding): Map<String, Long> =
        city.research?.project?.takeIf { !CivsAutonomy.legacyStock(city) && !it.funded && !it.paused &&
            building.id == university(city)?.id }?.cost?.materials.orEmpty()

    internal fun begin(city: Settlement, research: CivResearchId, projectId: UUID, now: Long): Settlement {
        available(city)
        reason(city, research)?.let { throw CivsRejection(it) }
        val project = CivResearchProject(projectId, research, cost(city, research), sessions(research), termsVersion = if (extended(research)) 3 else 1)
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
            (!CivsAutonomy.legacyStock(city) || city.stock.covers(project.cost.stock) &&
                project.cost.products.all { (product, amount) -> CivsResearch.product(city, product) >= amount })
    }

    /** Physical funding carries the existing exact inventory receipt; legacy closed funding remains recoverable. */
    internal fun fund(city: Settlement, projectId: UUID, now: Long, nativePaid: Boolean = false): Settlement {
        available(city)
        val project = project(city, projectId)
        if (project.funded) return city
        requireState(canFund(city), if (project.paused) "research-paused" else "research-unfunded")
        requireState(CivsAutonomy.legacyStock(city) || nativePaid, "materials-required")
        val autonomy = city.autonomy?.let { auto ->
            if (!CivsAutonomy.legacyStock(city)) return@let auto
            val products = auto.products.orEmpty().toMutableMap()
            project.cost.products.forEach { (product, count) ->
                val remaining = (products[product] ?: 0L) - count
                if (remaining == 0L) products.remove(product) else products[product] = remaining
            }
            auto.copy(products = products.takeIf { it.isNotEmpty() })
        }
        return revise(city, city.copy(stock = if (!CivsAutonomy.legacyStock(city)) city.stock else city.stock - project.cost.stock,
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
            require(project.workRequired in 1..120 && project.workDone in 0 until project.workRequired)
            require(project.funded || project.workDone == 0)
            val cost = project.cost
            val native = !CivsAutonomy.legacyStock(city)
            if (extended(project.research)) {
                val migrated = city.autonomy != null && native && project.termsVersion == 4 &&
                    cost == CivsAutoStorageMigration.nativeCost(costV3(true, project.research))
                require((project.termsVersion == 3 && cost == costV3(!native, project.research) || migrated) &&
                    project.workRequired == sessions(project.research))
            } else {
                val expected = costV1(!native, project.research)
                val migrated = city.autonomy != null && native && project.termsVersion == 2 &&
                    cost == CivsAutoStorageMigration.nativeCost(costV1(true, project.research))
                require((project.termsVersion == 1 && cost == expected || migrated) &&
                    project.workRequired == sessionsV1(project.research))
            }
            require(cost.stock.wood in 0..CivInventory.MAX_AMOUNT && cost.stock.stone in 0..CivInventory.MAX_AMOUNT &&
                cost.stock.food in 0..CivInventory.MAX_AMOUNT)
            require(cost.products.size <= CivIndustryProduct.entries.size && cost.products.all { (key, amount) -> key != null && amount in 1..64 })
            CivInventory(CivStorageMode.VIRTUAL, cost.materials).validated()
            require(if (!CivsAutonomy.legacyStock(city)) cost.stock == Stock() && cost.products.isEmpty() else cost.materials.isEmpty())
        }
    }
}

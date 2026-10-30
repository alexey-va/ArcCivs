package ru.arc.civs

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only presentation of accepted batches; displaying it never starts a capture. */
data class CivCarpentryView(val level: Int, val paused: Boolean, val planning: Boolean,
    val orders: List<CivCarpentryOrderView>, val recipes: List<CivCarpentryRecipeView>)
data class CivCarpentryOrderView(val workerId: UUID, val workerName: String, val material: String,
    val amount: Long, val inputs: Map<String, Long>, val missing: Map<String, Long>,
    val demandMaterial: String, val targetBuildingId: UUID?, val status: String)
data class CivCarpentryRecipeView(val material: String, val amount: Long, val level: Int,
    val inputs: Map<String, Long>, val stripping: Boolean = false)

/** One immutable native-sized batch. Inventory receipts, not this temporary intent, own items. */
data class CivCraftJob(val id: UUID, val workerId: UUID, val buildingId: UUID, val cycle: Long,
    val recipeId: String, val version: Int, val material: String, val amount: Long,
    val inputs: Map<String, Long>, val demandMaterial: String, val targetBuildingId: UUID,
    val constructionId: UUID? = null)

internal data class CivCraftWorker(val workerId: UUID, val buildingId: UUID, val cycle: Long,
    val level: Int, val carried: Map<String, Long>, val axe: Boolean, val type: BuildingType = BuildingType.SAWMILL)
internal data class CivCraftRecipe(val id: String, val material: String, val amount: Long,
    val level: Int, val inputs: Map<String, Long>, val stripping: Boolean = false, val workshop: BuildingType = BuildingType.SAWMILL)
internal data class CivCraftAllocation(val jobs: Map<UUID, CivCraftJob>,
    val required: Map<UUID, Map<String, Long>>, val requests: List<CivSupplyRequest>)

/** Exact Minecraft 1.21.11 recipe batches, checked against Mojang server SHA1
 * 64bb6d763bed0a9f1d632ec347938594144943ed. No price, yield or speed multiplier. */
internal object CivsCarpentry {
    const val VERSION = 1
    val workshops = setOf(BuildingType.SAWMILL, BuildingType.BLACKSMITH)
    val woods = listOf("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK")
    val recipes: List<CivCraftRecipe> = buildList {
        fun recipe(id: String, material: String, amount: Long, level: Int,
            vararg inputs: Pair<String, Long>, stripping: Boolean = false) {
            add(CivCraftRecipe(id, material, amount, level, inputs.toMap(), stripping))
        }
        for (wood in woods) {
            val planks = "${wood}_PLANKS"; val log = "${wood}_LOG"; val slab = "${wood}_SLAB"
            for (source in listOf(log, "${wood}_WOOD", "STRIPPED_$log", "STRIPPED_${wood}_WOOD"))
                recipe("${planks.lowercase()}/${source.lowercase()}", planks, 4, 1, source to 1L)
            recipe("${wood.lowercase()}_wood", "${wood}_WOOD", 3, 1, log to 4L)
            recipe("stripped_${wood.lowercase()}_wood", "STRIPPED_${wood}_WOOD", 3, 1, "STRIPPED_$log" to 4L)
            for (source in listOf(log, "${wood}_WOOD"))
                recipe("strip/${source.lowercase()}", "STRIPPED_$source", 1, 1, source to 1L, stripping = true)
            recipe("${wood.lowercase()}_stairs", "${wood}_STAIRS", 4, 1, planks to 6L)
            recipe("${wood.lowercase()}_slab", slab, 6, 1, planks to 3L)
            recipe("${wood.lowercase()}_fence", "${wood}_FENCE", 3, 1, planks to 4L, "STICK" to 2L)
            recipe("${wood.lowercase()}_fence_gate", "${wood}_FENCE_GATE", 1, 1, planks to 2L, "STICK" to 4L)
            recipe("${wood.lowercase()}_door", "${wood}_DOOR", 3, 1, planks to 6L)
            recipe("${wood.lowercase()}_trapdoor", "${wood}_TRAPDOOR", 2, 1, planks to 6L)
            recipe("${wood.lowercase()}_button", "${wood}_BUTTON", 1, 1, planks to 1L)
            recipe("${wood.lowercase()}_pressure_plate", "${wood}_PRESSURE_PLATE", 1, 1, planks to 2L)
            recipe("${wood.lowercase()}_sign", "${wood}_SIGN", 3, 1, planks to 6L, "STICK" to 1L)
            recipe("stick/${wood.lowercase()}", "STICK", 4, 1, planks to 2L)
            recipe("crafting_table/${wood.lowercase()}", "CRAFTING_TABLE", 1, 1, planks to 4L)
            recipe("chest/${wood.lowercase()}", "CHEST", 1, 2, planks to 8L)
            recipe("barrel/${wood.lowercase()}", "BARREL", 1, 2, planks to 6L, slab to 2L)
            for (coal in listOf("COAL", "CHARCOAL"))
                recipe("campfire/${wood.lowercase()}/${coal.lowercase()}", "CAMPFIRE", 1, 2, log to 3L, "STICK" to 3L, coal to 1L)
            recipe("tripwire_hook/${wood.lowercase()}", "TRIPWIRE_HOOK", 2, 2, planks to 1L, "STICK" to 1L, "IRON_INGOT" to 1L)
            recipe("bookshelf/${wood.lowercase()}", "BOOKSHELF", 1, 3, planks to 6L, "BOOK" to 3L)
            recipe("chiseled_bookshelf/${wood.lowercase()}", "CHISELED_BOOKSHELF", 1, 3, planks to 6L, slab to 3L)
            recipe("lectern/${wood.lowercase()}", "LECTERN", 1, 3, slab to 4L, "BOOKSHELF" to 1L)
            recipe("smithing_table/${wood.lowercase()}", "SMITHING_TABLE", 1, 3, planks to 4L, "IRON_INGOT" to 2L)
        }
        recipe("ladder", "LADDER", 3, 1, "STICK" to 7L)
        recipe("scaffolding", "SCAFFOLDING", 6, 2, "BAMBOO" to 6L, "STRING" to 1L)
        for (tool in listOf("IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE")) {
            val iron = when (tool) { "IRON_SHOVEL" -> 1L; "IRON_HOE" -> 2L; else -> 3L }
            add(CivCraftRecipe("blacksmith/${tool.lowercase()}", tool, 1, 1,
                mapOf("IRON_INGOT" to iron, "STICK" to 2L), workshop = BuildingType.BLACKSMITH))
        }
    }
    private val byId = recipes.associateBy(CivCraftRecipe::id)
    private val byMaterial = recipes.groupBy(CivCraftRecipe::material)
    // Flattened native item tags from the same pinned 1.21.11 server data.
    private val taggedWoods = woods + listOf("BAMBOO", "CHERRY", "CRIMSON", "MANGROVE", "PALE_OAK", "WARPED")
    private val planks = taggedWoods.mapTo(linkedSetOf()) { "${it}_PLANKS" }
    private val slabs = taggedWoods.mapTo(linkedSetOf()) { "${it}_SLAB" }
    private val logs = (woods + listOf("CHERRY", "MANGROVE", "PALE_OAK")).flatMap {
        listOf("${it}_LOG", "${it}_WOOD", "STRIPPED_${it}_LOG", "STRIPPED_${it}_WOOD")
    }.toSet() + listOf("CRIMSON", "WARPED").flatMap {
        listOf("${it}_STEM", "${it}_HYPHAE", "STRIPPED_${it}_STEM", "STRIPPED_${it}_HYPHAE")
    }
    internal fun timberFamily(material: String): Boolean = material in logs
    internal fun timberSpecies(material: String): String? = taggedWoods.firstOrNull {
        material.removePrefix("STRIPPED_").startsWith("${it}_")
    }

    private val generic = setOf("STICK", "CRAFTING_TABLE", "CHEST", "BARREL", "CAMPFIRE", "TRIPWIRE_HOOK",
        "BOOKSHELF", "CHISELED_BOOKSHELF", "LECTERN", "SMITHING_TABLE")
    private fun group(recipe: CivCraftRecipe, input: String): Set<String> = when {
        recipe.material !in generic -> setOf(input)
        input.endsWith("_PLANKS") -> planks
        input.endsWith("_SLAB") -> slabs
        recipe.material == "CAMPFIRE" && input.endsWith("_LOG") -> logs
        recipe.material == "CAMPFIRE" && input in setOf("COAL", "CHARCOAL") -> setOf("COAL", "CHARCOAL")
        else -> setOf(input)
    }
    val materials: Set<String> = recipes.flatMapTo(linkedSetOf()) { recipe ->
        recipe.inputs.keys.flatMap { group(recipe, it) } + recipe.material
    }
    val views: List<CivCarpentryRecipeView> = views(BuildingType.SAWMILL)
    fun views(workshop: BuildingType): List<CivCarpentryRecipeView> = recipes.filter { it.workshop == workshop }.distinctBy(CivCraftRecipe::material)
        .map { CivCarpentryRecipeView(it.material, it.amount, it.level, it.inputs, it.stripping) }
        .sortedWith(compareBy<CivCarpentryRecipeView> { it.level }.thenBy { it.material })

    init {
        check(byId.size == recipes.size && recipes.size <= 256)
        fun visit(material: String, path: Set<String>) {
            check(material !in path) { "Cyclic carpenter recipe: $material" }
            byMaterial[material].orEmpty().flatMap { it.inputs.keys }.distinct().forEach { visit(it, path + material) }
        }
        byMaterial.keys.forEach { visit(it, emptySet()) }
    }

    fun recipe(job: CivCraftJob): CivCraftRecipe? = byId[job.recipeId]?.takeIf { recipe ->
        job.version == VERSION && job.material == recipe.material && job.amount == recipe.amount &&
            job.inputs.isNotEmpty() && job.inputs.values.all { it in 1..64 } &&
            job.inputs.keys.all { material -> recipe.inputs.keys.any { material in group(recipe, it) } } &&
            recipe.inputs.all { (input, count) -> job.inputs.filterKeys { it in group(recipe, input) }.values.sum() == count }
    }?.copy(inputs = job.inputs)

    /** Resolve native tagged slots once, then pin exact plain materials in the job. */
    private fun resolve(recipe: CivCraftRecipe, actual: Map<String, Long>, promised: Map<String, Long>,
        cost: (String, Long) -> Long): CivCraftRecipe {
        val inputs = linkedMapOf<String, Long>()
        for ((canonical, count) in recipe.inputs) {
            val options = group(recipe, canonical).sortedWith(compareBy<String> { it != canonical }.thenBy { it })
            var remaining = count
            for (material in options) {
                val used = minOf(remaining, (actual[material] ?: 0L) + (promised[material] ?: 0L))
                if (used > 0) { inputs[material] = used; remaining -= used }
            }
            if (remaining > 0) {
                val selected = options.minWith(compareBy<String> { cost(it, remaining) }.thenBy { it != canonical }.thenBy { it })
                inputs.merge(selected, remaining, Math::addExact)
            }
        }
        return recipe.copy(inputs = inputs)
    }

    fun valid(city: Settlement, worker: CivWorker, job: CivCraftJob): Boolean {
        val building = city.building(job.buildingId) ?: return false
        val recipe = recipe(job) ?: return false
        return city.pendingDeletion == null && city.inventory != null && job.workerId == worker.id &&
            worker.assignment == building.id && worker.completedCycles == job.cycle && worker.cargo == null &&
            !CivsResidents.isChild(worker) && building.type == recipe.workshop &&
            CivsRules.productionAvailable(city, building) && recipe.level <= building.level &&
            (!recipe.stripping || CivsEquipment.slots(worker)[4]?.material?.endsWith("_AXE") == true) &&
            (job.constructionId == null || city.construction?.id == job.constructionId)
    }

    /** A single shared buffer, below real requests. Native batches and level unlocks still apply. */
    fun stockTargets(city: Settlement): Map<UUID, Map<String, Long>> {
        if (city.inventory == null || city.pendingDeletion != null) return emptyMap()
        val workshops = city.buildings.filter { it.type == BuildingType.SAWMILL && it.control != null &&
            CivsRules.productionAvailable(city, it) && city.workers.any { worker ->
                worker.assignment == it.id && worker.cargo == null && !CivsResidents.isChild(worker) } }
        val level = workshops.maxOfOrNull { it.level } ?: return emptyMap()
        val warehouse = city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE &&
            it.control != null && city.workers.any { worker -> worker.assignment == it.id && !CivsResidents.isChild(worker) } }
            .minByOrNull { it.id.toString() } ?: return emptyMap()
        val wood = when (city.style) { "nordic", "caledonia" -> "SPRUCE"; else -> "OAK" }
        val desired = linkedMapOf("${wood}_PLANKS" to 32L, "STICK" to 16L, "LADDER" to 12L,
            "${wood}_SLAB" to 12L, "${wood}_STAIRS" to 8L, "${wood}_FENCE" to 6L,
            "${wood}_FENCE_GATE" to 2L, "${wood}_DOOR" to 3L, "${wood}_TRAPDOOR" to 2L)
        if (level >= 2) desired += mapOf("CHEST" to 2L, "BARREL" to 2L)
        return mapOf(warehouse.id to desired)
    }

    /** One capture, one finite catalog, at most one paid batch per resident. Intermediate
     * outputs are real later receipts, never virtual ingredients of a collapsed recipe. */
    fun allocate(snapshot: CivSupplySnapshot, cancelled: AtomicBoolean): CivCraftAllocation {
        if (snapshot.craftWorkers.isEmpty()) return CivCraftAllocation(emptyMap(), emptyMap(), emptyList())
        require(snapshot.craftWorkers.size <= 48 && snapshot.requests.size <= 2_048)
        val workers = snapshot.craftWorkers.associateBy(CivCraftWorker::workerId)
        val roots = snapshot.requests.filter { it.needed > it.inTransit && it.material in byMaterial }
            .sortedWith(compareByDescending<CivSupplyRequest> { it.construction }.thenBy { it.buildingId.toString() }.thenBy { it.material }) + snapshot.stockTargets.toSortedMap(compareBy(UUID::toString)).flatMap { (target, desired) ->
                desired.map { (material, count) -> CivSupplyRequest(target, material, count, 0) }
            }
        val stock = linkedMapOf<String, Long>()
        fun add(target: MutableMap<String, Long>, material: String, amount: Long) {
            if (amount > 0) target[material] = Math.addExact(target[material] ?: 0, amount)
        }
        // Consumer reserves were already subtracted by requests(); do not count them twice.
        snapshot.stores.values.filter { it.type == BuildingType.WAREHOUSE || it.outputMaterial != null }.forEach { store ->
            store.stock.forEach { (material, count) -> add(stock, material, (count - (store.desiredStock[material] ?: 0)).coerceAtLeast(0)) }
        }
        snapshot.workerSources.filter { it.workerId !in workers }.forEach { source ->
            source.excess.forEach { (material, count) -> add(stock, material, count) }
        }
        workers.values.forEach { it.carried.forEach { (material, count) -> if (material in materials) add(stock, material, count) } }
        // Inputs in a durable workshop-bound shipment still belong to their pinned batch.
        // Readiness remains a separate check of the actual barrel/resident inventory.
        snapshot.retained.values.filter { plan -> plan.carrying && snapshot.stores[plan.destinationBuildingId]?.type.let {
            it == BuildingType.WAREHOUSE || it in workshops
        } }
            .distinctBy { it.cargo.id }.forEach { add(stock, it.cargo.material, it.cargo.amount) }
        val jobs = linkedMapOf<UUID, CivCraftJob>()
        fun outputsFit(batches: Collection<CivCraftJob>): Boolean {
            val rooms = snapshot.stores.mapValues { CivSupplyRoom(it.value.capacity, snapshot.stackSizes) }
            val picked = linkedMapOf<UUID, MutableMap<String, Long>>()
            batches.forEach { job -> job.inputs.forEach { (material, count) ->
                val carried = workers[job.workerId]?.carried?.get(material) ?: 0L
                add(picked.getOrPut(job.buildingId, ::linkedMapOf), material, (count - carried).coerceAtLeast(0))
            } }
            picked.forEach { (id, inputs) -> inputs.forEach { (material, count) ->
                val stockCount = snapshot.stores[id]?.stock?.get(material) ?: 0L
                if (stockCount > 0 && count >= stockCount) rooms[id]?.removeAll(material, stockCount)
            } }
            // A durable shipment already owns its destination. Exact batch ingredients
            // disappear in the required pickup before the output is inserted.
            snapshot.retained.values.filter { it.carrying }.distinctBy { it.cargo.id }.forEach { plan ->
                val cargo = plan.cargo
                if ((picked[plan.destinationBuildingId]?.get(cargo.material) ?: 0L) < cargo.amount) {
                    val room = rooms[plan.destinationBuildingId] ?: return@forEach
                    if (room.fits(cargo.material) < cargo.amount) room.block() else room.take(cargo.material, cargo.amount)
                }
            }
            return batches.all { job ->
                // A buffer batch must fit both its workshop and its eventual warehouse.
                if (job.targetBuildingId in snapshot.stockTargets) {
                    val destination = rooms[job.targetBuildingId] ?: return@all false
                    if (destination.fits(job.material) < job.amount) return@all false
                    destination.take(job.material, job.amount)
                }
                val room = rooms[job.buildingId]
                if (room == null || room.fits(job.material) < job.amount) false
                else { room.take(job.material, job.amount); true }
            }
        }
        var checks = 0
        fun budget() {
            if (cancelled.get()) throw CancellationException("Carpentry calculation retired")
            check(++checks <= 100_000) { "Carpentry calculation budget exhausted" }
        }
        fun find(worker: CivCraftWorker, accepted: Collection<CivCraftJob>, only: CivCraftJob? = null): Pair<CivSupplyRequest, CivCraftRecipe>? {
            val actual = stock.toMutableMap()
            val promised = mutableMapOf<String, Long>()
            accepted.forEach { job ->
                job.inputs.forEach { (material, count) -> actual[material] = ((actual[material] ?: 0) - count).coerceAtLeast(0) }
                add(promised, job.material, job.amount)
            }
            val costCache = hashMapOf<Pair<String, Long>, Long>()
            fun alternatives(material: String) = (if (only?.material == material) listOfNotNull(recipe(only))
                else byMaterial[material].orEmpty().distinctBy { if (material in generic) material else it.id }).filter {
                it.workshop == worker.type && it.level <= worker.level && (!it.stripping || worker.axe)
            }
            fun cost(material: String, amount: Long, depth: Int = 0): Long {
                budget(); check(depth <= 8)
                val missing = (amount - (actual[material] ?: 0) - (promised[material] ?: 0)).coerceAtLeast(0)
                if (missing == 0L) return 0
                return costCache.getOrPut(material to missing) {
                    alternatives(material).minOfOrNull { original ->
                        val recipe = if (only?.recipeId == original.id) original else resolve(original, actual, promised) { input, count ->
                            cost(input, count, depth + 1)
                        }
                        val batches = (missing - 1) / recipe.amount + 1
                        recipe.inputs.entries.fold(0L) { total, (input, count) ->
                            Math.addExact(total, cost(input, Math.multiplyExact(count, batches), depth + 1))
                        }
                    } ?: missing
                }
            }
            // Null recipe with pending=true means another accepted batch must finish first.
            fun need(material: String, amount: Long, depth: Int = 0, oneBatch: Boolean = false): Pair<CivCraftRecipe?, Boolean> {
                budget(); check(depth <= 8)
                val used = minOf(amount, actual[material] ?: 0)
                actual[material] = (actual[material] ?: 0) - used
                val future = minOf(amount - used, promised[material] ?: 0)
                promised[material] = (promised[material] ?: 0) - future
                val missing = amount - used - future
                if (missing <= 0) return null to (future > 0)
                val recipe = alternatives(material).map { original ->
                    if (only?.recipeId == original.id) original else resolve(original, actual, promised) { input, count -> cost(input, count, depth + 1) }
                }.minWithOrNull(compareBy<CivCraftRecipe> {
                    it.inputs.entries.sumOf { (input, count) -> cost(input, count) }
                }.thenBy { it.id }) ?: return null to false
                val batches = if (oneBatch) 1 else (missing - 1) / recipe.amount + 1
                var pending = false
                for ((input, count) in recipe.inputs) {
                    val child = need(input, Math.multiplyExact(count, batches), depth + 1, oneBatch)
                    child.first?.let { return it to false }
                    pending = pending || child.second
                }
                return (if (pending) null else recipe) to pending
            }
            var bufferInputsReserved = false
            for (root in roots) {
                if (root.buildingId in snapshot.stockTargets) {
                    if (!bufferInputsReserved) {
                        // Raw construction/consumer inputs have no recipe root, but still
                        // outrank optional conversion of those same logs or planks.
                        snapshot.requests.filter { it.material !in byMaterial }.forEach { demand ->
                            actual[demand.material] = ((actual[demand.material] ?: 0L) -
                                (demand.needed - demand.inTransit).coerceAtLeast(0)).coerceAtLeast(0)
                        }
                        bufferInputsReserved = true
                    }
                    costCache.clear()
                    val room = CivSupplyRoom(snapshot.stores[root.buildingId]?.capacity ?: continue, snapshot.stackSizes)
                    // Do not make optional intermediates when the final buffer cannot be stored.
                    if (room.fits(root.material) <= 0) continue
                }
                val bufferInputs = actual.toMap().takeIf { root.buildingId in snapshot.stockTargets }
                val next = need(root.material, root.needed - root.inTransit, oneBatch = bufferInputs != null).first
                if (next != null && bufferInputs != null && next.inputs.any { (material, count) ->
                        (bufferInputs[material] ?: 0L) < count }) continue
                if (next != null && only?.targetBuildingId in snapshot.stockTargets && root.buildingId !in snapshot.stockTargets)
                    return null
                if (next != null && (only == null || root.buildingId == only.targetBuildingId && root.material == only.demandMaterial &&
                        next.id == only.recipeId)) return root to next
            }
            return null
        }
        // Revalidate retained intent against current demand before adding new output promises.
        val retained = snapshot.craftJobs.filter { (id, job) -> workers[id]?.let { worker ->
            job.workerId == id && job.buildingId == worker.buildingId && job.cycle == worker.cycle &&
                recipe(job)?.let { it.workshop == worker.type && it.level <= worker.level && (!it.stripping || worker.axe) } == true &&
                (job.constructionId == null || job.constructionId == snapshot.constructionId)
        } == true }.toSortedMap(compareBy(UUID::toString))
        for ((id, job) in retained) {
            budget()
            val other = jobs.values + retained.filterKeys { it != id && it !in jobs }.values
            if (outputsFit(jobs.values + job) && find(workers.getValue(id), other, job) != null) {
                jobs[id] = job
            }
        }
        for (worker in workers.values.sortedBy { it.workerId.toString() }) {
            budget()
            if (worker.workerId in jobs) continue
            val (root, recipe) = find(worker, jobs.values) ?: continue
            val id = UUID.nameUUIDFromBytes("${snapshot.craftEpoch}/${worker.workerId}/${worker.cycle}/${recipe.id}".toByteArray(StandardCharsets.UTF_8))
            val job = CivCraftJob(id, worker.workerId, worker.buildingId, worker.cycle, recipe.id, VERSION,
                recipe.material, recipe.amount, recipe.inputs, root.material, root.buildingId,
                snapshot.constructionId.takeIf { root.construction })
            if (outputsFit(jobs.values + job)) jobs[worker.workerId] = job
        }
        val required = linkedMapOf<UUID, MutableMap<String, Long>>()
        jobs.values.forEach { job ->
            val worker = workers.getValue(job.workerId)
            val inputs = required.getOrPut(job.buildingId, ::linkedMapOf)
            job.inputs.forEach { (material, count) -> add(inputs, material, (count - (worker.carried[material] ?: 0)).coerceAtLeast(0)) }
        }
        val requests = required.flatMap { (building, inputs) -> inputs.mapNotNull { (material, desired) ->
            val needed = (desired - (snapshot.stores[building]?.stock?.get(material) ?: 0)).coerceAtLeast(0)
            val incoming = snapshot.retained.values.filter { it.carrying && it.destinationBuildingId == building && it.cargo.material == material }
                .distinctBy { it.cargo.id }.sumOf { it.cargo.amount }
            if (needed > 0 || incoming > 0) CivSupplyRequest(building, material, needed, incoming) else null
        } }
        return CivCraftAllocation(jobs.toMap(), required.mapValues { it.value.toMap() }, requests)
    }
}

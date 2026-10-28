package ru.arc.civs

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable

/** Retained knowledge and native action selection for one worker's authored field. */
internal class CivsFarmWorksite(val building: CivBuilding) {
    val plots = CivsWorksiteLayouts.field(building)
    private val columns = plots.associateBy { it.x to it.z }
    val chunks = plots.mapTo(hashSetOf()) { (it.x shr 4) to (it.z shr 4) }
    var next = 0
        private set
    var examined = 0
        private set
    var lastFocus: CivPosition? = null
        private set
    private val ripe = linkedSetOf<CivPosition>()
    private val unplanted = linkedSetOf<CivPosition>()
    private val immature = linkedSetOf<CivPosition>()
    private val dirty = linkedSetOf<CivPosition>()
    val harvest: Set<CivPosition> get() = ripe
    val field: Set<CivPosition> get() = unplanted
    val care: Set<CivPosition> get() = immature

    data class Attempt(val finish: ((CivWorkAction) -> CivWorldWork)?, val pending: Boolean)
    sealed interface Selection {
        data class Ready(val work: CivWorldWork) : Selection
        data object Unavailable : Selection
        data object Pending : Selection
        data object Exhausted : Selection
    }

    fun selected(focus: CivPosition?) { lastFocus = focus }
    fun changed(point: CivPosition) { columns[point.x to point.z]?.let { dirty += it } }
    fun protectionChanged(affected: (CivPosition) -> Boolean) { plots.filterTo(dirty, affected) }

    /** The caller admits every column against the pulse shared with the other worksite types. */
    fun refresh(world: World, autonomous: Boolean, available: () -> Map<String, Long>, maximumColumns: Int,
        admitColumn: () -> Boolean, read: (CivPosition) -> Block?) {
        val supplies by lazy(available)
        val crop = cropAndSeed(building).first
        repeat(minOf(maximumColumns, plots.size)) {
            if (!admitColumn()) return
            val changed = dirty.firstOrNull()
            val point = changed ?: plots[next]
            if (changed != null) dirty.remove(changed) else {
                next = (next + 1) % plots.size
                examined = minOf(examined + 1, plots.size)
            }
            ripe.remove(point); unplanted.remove(point); immature.remove(point)
            val soil = read(point) ?: return@repeat
            val top = read(point.copy(y = point.y + 1)) ?: return@repeat
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) return@repeat
            if (soil.type == Material.FARMLAND && top.type == crop) {
                val age = top.blockData as? Ageable ?: return@repeat
                if (age.age == age.maximumAge) ripe += point
                else if (cropCareChanges(world, top, soil, age,
                        autonomous || (supplies["BONE_MEAL"] ?: 0) > 0).isNotEmpty()) immature += point
            } else if (top.type != crop) unplanted += point
        }
    }

    /** Read/claim/admission keep their existing owners; drops are captured only after the shared stance admission succeeds. */
    fun select(worker: CivWorker, world: World, autonomous: Boolean, harvestOnly: Boolean,
        available: () -> Map<String, Long>, pending: CivPosition?, claimed: (CivPosition) -> Boolean,
        read: (CivPosition, Boolean) -> Block?, blocked: (CivWorkNeed) -> Unit,
        admit: (CivPosition) -> Attempt): Selection {
        if (!autonomous && CivsEquipment.slots(worker)[4]?.material?.endsWith("_HOE") != true) return Selection.Unavailable
        val (crop, seed) = cropAndSeed(building)
        // A paid Auto field owns its seeds; this planning allowance never creates a carried item.
        val supplies by lazy(available)
        fun need(value: CivWorkNeed): Selection { blocked(value); return Selection.Unavailable }
        for (point in CivsWorksiteLayouts.irrigation(building)) {
            val block = read(point, true) ?: return Selection.Unavailable
            if (block.type == Material.WATER) continue
            if (harvestOnly) return Selection.Unavailable
            if (block.type !in SOILS) return need(CivWorkNeed("worksite-soil", point = point,
                reason = "irrigation-ground", blockMaterial = block.type.name))
            val attempt = admit(point)
            val finish = attempt.finish ?: return if (attempt.pending) Selection.Pending else Selection.Unavailable
            return Selection.Ready(finish(CivWorkAction(listOf(change(block, Material.WATER.createBlockData().asString)))))
        }
        val from = lastFocus ?: building.entrance
        val nearest = compareBy<CivPosition> { if (pending == it || pending == it.copy(y = it.y + 1)) 0 else 1 }
            .thenBy { val dx = from.x.toDouble() - it.x; val dz = from.z.toDouble() - it.z; dx * dx + dz * dz }
            .thenBy { it.x }.thenBy { it.z }
        val candidates = ripe.sortedWith(nearest).asSequence() + sequence {
            if (harvestOnly || examined != plots.size) return@sequence
            // Visit the next authored plot instead of repeatedly boosting the plant we just left.
            val after = plots.indexOfFirst { it == lastFocus || it.copy(y = it.y + 1) == lastFocus } + 1
            val care = plots.indices.map { plots[(after + it) % plots.size] }
                .filter { it in immature }
                .sortedBy { if (pending == it || pending == it.copy(y = it.y + 1)) 0 else 1 }
            yieldAll(unplanted.sortedWith(nearest))
            yieldAll(care)
        }
        var checked = 0
        for (soilPoint in candidates) {
            if (claimed(soilPoint)) continue
            if (checked++ == 16) return need(CivWorkNeed("searching-work"))
            val wasHarvest = soilPoint in ripe
            ripe.remove(soilPoint); unplanted.remove(soilPoint); immature.remove(soilPoint)
            val soil = read(soilPoint, false) ?: continue
            val top = read(soilPoint.copy(y = soilPoint.y + 1), false) ?: continue
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) continue
            if (soil.type == Material.FARMLAND && top.type == crop) {
                val age = top.blockData as? Ageable ?: continue
                if (age.age < age.maximumAge) {
                    if (harvestOnly || wasHarvest) continue
                    val care = careCrop(world, position(top), supplies, autonomous, read, admit)
                    if (care != null) {
                        immature += soilPoint
                        return care
                    }
                    continue
                }
                ripe += soilPoint
            } else {
                if (harvestOnly || wasHarvest) continue
                unplanted += soilPoint
            }
            val focus = if (soil.type == Material.FARMLAND) position(top) else soilPoint
            val attempt = admit(focus)
            val finish = attempt.finish
            if (finish == null) {
                if (attempt.pending) return Selection.Pending
                ripe.remove(soilPoint); unplanted.remove(soilPoint)
                continue
            }
            if (soil.type != Material.FARMLAND) {
                if (top.type == crop) continue
                return Selection.Ready(finish(CivWorkAction(listOf(change(soil, "minecraft:farmland[moisture=7]")) +
                    if (top.type in WEEDS) listOf(change(top, "minecraft:air")) else emptyList())))
            }
            if (top.type == crop) {
                val age = top.blockData as? Ageable ?: continue
                if (age.age != age.maximumAge) continue
                val drops = (if (autonomous) emptyMap() else drops(top, worker)).toMutableMap()
                val seedCount = drops[seed] ?: 0
                val replant = seedCount > 0 || (supplies[seed] ?: 0) > 0
                if (seedCount > 0) put(drops, seed, seedCount - 1)
                val inputs = if (replant && seedCount == 0L) mapOf(seed to 1L) else emptyMap()
                return Selection.Ready(finish(CivWorkAction(listOf(change(top,
                    if (replant) crop.createBlockData().asString else "minecraft:air")), inputs, drops)))
            }
            if ((supplies[seed] ?: 0) > 0) return Selection.Ready(finish(CivWorkAction(
                listOf(change(top, crop.createBlockData().asString)), mapOf(seed to 1))))
            blocked(CivWorkNeed("waiting-seeds", seed))
        }
        if (examined < plots.size) return need(CivWorkNeed("searching-work"))
        return Selection.Exhausted
    }

    private fun careCrop(world: World, focus: CivPosition, available: Map<String, Long>, autonomous: Boolean,
        read: (CivPosition, Boolean) -> Block?, admit: (CivPosition) -> Attempt): Selection? {
        val crop = read(focus, false) ?: return null
        val soil = read(focus.copy(y = focus.y - 1), false) ?: return null
        val age = crop.blockData as? Ageable ?: return null
        if (soil.type != Material.FARMLAND || crop.type != cropAndSeed(building).first || age.age == age.maximumAge) return null
        val fertilizing = autonomous || (available["BONE_MEAL"] ?: 0) > 0
        val changes = cropCareChanges(world, crop, soil, age, fertilizing)
        if (changes.isEmpty()) return null
        val attempt = admit(focus)
        val finish = attempt.finish ?: return if (attempt.pending) Selection.Pending else null
        return Selection.Ready(finish(CivWorkAction(changes, if (fertilizing && !autonomous) mapOf("BONE_MEAL" to 1L) else emptyMap(),
            status = if (fertilizing) "fertilizing" else "watering", duration = 4_000, focus = focus)))
    }

    companion object {
        fun cropAndSeed(building: CivBuilding): Pair<Material, String> = when (CivsProduction.material(building)) {
            "CARROT" -> Material.CARROTS to "CARROT"
            "POTATO" -> Material.POTATOES to "POTATO"
            else -> Material.WHEAT to "WHEAT_SEEDS"
        }

        private fun cropCareChanges(world: World, crop: Block, soil: Block, age: Ageable, fertilizing: Boolean): List<CivPlanBlock> = buildList {
            val rainExposed = world.hasStorm() && world.getHighestBlockYAt(crop.x, crop.z) <= crop.y
            if (!rainExposed && soil.blockData.asString != "minecraft:farmland[moisture=7]")
                add(change(soil, "minecraft:farmland[moisture=7]"))
            // Exposed rain needs no watering; accepted fertilizer still advances exactly one immature stage.
            if (!rainExposed || fertilizing)
                add(change(crop, (age.clone() as Ageable).also { it.age++ }.asString))
        }

        private fun drops(block: Block, worker: CivWorker): Map<String, Long> = block.getDrops(
            CivsEquipment.slots(worker)[4]?.stack() ?: org.bukkit.inventory.ItemStack(Material.AIR)).filter { !it.type.isAir }
            .groupingBy { it.type.name }.fold(0L) { count, item -> count + item.amount }
        private fun change(block: Block, after: String) = CivPlanBlock(position(block), block.blockData.asString, after)
        private fun position(block: Block) = CivPosition(block.x, block.y, block.z)
        private fun put(map: MutableMap<String, Long>, key: String, value: Long) { if (value > 0) map[key] = value else map.remove(key) }
        private val SOILS = setOf(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.FARMLAND)
        private val WEEDS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
    }
}

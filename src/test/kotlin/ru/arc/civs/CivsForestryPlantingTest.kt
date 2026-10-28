package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import java.util.UUID

class CivsForestryPlantingFairnessTest : FunSpec({
    test("a planting turn with dense young crowns keeps native tree discovery bounded under the shared default quotas") {
        var nanos = 0L
        forestryWorksite("forestry-planting-fairness", strict = true,
            nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, initial ->
            var city = initial.copy(stock = Stock(wood = 64))
            var worker = city.workers.single()
            val firstRoot = CivPosition(-15, 64, -26)
            val secondRoot = CivPosition(10, 64, -26)
            val scheduler = Bukkit.getScheduler() as BukkitSchedulerMock
            val settings = CivsWorksiteSettings()
            fun entry(): Map<*, *> = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            fun pulse() {
                scheduler.performTicks(5)
                val rays = world.rayTraces
                runtime.pulse(listOf(city))
                check(world.rayTraces - rays <= settings.rayChecksPerPulse)
                val budget = runtime.debug(setOf(worker.id))["remainingPulseBudget"] as Map<*, *>
                check(budget["forestWorkUnits"] as Int in 0..settings.forestWorkUnitsPerPulse)
                check(budget["columns"] as Int in 0..settings.maximumForestColumnsPerPulse)
            }
            forestrySmallOak(world, firstRoot)
            repeat(3_000) { if (runtime.plan(city, worker) == null) pulse() }
            val first = requireNotNull(runtime.plan(city, worker)) { "Initial tree was not admitted: ${entry()}" }
            first.status shouldBe "felling-tree"
            runtime.validate(city, first)
            val receipt = forestryReceipt(city, first, 1_000).validated()
            CivsStorage(requireNotNull(Bukkit.getPluginManager().getPlugin("ForestryCare"))) { emptyArray() }.use { storage ->
                storage.replaceCities(listOf(city))
                storage.reserve(city, receipt.id)
                storage.seal(receipt)
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(receipt.id)
            }
            city = receipt.after
            worker = city.workers.single()
            runtime.invalidate(worker.id, first)
            world.getBlockAt(firstRoot.x, firstRoot.y, firstRoot.z).blockData =
                Bukkit.createBlockData("minecraft:oak_sapling[stage=1]")
            runtime.changed(world.uid, firstRoot)

            // Complete ordinary discovery and enter the planting turn earned by that real receipt.
            var pending = false
            repeat(5_000) {
                if (!pending) {
                    pulse()
                    pending = entry()["forestPlant"] != null
                }
            }
            check(pending) { "No resumable planting check after full discovery: ${entry()}" }
            val covered = entry()["forestSearch"] as Map<*, *>
            covered["column"] shouldBe covered["columns"]
            check((covered["planting"] as Int) > 100)
            runtime.plan(city, worker) shouldBe null

            // Native growth after the completed sweep leaves many stale empty-site candidates.
            // Mature trees remain actionable; the dense young crowns make planting attempts fail.
            for (root in listOf(firstRoot, secondRoot)) {
                forestrySmallOak(world, root)
                runtime.changed(world.uid, root)
            }
            val paths = CivsForestry.paths(city)
            for (x in -15..26 step 4) for (z in -26..10 step 4) {
                val point = CivPosition(x, 64, z)
                if (point in listOf(firstRoot, secondRoot) || CivsForestry.blockedColumn(city, x, z, paths)) continue
                world.getBlockAt(x, 64, z).blockData = Bukkit.createBlockData("minecraft:oak_sapling[stage=1]")
                runtime.changed(world.uid, point)
            }
            val afterGrowth = entry()["forestSearch"] as Map<*, *>
            afterGrowth["column"] shouldBe covered["columns"]
            var pulses = 0
            while (pulses < 64 && runtime.plan(city, worker) == null) { pulse(); pulses++ }
            val next = requireNotNull(runtime.plan(city, worker)) {
                "Available native trees stayed behind planting attempts for $pulses default pulses: ${entry()}"
            }
            next.status shouldBe "felling-tree"
            check(next.focus in listOf(firstRoot, secondRoot))
            runtime.validate(city, next)
            city.stock shouldBe receipt.after.stock
            city.forestryJobs shouldBe receipt.after.forestryJobs
            println("Forestry planting fairness: native tree admitted after $pulses default 192-unit/16-column/2ms pulses")
        }
    }
})

class CivsForestryGrassPlantingTest : FunSpec({
    for (timber in listOf("OAK", "SPRUCE")) for (tall in listOf(false, true))
        test("$timber planting clears only ${if (tall) "both tall grass halves" else "short grass"} in one paid native receipt") {
            var nanos = 0L
            forestryWorksite("forestry-grass-$timber-$tall", timber = timber, strict = true,
                // Keep production's 2ms policy; each clock check advances deterministically on slow CI too.
                nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, initial ->
                val city = initial.copy(stock = Stock(wood = 64))
                val worker = city.workers.single()
                val columns = forestryTestColumns(city)
                val field = columns.flatMap { p -> (-1..1).flatMap { dx -> (-1..1).map { dz -> p.copy(x = p.x + dx, z = p.z + dz) } } }.distinct()
                field.forEach { point ->
                    world.getBlockAt(point.x, 64, point.z).blockData = Bukkit.createBlockData(
                        if (tall) "minecraft:tall_grass[half=lower]" else "minecraft:short_grass")
                    if (tall) world.getBlockAt(point.x, 65, point.z).blockData =
                        Bukkit.createBlockData("minecraft:tall_grass[half=upper]")
                }
                val before = field.flatMap { p -> (63..65).map { y -> p.copy(y = y) } }
                    .associateWith { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
                val clock = Bukkit.getScheduler() as BukkitSchedulerMock
                var pulses = 0
                val partialPlantScans = linkedSetOf<Int>()
                while (pulses < 256 && runtime.plan(city, worker) == null) {
                    clock.performTicks(5)
                    runtime.pulse(listOf(city))
                    val observed = runtime.debug(setOf(worker.id))
                    val budget = observed["remainingPulseBudget"] as Map<*, *>
                    check(budget["forestWorkUnits"] as Int in 0..192)
                    check(budget["columns"] as Int in 0..16)
                    val entry = (observed["entries"] as List<*>).single() as Map<*, *>
                    (entry["forestPlant"] as? Map<*, *>)?.get("checkedColumns")?.let { partialPlantScans += it as Int }
                    pulses++
                }
                val observed = runtime.debug(setOf(worker.id))
                val work = requireNotNull(runtime.plan(city, worker)) {
                    "A grass-covered field with ${columns.size} soil columns has no planting after $pulses default pulses: $observed"
                }
                work.status shouldBe "planting-tree"
                check(partialPlantScans.size > 1) { "The 2ms planting cursor was not resumed: $partialPlantScans" }
                val roots = work.changes.filter { it.after == CivsForestry.saplingState("${timber}_SAPLING") }
                roots.size shouldBe if (timber == "SPRUCE") 4 else 1
                work.inputs shouldBe mapOf("${timber}_SAPLING" to roots.size.toLong())
                work.changes.size shouldBe roots.size * if (tall) 2 else 1
                roots.forEach { cell -> cell.before shouldBe if (tall) "minecraft:tall_grass[half=lower]" else "minecraft:short_grass" }
                val after = forestryCommitTestWork(city, runtime, work, 1_000)
                after.stock.wood shouldBe city.stock.wood - 1
                val expected = before.toMutableMap().also { states -> work.changes.forEach { states[it.position] = it.after } }
                expected.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                work.changes.all { it.position.y >= 64 } shouldBe true
                println("Grass planting: timber=$timber tall=$tall pulses=$pulses resumed=$partialPlantScans roots=${roots.map { it.position }}")
            }
        }

    test("grass receipts reject a missing upper half other vegetation duplicate cells and extra payment") {
        val city = forestryCity()
        val planting = forestryPlantWork(city)
        val lower = planting.changes.map { it.copy(before = "minecraft:tall_grass[half=lower]") }
        val upper = lower.map { CivPlanBlock(it.position.copy(y = it.position.y + 1), "minecraft:tall_grass[half=upper]", "minecraft:air") }
        val tall = planting.copy(changes = lower + upper)
        CivsForestry.validateWork(city, tall)
        val short = planting.copy(changes = planting.changes.map { it.copy(before = "minecraft:short_grass") })
        CivsForestry.validateWork(city, short)
        for (invalid in listOf(
            tall.copy(changes = tall.changes.dropLast(1)),
            tall.copy(changes = tall.changes + upper.first()),
            tall.copy(changes = lower + upper.map { it.copy(before = "minecraft:fern") }),
            tall.copy(changes = lower.map { it.copy(before = "minecraft:tall_grass[half=upper]") } + upper),
            tall.copy(inputs = mapOf("SPRUCE_SAPLING" to 8L)),
            short.copy(changes = short.changes.map { it.copy(before = "minecraft:fern") }),
            short.copy(changes = short.changes + upper.first()),
        )) shouldThrow<IllegalArgumentException> { CivsForestry.validateWork(city, invalid) }
    }

    test("seeded grass planting uses several separated sites beyond rejected young crowns and retains roads") {
        val firstSites = mutableListOf<CivPosition>()
        val road = (-26..11).mapTo(hashSetOf()) { 0 to it }
        for (seed in listOf(9_101L, 9_102L)) {
            var nanos = 0L
            forestryWorksite("forestry-seeded-grass-$seed", timber = "BIRCH", strict = true, retainedPaths = road,
                nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, initial ->
                val old = requireNotNull(initial.building(requireNotNull(initial.workers.single().assignment)))
                val mill = old.copy(id = UUID(0, seed))
                var city = initial.copy(stock = Stock(wood = 64), buildings = initial.buildings.map { if (it.id == old.id) mill else it },
                    workers = initial.workers.map { it.copy(assignment = mill.id) })
                val columns = forestryTestColumns(city)
                columns.forEach { p -> world.getBlockAt(p.x, 64, p.z).type = Material.SHORT_GRASS }
                road.forEach { (x, z) ->
                    world.getBlockAt(x, 63, z).type = Material.DIRT_PATH
                    world.getBlockAt(x, 64, z).type = Material.AIR
                }
                val young = listOf(CivPosition(-10, 64, -20), CivPosition(18, 64, 4))
                young.forEach { p -> world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData("minecraft:birch_sapling[stage=1]") }
                val guards = (columns.map { it.copy(y = 63) } + young).associateWith { p ->
                    world.getBlockAt(p.x, p.y, p.z).blockData.asString
                }
                val selected = mutableListOf<CivPosition>()
                var actions = 0
                var rejected = 0
                while (selected.size < 3 && actions < 8) {
                    val worker = city.workers.single()
                    var pulses = 0
                    while (pulses < 3_000 && runtime.plan(city, worker) == null) {
                        forestryTestPulse(city, runtime)
                        val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                        rejected = maxOf(rejected, ((entry["forestSearch"] as Map<*, *>)["unplantable"] as Int))
                        pulses++
                    }
                    val work = requireNotNull(runtime.plan(city, worker)) { "No next grass planting: ${runtime.debug(setOf(worker.id))}" }
                    check(work.status in setOf("planting-tree", "watering"))
                    if (work.status == "planting-tree") {
                        val root = work.changes.single().position
                        val crown = CivsForestry.crownBounds(listOf(root), "BIRCH_LOG")
                        road.none { (x, z) -> x in crown.min.x..crown.max.x && z in crown.min.z..crown.max.z } shouldBe true
                        for (previous in young + selected) check(kotlin.math.abs(root.x - previous.x) > 6 || kotlin.math.abs(root.z - previous.z) > 6)
                        work.changes.single().before shouldBe "minecraft:short_grass"
                        selected += root
                    }
                    city = forestryCommitTestWork(city, runtime, work, 1_000L + actions * 10_000L)
                    actions++
                }
                selected.size shouldBe 3
                check(rejected > 0) { "Fixture did not exercise rejected planting sites" }
                city.stock.wood shouldBe 61L
                guards.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                firstSites += selected.first()
                println("Seeded grass planting: seed=$seed sites=$selected rejected=$rejected actions=$actions")
            }
        }
        firstSites.distinct().size shouldBe 2
    }

    test("locking or changing a captured tall grass upper half rejects the whole unpaid planting") {
        val locked = hashSetOf<CivPosition>()
        var nanos = 0L
        forestryWorksite("forestry-protected-grass", locked = locked, strict = true,
            nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, initial ->
            val city = initial.copy(stock = Stock(wood = 64))
            val worker = city.workers.single()
            forestryTestColumns(city).forEach { p ->
                world.getBlockAt(p.x, 64, p.z).blockData = Bukkit.createBlockData("minecraft:tall_grass[half=lower]")
                world.getBlockAt(p.x, 65, p.z).blockData = Bukkit.createBlockData("minecraft:tall_grass[half=upper]")
            }
            repeat(3_000) { if (runtime.plan(city, worker) == null) forestryTestPulse(city, runtime) }
            val work = requireNotNull(runtime.plan(city, worker))
            val upper = work.changes.single { it.after == "minecraft:air" }
            locked += upper.position
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "protected-area"
            locked.clear()
            val p = upper.position
            world.getBlockAt(p.x, p.y, p.z).type = Material.FERN
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
            work.changes.filter { it.position != p }.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
            city.stock.wood shouldBe 64L
            city.workers.single().completedCycles shouldBe 0
        }
    }

    test("physical giant grass planting consumes four carried saplings without adding axe wear or grass drops") {
        var nanos = 0L
        forestryWorksite("forestry-physical-grass", timber = "SPRUCE", autonomous = false, strict = true,
            nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, initial ->
            val worker = CivsEquipment.collectSupplies(initial.workers.single(), mapOf("SPRUCE_SAPLING" to 4L))
            val city = initial.copy(workers = listOf(worker))
            val field = forestryTestColumns(city).flatMap { p -> (-1..1).flatMap { dx -> (-1..1).map { dz -> p.copy(x = p.x + dx, z = p.z + dz) } } }.distinct()
            field.forEach { p ->
                world.getBlockAt(p.x, 64, p.z).blockData = Bukkit.createBlockData("minecraft:tall_grass[half=lower]")
                world.getBlockAt(p.x, 65, p.z).blockData = Bukkit.createBlockData("minecraft:tall_grass[half=upper]")
            }
            repeat(3_000) { if (runtime.plan(city, worker) == null) forestryTestPulse(city, runtime) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "planting-tree"
            work.inputs shouldBe mapOf("SPRUCE_SAPLING" to 4L)
            work.outputs shouldBe emptyMap()
            work.changes.size shouldBe 8
            work.equipmentAfter shouldBe null
            val after = forestryCommitTestWork(city, runtime, work, 1_000)
            (CivsEquipment.available(after.workers.single(), emptyMap())["SPRUCE_SAPLING"] ?: 0) shouldBe 0
            CivsEquipment.slots(after.workers.single())[4] shouldBe CivsEquipment.slots(worker)[4]
            after.stock shouldBe city.stock
        }
    }

    for (obstruction in listOf("other-plants", "road-soil", "insufficient-saplings"))
        test("$obstruction cannot become a free cleared forestry planting") {
            var nanos = 0L
            forestryWorksite("forestry-grass-control-$obstruction", timber = "SPRUCE", autonomous = false, strict = true,
                supplies = { if (obstruction == "insufficient-saplings") mapOf("SPRUCE_SAPLING" to 3L) else mapOf("SPRUCE_SAPLING" to 4L) },
                nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, city ->
                val worker = city.workers.single()
                val columns = forestryTestColumns(city)
                columns.forEach { p ->
                    world.getBlockAt(p.x, 64, p.z).type = if (obstruction == "other-plants") Material.FERN else Material.SHORT_GRASS
                    if (obstruction == "road-soil") world.getBlockAt(p.x, 63, p.z).type = Material.COARSE_DIRT
                }
                val before = columns.flatMap { p -> listOf(p.copy(y = 63), p.copy(y = 64)) }
                    .associateWith { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
                repeat(3_000) { forestryTestPulse(city, runtime) }
                runtime.plan(city, worker) shouldBe null
                if (obstruction == "insufficient-saplings") runtime.idleReason(city, worker) shouldBe "waiting-saplings"
                before.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                city.workers.single().completedCycles shouldBe 0
            }
        }
})

private fun forestryTestColumns(city: Settlement): List<CivPosition> {
    val building = requireNotNull(city.building(requireNotNull(city.workers.single().assignment)))
    val box = requireNotNull(CivsWorksiteLayouts.site(building)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
    val min = CivsWorksiteLayouts.vector(box["min"])
    val max = CivsWorksiteLayouts.vector(box["max"])
    return (min.z..max.z).flatMap { z -> (min.x..max.x).map { x -> CivsWorksiteLayouts.point(building, CivPosition(x, 0, z)) } }
}

private fun forestryCommitTestWork(city: Settlement, runtime: CivsWorksites, work: CivWorldWork, at: Long): Settlement {
    runtime.validate(city, work)
    val receipt = (if (city.autonomy != null) forestryReceipt(city, work, at) else CivInventoryTransaction(UUID.randomUUID(),
        city.id, city.revision, CivsRules.finishWorldWork(city, work, at), emptyList(), worldChanges = work.changes)).validated()
    CivsStorage(requireNotNull(Bukkit.getPluginManager().getPlugin("ForestryCare"))) { emptyArray() }.use { storage ->
        storage.replaceCities(listOf(city))
        storage.reserve(city, receipt.id)
        storage.seal(receipt)
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.unlock(receipt.id)
    }
    runtime.invalidate(work.workerId, work)
    return receipt.after
}

private fun forestryTestPulse(city: Settlement, runtime: CivsWorksites) {
    (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
    runtime.pulse(listOf(city))
    val budget = runtime.debug(setOf(city.workers.single().id))["remainingPulseBudget"] as Map<*, *>
    check(budget["forestWorkUnits"] as Int in 0..192 && budget["columns"] as Int in 0..16 && budget["rays"] as Int in 0..8)
}

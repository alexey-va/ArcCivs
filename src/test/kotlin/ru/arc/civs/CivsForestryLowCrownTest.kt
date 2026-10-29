package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import java.util.UUID

class CivsForestryLowCrownTest : FunSpec({
    test("captured Auto2 low crown admits its exact saved next128 batch through an ordinary three metre stance") {
        forestryWorksite("saved-low-crown", autonomous = false, strict = true) { world, runtime, initial ->
            val root = CivPosition(-15, 64, -26)
            val (job, native) = lowCrownCapture(world, root)
            val worker = initial.workers.single()
            val building = requireNotNull(worker.assignment)
            val city = initial.copy(forestryJobs = mapOf(building.toString() to job))
            job.next shouldBe 128
            job.harvestedLogs shouldBe 0
            CivsForestry.valid(city, building.toString(), job) shouldBe true
            // Same relative distant resident position as the capture; this test does not replay a route.
            val feet = Location(world, root.x + 15.5, 64.0, root.z - 3.5)
            runtime.residentFeet = { feet }
            citizenBlockAimPoints(feet.clone().add(0.0, 1.62, 0.0), root).all {
                it.distanceSquared(feet.clone().add(0.0, 1.62, 0.0)) > CITIZEN_REMOTE_WORK_REACH * CITIZEN_REMOTE_WORK_REACH
            } shouldBe true
            val safe = buildList {
                for (dy in -4..3) for (dx in -4..4) for (dz in -4..4) {
                    val p = root.copy(x = root.x + dx, y = root.y + dy, z = root.z + dz)
                    val eye = Location(world, p.x + .5, p.y + 1.62, p.z + .5)
                    if (citizenBlockAimPoints(eye, root).none { eye.distanceSquared(it) <= 9.0 }) continue
                    if (citizenStandable(world, p) && citizenPacketBodyClear(world, p.point(), false)) add(p)
                }
            }
            (safe.isNotEmpty()) shouldBe true
            safe.any { citizenCanReachBlock(world, Location(world, it.x + .5, it.y + 1.62, it.z + .5), root) } shouldBe false
            repeat(512) { if (runtime.plan(city, worker) == null) lowCrownPulse(runtime, city) }
            val work = requireNotNull(runtime.plan(city, worker)) { "Saved native crown did not admit: ${runtime.debug(setOf(worker.id))}" }
            work.remoteWork shouldBe false
            (work.standing in safe) shouldBe true
            work.forestry?.job shouldBe job
            work.forestry?.next shouldBe job.cells.size
            work.inputs shouldBe emptyMap()
            val eye = Location(world, work.standing.x + .5, work.standing.y + 1.62, work.standing.z + .5)
            (citizenReachableBlockPoint(world, eye, root, CITIZEN_BLOCK_REACH, work) != null) shouldBe true
            runtime.validate(city, work)
            city.forestryJobs?.get(building.toString()) shouldBe job
            city.stock shouldBe initial.stock
            city.workers shouldBe initial.workers
            native.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
        }
    }

    test("zero admitted stances close a complete saved-job sweep but budget yield does not and TTL or geometry reopen it") {
        forestryWorksite("closed-saved-crown", autonomous = false, strict = true,
            settings = CivsWorksiteSettings(standingChecksPerPulse = 1, rayChecksPerPulse = 1)) { world, runtime, initial ->
            val root = CivPosition(-15, 64, -26)
            val job = lowCrownPartialRoot(root)
            val worker = initial.workers.single()
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to job))
            for (x in root.x - 4..root.x + 4) for (z in root.z - 4..root.z + 4) for (y in 64..67)
                world.getBlockAt(x, y, z).type = Material.STONE
            world.getBlockAt(root.x, root.y, root.z).type = Material.OAK_LOG
            runtime.residentFeet = { Location(world, root.x + 15.5, 64.0, root.z + .5) }
            fun entry() = lowCrownEntry(runtime, worker)
            fun exhausted() = (entry()["failedStances"] as? Map<*, *>)?.get("exhausted") == true
            lowCrownPulse(runtime, city)
            (entry()["candidateSearch"] != null) shouldBe true
            exhausted() shouldBe false
            repeat(512) { if (!exhausted()) lowCrownPulse(runtime, city) }
            exhausted() shouldBe true
            val failure = entry()["failedStances"] as Map<*, *>
            ((failure["positions"] as Map<*, *>)["total"]) shouldBe 0
            entry()["candidateSearch"] shouldBe null
            val closedRays = world.rayTraces
            repeat(10) { lowCrownPulse(runtime, city); runtime.plan(city, worker) shouldBe null }
            world.rayTraces shouldBe closedRays
            entry()["candidateSearch"] shouldBe null
            // A saved job follows the same existing100-tick pause; it cannot bypass it at plan().
            lowCrownPulse(runtime, city, 51)
            (entry()["candidateSearch"] != null) shouldBe true
            exhausted() shouldBe false
            repeat(512) { if (!exhausted()) lowCrownPulse(runtime, city) }
            exhausted() shouldBe true
            // Geometry changes may reopen immediately without waiting for that second TTL.
            for (y in 64..67) world.getBlockAt(root.x + 1, y, root.z).type = Material.AIR
            for (y in 65..67) world.getBlockAt(root.x, y, root.z).type = Material.AIR
            runtime.changed(world.uid, root.copy(x = root.x + 1))
            exhausted() shouldBe false
            repeat(512) { if (runtime.plan(city, worker) == null) lowCrownPulse(runtime, city) }
            val work = requireNotNull(runtime.plan(city, worker)) { "Changed standing did not reopen saved job" }
            runtime.validate(city, work)
            work.forestry?.job shouldBe job
            city.forestryJobs?.values?.single()?.next shouldBe 128
            city.workers shouldBe initial.workers
            city.stock shouldBe initial.stock
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
        }
    }

    test("ordinary forestry crown witness never ignores stone glass liquids or persistent and waterlogged leaves") {
        forestryWorksite("ordinary-crown-guards", autonomous = false, strict = true) { world, _, initial ->
            val root = CivPosition(-15, 64, -26)
            val job = lowCrownPartialRoot(root)
            val worker = initial.workers.single()
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to job))
            val standing = root.copy(x = root.x - 2)
            world.getBlockAt(root.x, root.y, root.z).type = Material.OAK_LOG
            val drops = mapOf("OAK_LOG" to 1L)
            val intent = CivWorldWork(worker.id, worker.completedCycles, requireNotNull(worker.assignment), standing,
                listOf(CivPlanBlock(root, "minecraft:oak_log[axis=y]", CivsForestry.saplingState(job.sapling))),
                outputs = drops, status = "felling-tree", focus = root,
                forestry = CivForestryProgress(job, job.cells.size, drops, 1))
            CivsForestry.validateWork(city, intent)
            val eye = Location(world, standing.x + .5, standing.y + 1.62, standing.z + .5)
            for (state in listOf("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]",
                    "minecraft:stone", "minecraft:glass", "minecraft:water[level=0]", "minecraft:lava[level=0]",
                    "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]",
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=true]")) {
                for (y in 64..66) for (z in root.z - 3..root.z + 3)
                    world.getBlockAt(root.x - 1, y, z).blockData = Bukkit.createBlockData(state)
                citizenStandable(world, standing) shouldBe true
                citizenPacketBodyClear(world, standing.point(), false) shouldBe true
                (citizenReachableBlockPoint(world, eye, root, CITIZEN_BLOCK_REACH, intent) != null) shouldBe
                    (state == "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
                citizenReachableBlockPoint(world, eye, root, CITIZEN_BLOCK_REACH) shouldBe null
            }
        }
    }
})

private fun lowCrownEntry(runtime: CivsWorksites, worker: CivWorker) =
    (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>

private fun lowCrownPulse(runtime: CivsWorksites, city: Settlement, ticks: Long = 5) {
    (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(ticks)
    runtime.pulse(listOf(city))
}

private fun lowCrownPartialRoot(root: CivPosition): CivForestryJob {
    fun code(dx: Int, dy: Int, dz: Int, state: Int = 0) = (state shl 16) or (dy shl 10) or ((dz + 15) shl 5) or (dx + 15)
    val prefix = buildList { for (dy in 8..9) for (dx in -4..3) for (dz in -4..3) add(code(dx, dy, dz)) }
    return CivForestryJob(UUID(0, 127), root, "OAK_SAPLING", listOf(root),
        listOf("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", "minecraft:oak_log[axis=y]"),
        prefix + code(0, 0, 0, 1), next = 128)
}

private fun lowCrownCapture(world: CivsWorksiteWorldFixture, root: CivPosition): Pair<CivForestryJob, Map<CivPosition, String>> {
    fun resource(name: String) = JsonParser.parseReader(requireNotNull(CivsForestryLowCrownTest::class.java
        .getResourceAsStream("/scenarios/auto2-forest-low-crown-$name-20261029.json")).reader()).asJsonObject
    val saved = Gson().fromJson(resource("job")["job"], CivForestryJob::class.java)
    fun translated(p: CivPosition) = CivPosition(root.x + p.x - saved.root.x, root.y + p.y - saved.root.y, root.z + p.z - saved.root.z)
    val native = linkedMapOf<CivPosition, String>()
    // Both are exact native captures; the later south sample wins overlapping cells (leaf decay is legal).
    for (name in listOf("root", "south")) {
        val capture = resource(name)
        capture["unavailable_count"].asInt shouldBe 0
        val min = capture.getAsJsonObject("bounds").getAsJsonObject("min")
        val palette = capture.getAsJsonArray("palette")
        capture.getAsJsonArray("voxels").forEach { value ->
            val voxel = value.asJsonArray
            val p = translated(CivPosition(min["x"].asInt + voxel[0].asInt,
                min["y"].asInt + voxel[1].asInt, min["z"].asInt + voxel[2].asInt))
            native[p] = palette[voxel[3].asInt].asJsonObject["block_data"].asString
        }
    }
    native.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
    val job = saved.copy(root = root, roots = saved.roots.map(::translated))
    job.cells.drop(job.next).all { job.position(it) in native } shouldBe true
    return job to native
}

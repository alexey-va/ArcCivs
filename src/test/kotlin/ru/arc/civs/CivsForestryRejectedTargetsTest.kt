package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock

class CivsForestryRejectedTargetsTest : FunSpec({
    test("a new remote angle preserves an unpaid tree's explicitly rejected ordinary stance") {
        forestryWorksite("forestry-retained-rejected-stance", nanoTime = { 0L }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val city = initial.copy(forestrySeeded = setOf(requireNotNull(worker.assignment).toString()))
            val root = CivPosition(-15, 64, -26)
            val onlyStanding = root.copy(x = root.x + 1)
            forestrySmallOak(world, root)
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if ((x != root.x || z != root.z) && (x != onlyStanding.x || z != onlyStanding.z))
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            for (y in 64..66) for (z in root.z - 4..root.z + 4)
                world.getBlockAt(root.x - 8, y, z).type = Material.STONE
            val near = Location(world, root.x - 10.5, 64.0, root.z + .5)
            var feet = near.clone().subtract(20.0, 0.0, 0.0)
            runtime.residentFeet = { feet }
            fun pulse() {
                (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                runtime.pulse(listOf(city))
            }
            var first: CivWorldWork? = null
            repeat(2_000) {
                if (first == null) {
                    pulse()
                    first = runtime.plan(city, worker)
                }
            }
            val rejected = checkNotNull(first)
            rejected.focus shouldBe root
            rejected.standing shouldBe onlyStanding
            rejected.remoteWork shouldBe false
            runtime.rejectStanding(city, worker, rejected) shouldBe true
            fun deferred(): Int {
                val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                return ((entry["deferredForestApproaches"] as Map<*, *>)["total"] as Int)
            }
            repeat(2_000) {
                if (deferred() == 0) {
                    pulse()
                    runtime.plan(city, worker) shouldBe null
                }
            }
            deferred() shouldBe 1
            feet = near
            repeat(200) { pulse(); runtime.plan(city, worker) shouldBe null }
            deferred() shouldBe 1
            city.forestryJobs shouldBe null
            city.stock shouldBe initial.stock
            city.workers shouldBe initial.workers
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
        }
    }

    test("returning to a rejected resident cell keeps a closed tree deferred until its wall changes") {
        forestryWorksite("forestry-revisited-closed-tree", nanoTime = { 0L }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val city = initial.copy(forestrySeeded = setOf(requireNotNull(worker.assignment).toString()))
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            // No ordinary footing can reach this tree. Both real resident cells
            // have support, but the same untouched wall blocks their remote rays.
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if (x != root.x || z != root.z)
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            for (y in 64..66) for (z in root.z - 4..root.z + 4)
                world.getBlockAt(root.x - 8, y, z).type = Material.STONE
            val first = Location(world, root.x - 10.5, 64.0, root.z + .5)
            val second = first.clone().add(0.0, 0.0, 1.0)
            var feet = first
            runtime.residentFeet = { feet }
            for (at in listOf(first, second)) {
                citizenPacketSupported(world, CitizenPoint(at.x, at.y, at.z)) shouldBe true
                citizenPacketBodyClear(world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
                citizenReachableBlockPoint(world, at.clone().add(0.0, 1.62, 0.0), root,
                    CITIZEN_REMOTE_WORK_REACH) shouldBe null
            }
            fun entry() = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            fun deferred() = ((entry()["deferredForestApproaches"] as Map<*, *>)["total"] as Int)
            fun pulse() {
                (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                runtime.pulse(listOf(city))
            }
            fun awaitDeferred() {
                repeat(2_000) {
                    pulse()
                    runtime.plan(city, worker) shouldBe null
                    if (deferred() == 1) return
                }
                error("Closed tree was not deferred: ${runtime.debug(setOf(worker.id))}")
            }
            awaitDeferred()
            val firstRays = world.rayTraces
            feet = second
            // A new angle gets one bounded attempt without needing a block event.
            pulse()
            awaitDeferred()
            (world.rayTraces > firstRays) shouldBe true
            val closedRays = world.rayTraces
            feet = first
            repeat(100) { pulse(); runtime.plan(city, worker) shouldBe null }
            world.rayTraces shouldBe closedRays
            deferred() shouldBe 1

            val unrelated = root.copy(x = root.x + 8)
            world.getBlockAt(unrelated.x, unrelated.y, unrelated.z).type = Material.STONE
            runtime.changed(world.uid, unrelated)
            repeat(20) { pulse(); runtime.plan(city, worker) shouldBe null }
            world.rayTraces shouldBe closedRays
            deferred() shouldBe 1

            // The rejection belongs to this geometry, not to the tree forever.
            for (y in 64..66) for (z in root.z - 4..root.z + 4)
                world.getBlockAt(root.x - 8, y, z).type = Material.AIR
            runtime.changed(world.uid, root.copy(x = root.x - 8))
            var work: CivWorldWork? = null
            repeat(2_000) {
                if (work == null) {
                    pulse()
                    work = runtime.plan(city, worker)
                }
            }
            val reopened = checkNotNull(work) { "Changed wall did not reopen its tree: ${runtime.debug(setOf(worker.id))}" }
            reopened.status shouldBe "felling-tree"
            reopened.focus shouldBe root
            reopened.remoteWork shouldBe true
            runtime.validate(city, reopened)
            city.forestryJobs shouldBe null
            city.stock shouldBe initial.stock
            city.workers shouldBe initial.workers
            for (y in root.y..root.y + 2) world.getBlockAt(root.x, y, root.z).type shouldBe Material.OAK_LOG
        }
    }
})

package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock

class CivsForestryRejectedTargetsTest : FunSpec({
    for (temporary in listOf(false, true))
    test("an unpaid tree preserves terrain rejection but reopens resident occupancy, temporary=$temporary") {
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
            runtime.rejectStanding(city, worker, rejected, temporary = temporary) shouldBe true
            fun deferred(): Int {
                val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                return ((entry["deferredForestApproaches"] as Map<*, *>)["total"] as Int)
            }
            if (temporary) {
                val rejectedAt = Bukkit.getCurrentTick()
                var reopened: CivWorldWork? = null
                repeat(200) {
                    if (reopened == null) {
                        pulse()
                        deferred() shouldBe 0 // A temporary holder must not close this unpaid root forever.
                        reopened = runtime.plan(city, worker)
                        if (Bukkit.getCurrentTick() - rejectedAt < 100) reopened shouldBe null
                    }
                }
                val resumed = checkNotNull(reopened) { "Resident exclusion never expired: ${runtime.debug(setOf(worker.id))}" }
                resumed.standing shouldBe rejected.standing
                resumed.focus shouldBe rejected.focus
                resumed.changes shouldBe rejected.changes
                resumed.inputs shouldBe rejected.inputs
                runtime.validate(city, resumed)
                city.forestryJobs shouldBe null
                city.stock shouldBe initial.stock
                city.workers shouldBe initial.workers
                world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
                return@forestryWorksite
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

    for (temporary in listOf(false, true))
    test("a remote-only tree reopens only an expired resident exclusion, temporary=$temporary") {
        forestryWorksite("forestry-remote-rejection-expiry", nanoTime = { 0L }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val city = initial.copy(forestrySeeded = setOf(requireNotNull(worker.assignment).toString()))
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if (x != root.x || z != root.z)
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            val feet = Location(world, root.x - 10.5, 64.0, root.z + .5)
            runtime.residentFeet = { feet }
            citizenPacketSupported(world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
            fun pulse() {
                (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                runtime.pulse(listOf(city))
            }
            var first: CivWorldWork? = null
            repeat(2_000) {
                if (first == null) { pulse(); first = runtime.plan(city, worker) }
            }
            val original = checkNotNull(first)
            original.focus shouldBe root
            original.remoteWork shouldBe true
            original.standing shouldBe CivPosition(feet.blockX, feet.blockY, feet.blockZ)
            runtime.validate(city, original)
            runtime.rejectStanding(city, worker, original, temporary = temporary) shouldBe true
            val rejectedAt = Bukkit.getCurrentTick()
            repeat(19) { pulse(); runtime.plan(city, worker) shouldBe null }
            Bukkit.getCurrentTick() - rejectedAt shouldBe 95
            if (temporary) {
                var resumed: CivWorldWork? = null
                repeat(200) {
                    if (resumed == null) { pulse(); resumed = runtime.plan(city, worker) }
                }
                val reopened = checkNotNull(resumed) { "Remote occupancy never expired: ${runtime.debug(setOf(worker.id))}" }
                // No durable tree job exists yet; a recapture may allocate a new nonce.
                // Every native cell, progress field, input and target must still match.
                val originalProgress = requireNotNull(original.forestry)
                val reopenedProgress = requireNotNull(reopened.forestry)
                city.forestryJobs shouldBe null
                reopened.copy(forestry = reopenedProgress.copy(job = reopenedProgress.job.copy(id = originalProgress.job.id))) shouldBe original
                runtime.validate(city, reopened)
            } else {
                repeat(240) { pulse(); runtime.plan(city, worker) shouldBe null }
                val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                ((entry["deferredForestApproaches"] as Map<*, *>)["total"] as Int) shouldBe 1
            }
            city.forestryJobs shouldBe null
            city.stock shouldBe initial.stock
            city.workers shouldBe initial.workers
            for (y in root.y..root.y + 2) world.getBlockAt(root.x, y, root.z).type shouldBe Material.OAK_LOG
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

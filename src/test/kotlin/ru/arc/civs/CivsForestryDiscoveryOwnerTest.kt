package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsForestryDiscoveryOwnerTest : FunSpec({
    test("an unarmed first forester leaves native tree discovery to the equipped forester") {
        twoForesters("forestry-owner-axe") { world, runtime, initial, first, second ->
            val city = initial.copy(workers = listOf(first.copy(items = null), second))
            val root = CivPosition(10, 64, 10)
            forestrySmallOak(world, root)
            runtime.residentFeet = { Location(world, root.x + .5, root.y.toDouble(), root.z - 1.5) }
            repeat(12) { if (runtime.plan(city, second) == null) runtime.pulse(listOf(city)) }

            val work = requireNotNull(runtime.plan(city, second)) { "Equipped forester has no tree: ${runtime.debug()}" }
            work.status shouldBe "felling-tree"
            work.focus shouldBe root
            runtime.validate(city, work)
            runtime.plan(city, city.workers.first()) shouldBe null
            city.forestryJobs shouldBe null
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
        }
    }

    test("a mill without a candidate gives its other equipped worker a bounded search turn") {
        twoForesters("forestry-owner-rotation") { _, runtime, city, first, second ->
            runtime.pulse(listOf(city))
            check(forestEntry(runtime, first)["forestSearch"] != null)
            forestEntry(runtime, second)["forestSearch"] shouldBe null
            repeat(64) {
                if (forestEntry(runtime, second)["forestSearch"] == null) runtime.pulse(listOf(city))
            }

            check(forestEntry(runtime, second)["forestSearch"] != null) { "Second forester never scanned: ${runtime.debug()}" }
            runtime.plan(city, first) shouldBe null
            runtime.plan(city, second) shouldBe null
            city.forestryJobs shouldBe null
        }
    }

    test("an active tree and stance scan stays with its owner when an eligible colleague joins") {
        val settings = CivsWorksiteSettings(standingChecksPerPulse = 1)
        twoForesters("forestry-owner-sticky", settings) { world, runtime, city, first, second ->
            val initiallyAlone = city.copy(workers = listOf(second))
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            for (x in root.x - 2..root.x + 2) for (z in root.z - 2..root.z + 2)
                if (x != root.x || z != root.z) for (y in 61..63) world.getBlockAt(x, y, z).type = Material.AIR
            repeat(128) {
                if (forestEntry(runtime, second)["candidateSearch"] == null) runtime.pulse(listOf(initiallyAlone))
            }
            val before = forestEntry(runtime, second)
            (before["forestScan"] as? Map<*, *>)?.get("phase") shouldBe "complete"
            check(before["candidateSearch"] != null)

            runtime.pulse(listOf(city))
            val after = forestEntry(runtime, second)
            (after["forestScan"] as? Map<*, *>)?.get("root") shouldBe "-15,64,-26"
            check(after["candidateSearch"] != null)
            forestEntry(runtime, first)["forestSearch"] shouldBe null
            runtime.plan(city, first) shouldBe null
            city.forestryJobs shouldBe null

            val disarmed = city.copy(workers = listOf(first, second.copy(items = null)))
            runtime.pulse(listOf(disarmed))
            forestEntry(runtime, second)["forestScan"] shouldBe null
            check(forestEntry(runtime, first)["forestSearch"] != null)
        }
    }
})

private fun forestEntry(runtime: CivsWorksites, worker: CivWorker): Map<*, *> =
    (runtime.debug(setOf(worker.id))["entries"] as List<*>).singleOrNull() as? Map<*, *> ?: emptyMap<Any, Any>()

private fun twoForesters(name: String, settings: CivsWorksiteSettings = CivsWorksiteSettings(),
    scenario: (CivsWorksiteWorldFixture, CivsWorksites, Settlement, CivWorker, CivWorker) -> Unit) {
    forestryWorksite(name, autonomous = false, settings = settings) { world, runtime, initial ->
        val original = initial.workers.single()
        val mill = spatialBuilding("nordic", "lumberjack", 2, CivPosition(0, 63, 0))
            .copy(id = requireNotNull(original.assignment), production = "OAK_LOG")
        val first = original.copy(id = UUID(0, 1), name = "First")
        val second = original.copy(id = UUID(0, 2), name = "Second")
        val city = initial.copy(buildings = initial.buildings.map { if (it.id == mill.id) mill else it },
            workers = listOf(first, second), inventory = CivInventory(CivStorageMode.PHYSICAL))
        scenario(world, runtime, city, first, second)
    }
}

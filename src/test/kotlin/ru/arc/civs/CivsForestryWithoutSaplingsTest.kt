package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material

class CivsForestryWithoutSaplingsTest : FunSpec({
    for (automatic in listOf(false, true)) test("a native ${if (automatic) "Auto" else "player"} forester harvests mature trees without a replacement sapling") {
        forestryWorksite("forestry-without-seed-$automatic", autonomous = false) { world, runtime, initial ->
            val worker = initial.workers.single().copy(items = CivsEquipment.slots(initial.workers.single()).map {
                it?.takeUnless { item -> item.material.endsWith("_SAPLING") }
            })
            val city = initial.copy(workers = listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL),
                autonomy = if (automatic) CivAutonomy() else null)
            val root = CivPosition(10, 64, 10)
            forestrySmallOak(world, root)
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            work.status shouldBe "felling-tree"
            work.focus shouldBe root
            work.inputs shouldBe emptyMap()
            work.changes.single { it.position == root }.after shouldBe "minecraft:air"
            runtime.validate(city, work)
            val after = CivsRules.finishWorldWork(city, work, 10_000)
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
            after.stock shouldBe city.stock
        }
    }

    test("an unfunded planting turn yields to a newly grown tree while retaining zone knowledge") {
        forestryWorksite("forestry-planting-does-not-block-harvest", autonomous = false) { world, runtime, initial ->
            var city = initial.copy(workers = initial.workers.map { worker -> worker.copy(items = CivsEquipment.slots(worker).map {
                it?.takeUnless { item -> item.material.endsWith("_SAPLING") }
            }) })
            var worker = city.workers.single()
            val young = CivPosition(-15, 64, -26)
            world.getBlockAt(young.x, young.y, young.z).type = Material.OAK_SAPLING
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val care = requireNotNull(runtime.plan(city, worker))
            care.status shouldBe "watering"
            city = CivsRules.finishWorldWork(city, care, 10_000)
            care.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                .blockData = Bukkit.createBlockData(cell.after) }
            runtime.invalidate(worker.id, care)
            worker = city.workers.single()
            val mature = CivPosition(10, 64, 10)
            forestrySmallOak(world, mature)
            runtime.changed(world.uid, mature)
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            work.status shouldBe "felling-tree"
            work.focus shouldBe mature
            runtime.validate(city, work)
            val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            (entry["forestSearch"] as Map<*, *>)["column"] shouldBe (entry["forestSearch"] as Map<*, *>)["columns"]
        }
    }
})

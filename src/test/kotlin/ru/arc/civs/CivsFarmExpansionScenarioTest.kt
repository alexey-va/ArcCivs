package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletionException
import org.bukkit.Material
import ru.arc.civs.scenarios.SettlementScenario

class CivsFarmExpansionScenarioTest : FunSpec({
    beforeEach { installExpandedFarmCatalog() }
    afterEach { CivsWorksiteLayouts.installCatalog(emptyMap()) }

    test("expanded farmer tills and plants new soil while old beds keep yielding ripe crops") {
        SettlementScenario.run("expanded-farm-harvest-fairness", CivPosition(0, 64, 0), setup = { world ->
            val f = farmExpansionFixture(world)
            sourceCity = CivsRules.expandFarmField(f.city, f.farm.id).copy(revision = 0)
            val farm = sourceCity!!.building(f.farm.id)!!
            buildings["farm"] = farm
            buildings["hall"] = sourceCity!!.buildings.single { it.type == BuildingType.TOWN_HALL }
            workers["Farmer"] = f.worker
            CivsFarmFields.water(farm).forEach {
                world.getBlockAt(it.x, it.y + 1, it.z).type = Material.AIR
                world.getBlockAt(it.x, it.y, it.z).type = Material.WATER
            }
            (CivsFarmFields.soil(farm) - CivsFarmFields.added(farm).toSet()).forEach {
                world.getBlockAt(it.x, it.y + 1, it.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=7]")
            }
        }) {
            val farm = city.buildings.single { it.type == BuildingType.FARM }
            val added = CivsFarmFields.added(farm).toSet() - CivsFarmFields.water(farm).toSet()
            val retained = CivsFarmFields.soil(farm) - added
            val tilled = mutableSetOf<CivPosition>()
            val planted = mutableSetOf<CivPosition>()
            var harvests = 0
            var previousOnAdded = false
            repeat(16) {
                val work = nextWork("Farmer")
                val cell = work.changes.single()
                val ground = cell.position.copy(y = cell.position.y - 1)
                val onAdded = cell.position in added || ground in added
                (previousOnAdded && onAdded) shouldBe false
                previousOnAdded = onAdded
                when {
                    cell.position in added -> {
                        cell.after shouldBe "minecraft:farmland[moisture=7]"
                        work.inputs shouldBe emptyMap()
                        tilled.add(cell.position) shouldBe true
                    }
                    ground in added -> {
                        (ground in tilled) shouldBe true
                        cell.after shouldBe "minecraft:wheat[age=0]"
                        work.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
                        planted.add(ground) shouldBe true
                    }
                    else -> {
                        (ground in retained) shouldBe true
                        work.outputs["WHEAT"] shouldBe 1L
                        harvests++
                    }
                }
                complete(work)
                state(cell.position) shouldBe cell.after
            }
            (planted.size >= 2) shouldBe true
            (harvests >= 4) shouldBe true
            retained.any { state(it.copy(y = it.y + 1)) == "minecraft:wheat[age=7]" } shouldBe true
            receipts() shouldBe emptyList()
        }
    }

    test("an unexpanded farmer restores one frozen authored source through a native receipt") {
        lateinit var irrigation: CivPosition
        SettlementScenario.run("frozen-authored-irrigation", CivPosition(0, 64, 0), setup = { world ->
            val f = farmExpansionFixture(world)
            sourceCity = f.city.copy(revision = 0)
            buildings["farm"] = f.farm
            buildings["hall"] = sourceCity!!.buildings.single { it.type == BuildingType.TOWN_HALL }
            workers["Farmer"] = f.worker
            irrigation = CivsWorksiteLayouts.irrigation(f.farm).single()
            world.getBlockAt(irrigation.x, irrigation.y, irrigation.z).type = Material.ICE
        }) {
            val thaw = nextWork("Farmer")
            thaw.changes shouldBe listOf(CivPlanBlock(irrigation, "minecraft:ice", "minecraft:water[level=0]"))
            thaw.inputs shouldBe emptyMap()
            thaw.outputs shouldBe emptyMap()
            state(irrigation) shouldBe "minecraft:ice"
            complete(thaw)
            state(irrigation) shouldBe "minecraft:water[level=0]"
            receipts() shouldBe emptyList()
        }
    }

    test("admitted irrigation weeds clear through ordinary receipts before separate water and real seed-paid planting") {
        val weeds = listOf(listOf("minecraft:short_grass"), listOf("minecraft:fern"),
            listOf("minecraft:tall_grass[half=lower]", "minecraft:tall_grass[half=upper]"),
            listOf("minecraft:large_fern[half=lower]", "minecraft:large_fern[half=upper]"))
        val occupied = linkedMapOf<CivPosition, List<String>>()
        lateinit var youngCrop: CivPosition
        SettlementScenario.run("expanded-farm-irrigation-weeds", CivPosition(0, 64, 0), setup = { world ->
            val f = farmExpansionFixture(world)
            CivsFarmFields.added(f.farm).filter { it in CivsFarmFields.water(f.farm) }.take(weeds.size)
                .zip(weeds).forEach { (point, states) ->
                    occupied[point] = states
                    states.forEachIndexed { index, state ->
                        world.getBlockAt(point.x, point.y + index + 1, point.z).blockData = org.bukkit.Bukkit.createBlockData(state)
                    }
                }
            // These native weeds are admitted; admission itself must leave the world untouched.
            CivsFarmExpansion.validate(f.city, f.farm, CivAutonomousAccess(), world, listOf(f.city)) { _, _ -> true }
            val expanded = CivsRules.expandFarmField(f.city, f.farm.id)
            sourceCity = expanded.copy(revision = 0)
            val farm = sourceCity!!.building(f.farm.id)!!
            buildings["farm"] = farm
            buildings["hall"] = sourceCity!!.buildings.single { it.type == BuildingType.TOWN_HALL }
            workers["Farmer"] = f.worker
            val unfinished = CivsFarmFields.draining(farm).first()
            youngCrop = unfinished.copy(y = unfinished.y + 1)
            // Represent a partly completed transition; one genuine immature channel crop still waits.
            CivsFarmFields.water(farm).filter { it !in occupied && it != unfinished }.forEach {
                world.getBlockAt(it.x, it.y + 1, it.z).type = Material.AIR
                world.getBlockAt(it.x, it.y, it.z).type = Material.WATER
            }
        }) {
            val young = snapshot(listOf(youngCrop))
            val seedsBefore = CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS")
            for ((ground, states) in occupied) {
                val clear = nextWork("Farmer")
                clear.status shouldBe "working-field"
                clear.durationMillis shouldBe 1_500L
                clear.autonomousVisual shouldBe false
                clear.inputs shouldBe emptyMap()
                clear.outputs shouldBe emptyMap()
                clear.changes.associate { it.position to it.before } shouldBe states.mapIndexed { index, state ->
                    ground.copy(y = ground.y + index + 1) to state
                }.toMap()
                clear.changes.all { it.after == "minecraft:air" } shouldBe true
                state(ground) shouldBe "minecraft:dirt"
                complete(clear)
                state(ground.copy(y = ground.y + 1)) shouldBe "minecraft:air"
                state(ground.copy(y = ground.y + 2)) shouldBe "minecraft:air"
                state(ground) shouldBe "minecraft:dirt"
                unchanged(young)
                val water = nextWork("Farmer")
                water.changes.single().position shouldBe ground
                water.changes.single().after shouldBe "minecraft:water[level=0]"
                water.inputs shouldBe emptyMap()
                water.outputs shouldBe emptyMap()
                complete(water)
                unchanged(young)
                CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS") shouldBe seedsBefore
                CivsEquipment.plainCount(city.workers.single(), "WHEAT") shouldBe 0L
                receipts() shouldBe emptyList()
            }
            put(youngCrop, "minecraft:wheat[age=7]")
            val harvest = nextWork("Farmer")
            harvest.changes.single().position shouldBe youngCrop
            harvest.changes.single().after shouldBe "minecraft:air"
            harvest.inputs shouldBe emptyMap()
            complete(harvest)
            val channel = nextWork("Farmer")
            channel.changes.single().position shouldBe youngCrop.copy(y = youngCrop.y - 1)
            channel.changes.single().after shouldBe "minecraft:water[level=0]"
            complete(channel)
            val seedsAfterHarvest = CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS")
            val till = nextWork("Farmer")
            till.changes.single().after shouldBe "minecraft:farmland[moisture=7]"
            till.inputs shouldBe emptyMap()
            till.outputs shouldBe emptyMap()
            complete(till)
            val plant = nextWork("Farmer")
            plant.changes.single().position shouldBe till.changes.single().position.copy(y = till.changes.single().position.y + 1)
            plant.changes.single().before shouldBe "minecraft:air"
            plant.changes.single().after shouldBe "minecraft:wheat[age=0]"
            plant.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
            plant.outputs shouldBe emptyMap()
            complete(plant)
            CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS") shouldBe seedsAfterHarvest - 1
            receipts() shouldBe emptyList()
        }
    }

    test("one immature channel crop leaves adjacent watered beds available to till and plant") {
        lateinit var immature: CivPosition
        SettlementScenario.run("expanded-farm-partial-channel", CivPosition(0, 64, 0), setup = { world ->
            val f = farmExpansionFixture(world)
            sourceCity = CivsRules.expandFarmField(f.city, f.farm.id).copy(revision = 0)
            val farm = sourceCity!!.building(f.farm.id)!!
            buildings["farm"] = farm
            buildings["hall"] = sourceCity!!.buildings.single { it.type == BuildingType.TOWN_HALL }
            workers["Farmer"] = f.worker
            val channelSoil = CivsFarmFields.draining(farm).first()
            immature = channelSoil.copy(y = channelSoil.y + 1)
            CivsFarmFields.water(farm).filter { it != channelSoil }.forEach {
                world.getBlockAt(it.x, it.y + 1, it.z).type = Material.AIR
                world.getBlockAt(it.x, it.y, it.z).type = Material.WATER
            }
        }) {
            val crop = snapshot(listOf(immature))
            val till = nextWork("Farmer")
            val plot = till.changes.single().position
            till.changes.single().after shouldBe "minecraft:farmland[moisture=7]"
            complete(till)
            val sow = nextWork("Farmer")
            sow.changes.single().position shouldBe plot.copy(y = plot.y + 1)
            sow.changes.single().after shouldBe "minecraft:wheat[age=0]"
            sow.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
            complete(sow)
            unchanged(crop)
        }
    }

    test("expanded physical farmer harvests channel crops once, recovers a legacy harvest and pays for all 48 plantings") {
        SettlementScenario.run("expanded-farm-journal", CivPosition(0, 64, 0), setup = { world ->
            val f = farmExpansionFixture(world)
            val expanded = CivsRules.expandFarmField(f.city, f.farm.id)
            // A versioned starting fixture; source admission/legacy codec are exercised independently.
            sourceCity = expanded.copy(revision = 0)
            buildings["farm"] = sourceCity!!.building(f.farm.id)!!
            buildings["hall"] = sourceCity!!.buildings.single { it.type == BuildingType.TOWN_HALL }
            workers["Farmer"] = f.worker
            val farm = buildings.getValue("farm")
            val draining = CivsFarmFields.draining(farm)
            CivsFarmFields.water(farm).filter { it !in draining }.forEach {
                world.getBlockAt(it.x, it.y, it.z).type = Material.WATER
            }
            val retained = CivsWorksiteLayouts.points(f.farm, "farm", "soilCells").first { it !in draining }
            world.getBlockAt(retained.x, retained.y + 1, retained.z).blockData =
                org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=7]")
        }) {
            val farm = city.buildings.single { it.type == BuildingType.FARM }
            val draining = CivsFarmFields.draining(farm)
            val young = snapshot(draining.map { it.copy(y = it.y + 1) })
            val normal = nextWork("Farmer")
            (normal.focus!!.copy(y = normal.focus!!.y - 1) !in draining) shouldBe true
            normal.changes.single().after shouldBe "minecraft:wheat[age=0]"
            complete(normal)
            unchanged(young)
            val newSoil = CivsFarmFields.added(farm).filter { it !in CivsFarmFields.water(farm) }.toSet()
            newSoil.size shouldBe 48
            newSoil.all { state(it) == "minecraft:dirt" } shouldBe true
            draining.forEach { put(it.copy(y = it.y + 1), "minecraft:wheat[age=7]") }

            val harvest = nextWork("Farmer")
            val retired = harvest.focus!!.copy(y = harvest.focus!!.y - 1)
            (retired in draining) shouldBe true
            harvest.inputs shouldBe emptyMap()
            harvest.outputs shouldBe mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 2L)
            harvest.changes.single().after shouldBe "minecraft:air"
            val before = city
            val farmerBefore = before.workers.single()
            val seedsBefore = CivsEquipment.plainCount(farmerBefore, "WHEAT_SEEDS")
            checkpoint()

            val crop = requireNotNull(harvest.focus)
            val completion = observedCompletion(harvest)
            completion.isDone shouldBe true
            val harvested = completion.join()
            city shouldBe harvested
            harvested.revision shouldBe before.revision + 1
            val farmerAfter = harvested.workers.single()
            farmerAfter.completedCycles shouldBe farmerBefore.completedCycles + 1
            CivsEquipment.plainCount(farmerAfter, "WHEAT_SEEDS") shouldBe seedsBefore + 2
            CivsEquipment.plainCount(farmerAfter, "WHEAT") shouldBe CivsEquipment.plainCount(farmerBefore, "WHEAT") + 1
            state(crop) shouldBe "minecraft:air"
            receipts() shouldBe emptyList()
            savedCity() shouldBe before
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                observedCompletion(harvest, farmerBefore.nextDeliveryAt).join()
            })?.reason shouldBe "stale-delivery"
            city shouldBe harvested

            // Recreate a pre-upgrade crash with its saved intent and its original ripe block.
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, harvested, emptyList(),
                worldChanges = harvest.changes)
            put(crop, harvest.changes.single().before)
            seedLegacyInventoryReceipt(receipt)
            receipts().single().validated() shouldBe receipt
            restart()
            advanceUntil("legacy harvest publication") { city == harvested }
            state(crop) shouldBe "minecraft:air"
            restart()
            city shouldBe harvested
            receipts() shouldBe emptyList()

            val water = nextWork("Farmer")
            water.changes.single().position shouldBe retired
            water.changes.single().after shouldBe "minecraft:water[level=0]"
            water.inputs shouldBe emptyMap()
            water.outputs shouldBe emptyMap()
            complete(water)
            CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS") shouldBe seedsBefore + 2
            repeat(7) {
                val nextHarvest = nextWork("Farmer")
                (nextHarvest.focus!!.copy(y = nextHarvest.focus!!.y - 1) in draining) shouldBe true
                nextHarvest.changes.single().after shouldBe "minecraft:air"
                nextHarvest.inputs shouldBe emptyMap()
                nextHarvest.outputs["WHEAT_SEEDS"] shouldBe 2L
                complete(nextHarvest)
                val nextWater = nextWork("Farmer")
                nextWater.changes.single().position shouldBe nextHarvest.focus!!.copy(y = nextHarvest.focus!!.y - 1)
                nextWater.changes.single().after shouldBe "minecraft:water[level=0]"
                complete(nextWater)
            }
            CivsFarmFields.water(farm).all { state(it) == "minecraft:water[level=0]" } shouldBe true
            val seedBalance = CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS")
            val planted = mutableSetOf<CivPosition>()
            var tilled = 0
            repeat(96) {
                // The complete field takes more than one workday. Let an open batch resume at dawn.
                val work = nextWork("Farmer", maximumPulses = 12_000)
                work.durationMillis shouldBe 1_500L
                work.autonomousVisual shouldBe false
                work.outputs shouldBe emptyMap()
                val cell = work.changes.single()
                if (cell.after == "minecraft:farmland[moisture=7]") {
                    (cell.position in newSoil) shouldBe true
                    work.inputs shouldBe emptyMap()
                    tilled++
                } else {
                    cell.before shouldBe "minecraft:air"
                    cell.after shouldBe "minecraft:wheat[age=0]"
                    (cell.position.copy(y = cell.position.y - 1) in newSoil) shouldBe true
                    work.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
                    planted.add(cell.position) shouldBe true
                }
                complete(work)
            }
            tilled shouldBe 48
            planted.size shouldBe 48
            CivsEquipment.plainCount(city.workers.single(), "WHEAT_SEEDS") shouldBe seedBalance - 48
            city.buildings.single { it.id == farm.id } shouldBe farm
            city.workers.size shouldBe 1
            receipts() shouldBe emptyList()
            val completed = city
            checkpoint()
            restart()
            city shouldBe completed
        }
    }
})

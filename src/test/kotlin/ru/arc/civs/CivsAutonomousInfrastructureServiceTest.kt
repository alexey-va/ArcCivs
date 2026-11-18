package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Location

class CivsAutonomousInfrastructureServiceTest : FunSpec({
    test("operator terrain capture rejects an unready service and a worker outside the selected city without native access") {
        serviceScenario { h ->
            val worker = h.city.workers.first()
            val origin = CivPosition(8, 55, 8)
            var feetReads = 0
            every { h.citizens.residentFeet(any()) } answers { feetReads++; null }
            h.service.debugTerrainAt(h.city.id, worker.id, origin)["error"] shouldBe "not-ready"
            h.start()
            val cities = h.service.settlements()
            val saves = h.saves.toList()
            val reads = feetReads
            h.service.debugTerrainAt(UUID.randomUUID(), worker.id, origin)["error"] shouldBe "city-not-found"
            h.service.debugTerrainAt(h.city.id, UUID.randomUUID(), origin)["error"] shouldBe "worker-not-found"
            h.service.settlements() shouldBe cities
            h.saves shouldBe saves
            feetReads shouldBe reads
        }
    }

    test("historical access repair precedes growth with empty stock and publishes no construction or resource credit") {
        serviceScenario { h ->
            val city = h.city.copy(autonomy = CivAutonomy(), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), workers = emptyList())
            val older = city.buildings.first()
            val offender = city.buildings.last()
            val write = CivPlanBlock(older.entrance, "minecraft:air", "minecraft:coarse_dirt")
            val plan = CivPlacementPlan(offender.id, city.id, city.worldId, offender, emptyList(),
                terrain = CivTerraformPlan(offender.bounds, listOf(write), listOf(offender.entrance), 63, 0, 1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(offender.id) } returns CompletableFuture.completedFuture(plan)
            var repair = CompletableFuture<Map<String, Int>>()
            every { h.worldAdapter.repairAutonomousAccess(city, any()) } answers { repair }
            h.start()
            h.paper.addPlayer("AccessOwner").teleport(Location(h.world, 0.5, 65.0, 0.5))
            val development = autonomousDevelopment(h.service)
            development.pulseAccessRepair(); h.tick()
            development.accessReady(city) shouldBe false
            verify(exactly = 1) { h.worldAdapter.repairAutonomousAccess(city, listOf(
                CivAutonomousAccessRepair(older.id, offender.id, older.entrance,
                    write.copy(before = write.after, after = write.before)))) }
            h.saves shouldBe emptyList()
            repair.complete(mapOf("changed" to 0, "unchanged" to 0, "conflicts" to 0, "denied" to 1)); h.tick()
            development.accessReady(city) shouldBe false
            development.pulseAccessRepair()
            verify(exactly = 1) { h.worldAdapter.repairAutonomousAccess(any(), any()) }
            repair = CompletableFuture()
            h.time += 30_001
            development.pulseAccessRepair(); h.tick()
            repair.complete(mapOf("changed" to 1, "unchanged" to 0, "conflicts" to 0, "denied" to 0)); h.tick()
            development.accessReady(city) shouldBe true
            val productionChanged = city.copy(buildings = city.buildings.map { building ->
                if (building.id == offender.id) building.copy(production = "SPRUCE_LOG") else building
            })
            development.accessReady(productionChanged) shouldBe true
            development.accessReady(city.copy(buildings = city.buildings.map { building ->
                if (building.id == offender.id) building.copy(entrance = building.entrance.copy(x = building.entrance.x + 1)) else building
            })) shouldBe false
            development.pulseAccessRepair(); h.tick()
            verify(exactly = 2) { h.worldAdapter.repairAutonomousAccess(any(), any()) }
            h.service.settlements().single().stock shouldBe Stock()
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
        }
    }

    for (atInfrastructureCap in listOf(false, true))
    test("a planless town immediately searches its next goal without a full forecast atInfrastructureCap=$atInfrastructureCap") {
        mockkConstructor(CivsAutonomousPlacement::class)
        try {
            every { anyConstructed<CivsAutonomousPlacement>().plan(any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-no-site", mapOf("type" to "SMELTERY")))
            every { anyConstructed<CivsAutonomousPlacement>().search(any(), any(), any(), any()) } returns CompletableFuture()
            serviceScenario { h ->
                val oldWalls = if (!atInfrastructureCap) emptyList() else (0 until CivsRules.MAX_INFRASTRUCTURE).map { i ->
                    val at = CivPosition(30 + i % 12 * 2, 64, 30 + i / 12 * 2)
                    CivBuilding(UUID.randomUUID(), BuildingType.WALL, 1, at, 0, "civs/infrastructure/v1/wall/fixture",
                        CivBox(at, at.copy(y = 67)), at.copy(z = at.z - 1), BuildingPhase.ACTIVE)
                }
                val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), workers = emptyList(), stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL), buildings = h.city.buildings + oldWalls))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
                h.start()
                h.paper.addPlayer("GrowthObserver").teleport(Location(h.world, 0.5, 65.0, 0.5))
                val development = autonomousDevelopment(h.service)
                development.pulseAccessRepair(); h.tick()
                h.time += 5_001
                development.pulse(); h.tick()
                verify(exactly = 0) { anyConstructed<CivsAutonomousPlacement>().plan(any()) }
                verify(exactly = 1) { anyConstructed<CivsAutonomousPlacement>().search(any(), BuildingType.BUILDER_HUT, 1, any()) }
                h.time += 30_001
                development.pulse(); h.tick()
                verify(exactly = 0) { anyConstructed<CivsAutonomousPlacement>().plan(any()) }
                verify(exactly = 1) { anyConstructed<CivsAutonomousPlacement>().search(any(), BuildingType.BUILDER_HUT, 1, any()) }
                val after = h.service.settlements().single()
                after.buildings shouldBe city.buildings
                after.stock shouldBe city.stock
                after.autonomy?.plan shouldBe null
                h.saves shouldBe emptyList()
            }
        } finally { unmockkConstructor(CivsAutonomousPlacement::class) }
    }

    test("autonomous pause is durable separately from the administrators personal town and rechecks permission") {
        serviceScenario { h ->
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val autonomous = h.city.copy(id = UUID.randomUUID(), workers = emptyList(), autonomy = CivAutonomy())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, autonomous))
            h.start()
            val pause = h.service.setAutonomousPaused(h.player, autonomous.id, true)
            h.commit(); pause.join()
            h.service.city(h.player)?.id shouldBe personal.id
            h.service.settlements().single { it.id == autonomous.id }.autonomy?.paused shouldBe true
            h.service.settlements().single { it.id == autonomous.id }.stock shouldBe autonomous.stock
            every { h.player.hasPermission("arccivs.admin") } returns false
            val resume = h.service.setAutonomousPaused(h.player, autonomous.id, false)
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { resume.join() })?.reason shouldBe "not-authorized"
            h.saves.size shouldBe 1
        }
    }

    test("layout debug exports city and bounded runtime snapshots without native capture or city writes by default") {
        serviceScenario { h ->
            h.start()
            val result = h.service.debugLayout(h.city.id, false)
            val receipt = h.await(result)
            receipt["city"] shouldBe h.city.id
            receipt["source_revision"] shouldBe h.city.revision
            receipt["layout_unchanged"] shouldBe true
            val file = h.plugin.dataFolder.toPath().resolve(receipt["path"].toString())
            val document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonObject
            document.getAsJsonObject("city")["world"].asString shouldBe h.world.uid.toString()
            document.getAsJsonObject("runtime").keySet().containsAll(listOf("citizens", "worksites", "supply", "coverage")) shouldBe true
            document["surface"].isJsonNull shouldBe true
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) }
            h.saves shouldBe emptyList()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.service.debugLayout(UUID.randomUUID(), false).join() })
                ?.reason shouldBe "city-not-found"
        }
    }

    test("explicit layout terrain uses the existing loaded-only height field with the pinned hall") {
        serviceScenario { h ->
            val hall = h.city.buildings.first()
            val surface = CivTerrainSurface(-64, 320, mapOf(civsTerrainKey(0, 0) to CivTerrainSurfaceColumn(63, true)))
            val waterColumns = civsAutonomousWaterColumns(CivsSettlementLayout.frame(h.city),
                CivsSettlementPlans.current(h.city)?.gates.orEmpty())
            every { h.worldAdapter.autonomousHeightField(h.city, hall, hall, waterColumns) } returns CompletableFuture.completedFuture(surface)
            h.start()
            val receipt = h.await(h.service.debugLayout(h.city.id, true))
            val document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                h.plugin.dataFolder.toPath().resolve(receipt["path"].toString()))).asJsonObject
            document.getAsJsonObject("surface")["loaded_only"].asBoolean shouldBe true
            document.getAsJsonObject("surface")["atomic"].asBoolean shouldBe false
            verify(exactly = 1) { h.worldAdapter.autonomousHeightField(h.city, hall, hall, waterColumns) }
            h.saves shouldBe emptyList()
        }
        serviceScenario { h ->
            val unavailable = h.city.copy(worldId = UUID.randomUUID())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(unavailable))
            h.start()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.service.debugLayout(unavailable.id, true).join() })
                ?.reason shouldBe "terrain-world-not-loaded"
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) }
            h.saves shouldBe emptyList()
        }
    }

    test("global worksite debug includes the ninth city's cached need beyond the city row sample") {
        serviceScenario { h ->
            val forester = h.city.buildings.last().copy(type = BuildingType.FORESTER,
                schematicId = "civs/minecolonies/nordic/fundamentals/lumberjack1", production = "OAK_LOG")
            val worker = h.city.workers.first().copy(assignment = forester.id, nextDeliveryAt = 1)
            val ninth = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), forester),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL)))
            val earlier = List(8) { h.city.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), workers = emptyList()) }
            val cities = earlier + ninth
            every { h.store.load() } returns CompletableFuture.completedFuture(cities)
            h.start()
            h.paper.performTicks(5) // The only eligible worksite belongs to the ninth city.
            h.service.settlements().map { it.id } shouldBe cities.map { it.id }
            val global = h.service.debugState("worksites", null, null)
            global["total"] shouldBe 1
            global["truncated"] shouldBe false
            val entry = (global["entries"] as List<*>).single() as Map<*, *>
            entry["worker"] shouldBe worker.id.toString()
            (entry["need"] as Map<*, *>)["building"] shouldBe forester.id.toString()
            h.service.debugState("worksites", earlier.first().id, null)["total"] shouldBe 0
            h.service.debugState("worksites", ninth.id, null)["entries"] shouldBe global["entries"]
            h.saves shouldBe emptyList()
        }
    }
})

class CivsAutonomousInfrastructureFallbackTest : FunSpec({
    test("a proven unavailable new industry site gives paid infrastructure one attempt in the same request") {
        infrastructureFallbackScenario { h, city, placement, development ->
            val ordinary = CompletableFuture<CivAutonomousDesign>()
            val infrastructure = CompletableFuture<CivAutonomousDesign>()
            every { placement.search(any(), BuildingType.CRUSHER, 1, any()) } returns ordinary
            every { placement.infrastructure(city) } returns infrastructure
            development.pulse(); h.tick()
            h.tickUntil("ordinary site search admitted") { development.debug(city.id)["request"] != null }
            val request = development.debug(city.id)["request"]
            (request != null) shouldBe true
            verify(exactly = 1) { placement.search(city, BuildingType.CRUSHER, 1, any()) }
            verify(exactly = 0) { placement.infrastructure(any()) }
            ordinary.completeExceptionally(CivsRejection("autonomy-no-site")); h.tick()
            verify(exactly = 1) { placement.infrastructure(city) }
            development.debug(city.id)["request"] shouldBe request
            h.time += 5_001; development.pulse(); h.tick()
            verify(exactly = 1) { placement.search(any(), any(), any(), any()) }
            verify(exactly = 1) { placement.infrastructure(any()) }

            val at = CivPosition(60, 64, 0)
            val wall = CivBuilding(UUID.randomUUID(), BuildingType.WALL, 1, at, 0,
                "civs/infrastructure/v1/wall/fallback", CivBox(at, at.copy(x = 62, y = 68)),
                at.copy(z = -1), BuildingPhase.SITE)
            every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture()
            infrastructure.complete(CivAutonomousDesign(CivBlueprint(wall, city.worldId,
                mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf()))); h.tick()
            verify(exactly = 1) { h.worldAdapter.captureAutonomousSite(city.id, city.owner, city.worldId,
                wall, emptyList(), null) }
            h.service.settlements().single() shouldBe city
            h.saves shouldBe emptyList()
        }
    }

    test("unchanged industry and perimeter refusals both back off until their five minute input memo expires") {
        infrastructureFallbackScenario { h, city, placement, development ->
            every { placement.search(any(), any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-no-site"))
            every { placement.infrastructure(any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-no-site", mapOf("reason" to "autonomy-terrain")))
            development.pulse(); h.tick()
            verify(exactly = 1) { placement.infrastructure(city) }
            h.time += 30_001; development.pulse(); h.tick()
            verify(exactly = 1) { placement.search(any(), BuildingType.CRUSHER, 1, any()) }
            verify(exactly = 1) { placement.infrastructure(any()) }
            (development.debug(city.id)["geometric_refusal"] as Map<*, *>)["cached_goals"] shouldBe 2
            h.time += 300_001; development.pulse(); h.tick() // Both unchanged-input memos expire.
            h.time += 30_001; development.pulse(); h.tick()
            verify(exactly = 2) { placement.infrastructure(city) }
            verify(exactly = 2) { placement.search(any(), BuildingType.CRUSHER, 1, any()) }
            h.service.settlements().single() shouldBe city
            h.saves shouldBe emptyList()
        }
    }

    for (reason in listOf("world-busy", "chunks-unloaded"))
    test("a temporary ordinary search failure never starts infrastructure reason=$reason") {
        infrastructureFallbackScenario { h, city, placement, development ->
            every { placement.search(any(), any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection(reason))
            development.pulse(); h.tick()
            verify(exactly = 1) { placement.search(city, BuildingType.CRUSHER, 1, any()) }
            verify(exactly = 0) { placement.infrastructure(any()) }
            h.service.settlements().single() shouldBe city
            h.saves shouldBe emptyList()
        }
    }

    test("a failed existing board refit never falls back to infrastructure") {
        infrastructureFallbackScenario(refit = true) { h, city, placement, development ->
            val board = city.buildings.single { it.type == BuildingType.PLAN_BOARD }
            every { h.blueprints.preparePlanBoardRefit(board, city.worldId) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-no-site"))
            development.pulse(); h.tick()
            verify(exactly = 1) { h.blueprints.preparePlanBoardRefit(board, city.worldId) }
            verify(exactly = 0) { placement.infrastructure(any()) }
            verify(exactly = 0) { placement.search(any(), any(), any(), any()) }
            h.service.settlements().single() shouldBe city
            h.saves shouldBe emptyList()
        }
    }
})

private fun infrastructureFallbackScenario(refit: Boolean = false,
    action: (ServiceScenario, Settlement, CivsAutonomousPlacement, CivsAutonomousDevelopment) -> Unit) {
    mockkConstructor(CivsAutonomousPlacement::class)
    try {
        serviceScenario { h ->
            val types = CivsAutonomy.baseProfile + listOf(BuildingType.HOUSE, BuildingType.HOUSE,
                BuildingType.UNIVERSITY, BuildingType.SMELTERY)
            val buildings = types.mapIndexed { index, type ->
                val at = CivPosition(-30 + index % 4 * 20, 64, -30 + index / 4 * 20)
                CivBuilding(UUID.randomUUID(), type, minOf(2, type.maxLevel), at, 0,
                    if (type == BuildingType.PLAN_BOARD) {
                        if (refit) CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID else CivsPlanBoardBlueprint.SCHEMATIC_ID
                    } else CivsBlueprints.schematicId("nordic", type, minOf(2, type.maxLevel)),
                    CivBox(at.copy(x = at.x - 2, y = 63, z = at.z - 2), at.copy(x = at.x + 2, y = 68, z = at.z + 2)),
                    at.copy(z = at.z - 3), BuildingPhase.ACTIVE, at)
            }
            val paid = listOf(-10, 10).map { z ->
                val at = CivPosition(-60, 64, z)
                CivBuilding(UUID.randomUUID(), BuildingType.WALL, 1, at, 0, "civs/infrastructure/v1/wall/paid",
                    CivBox(at, at.copy(x = -58, y = 68)), at.copy(x = -61), BuildingPhase.ACTIVE)
            }
            val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(layoutHalfWidth = 56), workers = emptyList(),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), buildings = buildings + paid,
                research = CivResearchState(CivResearchId.entries.toSet())))
            CivsAutonomy.nextGoal(city)?.type shouldBe if (refit) BuildingType.PLAN_BOARD else BuildingType.CRUSHER
            val placement = CivsService::class.java.getDeclaredField("autonomousPlacement").also { it.isAccessible = true }
                .get(h.service) as CivsAutonomousPlacement
            every { placement.plan(any()) } returns CompletableFuture.failedFuture(CivsRejection("autonomy-no-site"))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            h.paper.addPlayer("PerimeterObserver").teleport(Location(h.world, 0.5, 65.0, 0.5))
            val development = autonomousDevelopment(h.service)
            development.pulseAccessRepair(); h.tick()
            h.time += 5_001
            verify(exactly = 0) { placement.plan(any()) }
            development.accessReady(city) shouldBe true
            h.service.settlements().single() shouldBe city
            h.saves shouldBe emptyList()
            action(h, city, placement, development)
        }
    } finally { unmockkConstructor(CivsAutonomousPlacement::class) }
}

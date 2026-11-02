package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsWallInfillServiceTest : FunSpec({
    test("console wall infill requires all three identities and preview or apply intent") {
        val requests = mutableListOf<List<Any?>>()
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk(relaxed = true), infillWall = { city, gate, wall, proof ->
            requests += listOf(city, gate, wall, proof); CompletableFuture()
        })
        val console = mockk<ConsoleCommandSender>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val command = mockk<Command>()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val args = arrayOf("infill-wall", "preview", *ids.map(UUID::toString).toTypedArray())
        router.onCommand(player, command, "civs", args)
        router.onCommand(console, command, "civs", args + "extra")
        router.onCommand(console, command, "civs", args.copyOf().also { it[4] = "bad" })
        requests shouldBe emptyList()
        router.onCommand(console, command, "civs", args)
        router.onCommand(console, command, "civs", args.copyOf().also { it[1] = "apply" } + "a".repeat(64))
        requests shouldBe listOf(ids + listOf(null), ids + listOf("a".repeat(64)))
        CivsCommand.completions(true, true, arrayOf("infill-wall", ""), console = true) shouldBe listOf("preview", "apply")
        CivsCommand.completions(true, true, arrayOf("infill-wall"), console = false) shouldBe emptyList()
    }

    test("preview writes no city and apply uses ordinary saved SITE admission with an exact bill") {
        serviceScenario { h ->
            val f = installWallInfill(h)
            h.start()
            val preview = h.await(h.service.infillWall(f.city.id, f.gate.id, f.wall.id, null))
            preview["state"] shouldBe "preview"
            preview["materials"] shouldBe mapOf("DARK_OAK_FENCE" to 2L, "STONE_BRICKS" to 26L)
            h.saves shouldBe emptyList()
            val applied = h.service.infillWall(f.city.id, f.gate.id, f.wall.id, preview["proof"] as String)
            h.tickUntil("infill site capture") { h.saves.isNotEmpty() || applied.isDone }
            if (applied.isDone) applied.join()
            h.saves.size shouldBe 1
            applied.isDone shouldBe false
            val queued = h.saves.single().first
            queued.stock shouldBe f.city.stock
            queued.inventory shouldBe f.city.inventory
            queued.construction shouldBe f.city.construction
            queued.buildings.filter { it.id != preview["building"] } shouldBe f.city.buildings
            queued.building(preview["building"] as UUID)!!.phase shouldBe BuildingPhase.SITE
            h.commit()
            val result = h.await(applied)
            result["state"] shouldBe "queued"
            result["existing_order_unchanged"] shouldBe true
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a change between reviewed capture and final normal recapture refuses queue admission") {
        serviceScenario { h ->
            val f = installWallInfill(h, driftAtCapture = 3)
            h.start()
            val preview = h.await(h.service.infillWall(f.city.id, f.gate.id, f.wall.id, null))
            val result = h.service.infillWall(f.city.id, f.gate.id, f.wall.id, preview["proof"] as String)
            val failure = shouldThrow<CompletionException> { h.await(result) }
            (CivsDiagnostics.rejection(failure) ?: throw failure).reason shouldBe "wall-infill-stale-preview"
            h.saves shouldBe emptyList()
            h.service.settlements().single().buildings shouldBe f.city.buildings
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("explicit wall queues behind the old SITE while actual activation still waits for admitted road work") {
        serviceScenario { h ->
            val road = roadAdmissionCity()
            val hut = road.buildings[1]
            val oldSite = CivBuilding(UUID(188, 1), BuildingType.WALL, 1, CivPosition(-20, 63, 20), 0,
                "civs/infrastructure/v1/wall/${UUID(188, 1)}", CivBox(CivPosition(-21, 63, 20), CivPosition(-19, 67, 22)),
                CivPosition(-20, 64, 19), BuildingPhase.SITE)
            val f = installWallInfill(h, cityTransform = { city -> CivsRules.validate(city.copy(
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = city.buildings + hut + road.buildings.last() + oldSite,
                workers = road.workers, constructionQueue = listOf(oldSite.id))) })
            val roadBuilding = road.buildings.last()
            val roadPlan = CivPlacementPlan(roadBuilding.id, f.city.id, f.city.worldId, roadBuilding,
                listOf(CivPlanBlock(roadBuilding.anchor, "minecraft:dirt", "minecraft:cobblestone")))
            val oldPlan = CivPlacementPlan(oldSite.id, f.city.id, f.city.worldId, oldSite.copy(phase = BuildingPhase.CONSTRUCTING),
                listOf(CivPlanBlock(oldSite.anchor, "minecraft:air", "minecraft:stone_bricks")), "a".repeat(64))
            val hutPlan = CivPlacementPlan(hut.id, f.city.id, f.city.worldId, hut, emptyList(), terrain = CivTerraformPlan(
                hut.bounds.copy(max = hut.bounds.max.copy(z = hut.entrance.z)), emptyList(), listOf(hut.entrance), 63, 0, 0))
            every { h.store.loadPlan(roadPlan.id) } returns CompletableFuture.completedFuture(roadPlan)
            every { h.store.loadPlan(oldPlan.id) } returns CompletableFuture.completedFuture(oldPlan)
            every { h.store.loadPlan(hutPlan.id) } returns CompletableFuture.completedFuture(hutPlan)
            every { h.worldAdapter.captureRoadUpkeep(any(), any(), any(), any()) } answers {
                roadAdmissionWork(firstArg(), thirdArg())
            }
            h.start()
            val preview = h.await(h.service.infillWall(f.city.id, f.gate.id, f.wall.id, null))
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            val worker = f.city.workers.single()
            // The existing SITE was queued after this column had already been selected.
            val work = requireNotNull(maintenance.plan(f.city.copy(constructionQueue = null), worker))
            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            maintenance.admitDevelopment(f.city) shouldBe false

            val applied = h.service.infillWall(f.city.id, f.gate.id, f.wall.id, preview["proof"] as String)
            h.tickUntil("explicit wall queued without starting its repair or build") { h.saves.isNotEmpty() || applied.isDone }
            if (applied.isDone) applied.join()
            val queued = h.saves.single().first
            queued.constructionQueue shouldBe listOf(oldSite.id, preview["building"] as UUID)
            queued.construction shouldBe f.city.construction
            queued.stock shouldBe f.city.stock
            queued.inventory shouldBe f.city.inventory
            queued.workers shouldBe f.city.workers
            queued.buildings.filter { it.id != preview["building"] } shouldBe f.city.buildings
            queued.building(preview["building"] as UUID)!!.phase shouldBe BuildingPhase.SITE
            h.commit()
            h.await(applied)["state"] shouldBe "queued"

            val runtime = CivsService::class.java.getDeclaredField("construction").also { it.isAccessible = true }
                .get(h.service) as CivsConstructionRuntime
            @Suppress("UNCHECKED_CAST")
            val activate = CivsConstructionRuntime::class.java.getDeclaredField("activateBuilding").also { it.isAccessible = true }
                .get(runtime) as (Settlement, CivBuilding) -> CompletableFuture<Unit>
            val failure = shouldThrow<CompletionException> { h.await(activate(queued, oldSite)) }
            (CivsDiagnostics.rejection(failure) ?: throw failure).reason shouldBe "city-busy"
            maintenance.requirements(queued) shouldBe mapOf(worker.id to work.inputs)
            h.service.settlements().single() shouldBe queued
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("preview and paid SITE admission wait for an unaccepted search without waiting for city commit") {
        serviceScenario { h ->
            val f = installWallInfill(h)
            h.start()
            val placement = CivsService::class.java.getDeclaredField("autonomousPlacement").also { it.isAccessible = true }
                .get(h.service) as CivsAutonomousPlacement
            val backgroundCommits = mutableListOf<CompletableFuture<Unit>>()
            fun searching(): CompletableFuture<CivTerrainSurface> {
                val surface = CompletableFuture<CivTerrainSurface>()
                var requested = false
                every { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) } answers {
                    requested = true; surface
                }
                backgroundCommits += placement.infrastructure(f.city).handle { _, _ -> Unit }.thenCompose {
                    h.service.afterCityAvailable(f.city.id) { CompletableFuture.completedFuture(Unit) }.also {
                        it.isDone shouldBe false
                    }
                }
                h.tickUntil("background search enters native surface capture") { requested }
                every { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(f.surface)
                return surface
            }

            val firstSearch = searching()
            val preview = h.service.infillWall(f.city.id, f.gate.id, f.wall.id, null)
            h.tick()
            preview.isDone shouldBe false
            h.saves shouldBe emptyList()
            firstSearch.isCancelled shouldBe false
            firstSearch.completeExceptionally(CivsRejection("autonomy-no-site"))
            val reviewed = h.await(preview)
            reviewed["state"] shouldBe "preview"
            reviewed["materials"] shouldBe mapOf("DARK_OAK_FENCE" to 2L, "STONE_BRICKS" to 26L)
            h.saves shouldBe emptyList()
            h.await(backgroundCommits.first()) shouldBe Unit

            val secondSearch = searching()
            val apply = h.service.infillWall(f.city.id, f.gate.id, f.wall.id, reviewed["proof"] as String)
            h.tick()
            apply.isDone shouldBe false
            h.saves shouldBe emptyList()
            secondSearch.isCancelled shouldBe false
            secondSearch.completeExceptionally(CivsRejection("autonomy-no-site"))
            h.tickUntil("ordinary wall SITE save") { h.saves.isNotEmpty() || apply.isDone }
            if (apply.isDone) apply.join()
            h.saves.size shouldBe 1
            val queued = h.saves.single().first
            queued.stock shouldBe f.city.stock
            queued.inventory shouldBe f.city.inventory
            queued.construction shouldBe f.city.construction
            queued.buildings.filter { it.id != reviewed["building"] } shouldBe f.city.buildings
            queued.building(reviewed["building"] as UUID)!!.phase shouldBe BuildingPhase.SITE
            apply.isDone shouldBe false
            h.commit()
            h.await(apply)["state"] shouldBe "queued"
            h.await(backgroundCommits.last()) shouldBe Unit
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

private fun installWallInfill(h: ServiceScenario, driftAtCapture: Int = Int.MAX_VALUE,
    cityTransform: (Settlement) -> Settlement = { it }): WallInfillFixture {
    val raw = wallInfillFixture()
    val city = cityTransform(raw.city.copy(owner = h.owner, worldId = h.world.uid))
    val f = raw.copy(city = city, gatePlan = raw.gatePlan.copy(worldId = city.worldId), wallPlan = raw.wallPlan.copy(worldId = city.worldId))
    every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
    every { h.store.loadPlan(f.gatePlan.id) } returns CompletableFuture.completedFuture(f.gatePlan)
    every { h.store.loadPlan(f.wallPlan.id) } returns CompletableFuture.completedFuture(f.wallPlan)
    every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
    every { h.store.deleteSchematic(any()) } returns CompletableFuture.completedFuture(true)
    every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
    every { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(f.surface)
    every { h.worldAdapter.captureTerraform(any(), any(), any(), any()) } answers {
        val draft = arg<CivTerraformDraft>(1)
        CompletableFuture.completedFuture(CivTerraformPlan(draft.building.bounds, emptyList(), draft.route, draft.groundY, 0, 0))
    }
    every { h.worldAdapter.autonomousRoute(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
    every { h.blueprints.prepareInfrastructure(any(), any(), any(), any()) } answers {
        val piece = firstArg<CivSettlementPiece>(); val bytes = arg<ByteArray>(2)
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        every { snapshot.fileSha256 } returns CivsGateRefit.sha256(bytes)
        val cells = piece.blocks.map { ArcSchematicBlock(ArcSchematicPoint(it.position.x, it.position.y, it.position.z), it.after, false, false) }
        CompletableFuture.completedFuture(CivBlueprint(CivsInfrastructure.building(piece), city.worldId, snapshot, cells, cells,
            bytes, CivsMaterials.required(f.plan(piece))))
    }
    var captures = 0
    every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) } answers {
        captures++
        val building = arg<CivBuilding>(3)
        val cells = arg<List<ArcSchematicBlock>>(4)
        CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), city.id, city.worldId, building,
            cells.mapIndexed { index, cell -> CivPlanBlock(CivPosition(cell.position.x, cell.position.y, cell.position.z),
                if (captures >= driftAtCapture && index == 0) "minecraft:stone" else "minecraft:air", cell.blockData) }, terrain = arg(5)))
    }
    return f
}

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
})

private fun installWallInfill(h: ServiceScenario, driftAtCapture: Int = Int.MAX_VALUE): WallInfillFixture {
    val raw = wallInfillFixture()
    val city = raw.city.copy(owner = h.owner, worldId = h.world.uid)
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

package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsGateRefitTest : FunSpec({
    test("same-bounds gate preserves every floor and saved edge route while providing a grounded three-wide arch") {
        for (alongX in listOf(true, false)) {
            val f = gateRefitFixture(alongX)
            val target = f.receipt.replacement
            val cells = target.blocks.associate { it.position to it.after }
            val gate = target.building
            gate.id shouldBe f.gate.id
            gate.bounds shouldBe f.gate.bounds
            gate.anchor shouldBe f.gate.anchor
            gate.entrance shouldBe f.gate.entrance
            f.receipt.changes().size shouldBe 48
            f.receipt.changes().none { it.position.y == f.gate.bounds.min.y } shouldBe true
            gate.transit!!.size shouldBe 19
            gate.transit!!.containsAll(f.receipt.witnesses) shouldBe true
            for (feet in gate.transit!!) for (dy in 0..2) cells[feet.copy(y = feet.y + dy)] shouldBe "minecraft:air"
            val posts = f.gate.transit!!.toSet() - gate.transit!!.toSet()
            posts.size shouldBe 6
            posts.forEach { p ->
                cells[p] shouldBe "minecraft:stone_bricks"
                cells[p.copy(y = p.y + 1)] shouldBe "minecraft:dark_oak_log[axis=y]"
                cells[p.copy(y = p.y + 2)] shouldBe "minecraft:dark_oak_log[axis=y]"
            }
            f.after.stock shouldBe f.before.stock
            f.after.workers shouldBe f.before.workers
            f.after.inventory shouldBe f.before.inventory
            f.after.buildings.filter { it.id != gate.id } shouldBe f.before.buildings.filter { it.id != gate.id }
            f.transaction.validated() shouldBe f.transaction
        }
    }

    test("a paid route through a support and an unsupported old gate are refused instead of silently narrowed") {
        val f = gateRefitFixture()
        val post = (f.gate.transit!!.toSet() - f.receipt.replacement.building.transit!!.toSet()).first()
        shouldThrow<CivsRejection> {
            CivsGateRefit.proposal(f.before, f.source, f.receipt.witnesses + post, UUID.randomUUID())
        }.reason shouldBe "gate-refit-route-conflict"
        shouldThrow<CivsRejection> {
            CivsGateRefit.proposal(f.before, f.source.copy(blocks = f.source.blocks.dropLast(1)), f.receipt.witnesses, UUID.randomUUID())
        }.reason shouldBe "gate-refit-unsupported"
        shouldThrow<CivsRejection> {
            CivsGateRefit.proposal(f.before.copy(style = "nordic"), f.source, f.receipt.witnesses, UUID.randomUUID())
        }.reason shouldBe "gate-refit-unsupported"
    }

    test("only versioned gate transit may exclude posts and every route owner sees those occupied columns") {
        val f = gateRefitFixture()
        val target = f.after.building(f.gate.id)!!
        CivsRules.validate(f.before) shouldBe f.before
        CivsRules.validate(f.after) shouldBe f.after
        CivsSnapshotValidation.validBuilding(f.after, target.copy(schematicId = f.gate.schematicId)) shouldBe false
        CivsSnapshotValidation.validBuilding(f.after, target.copy(type = BuildingType.ROAD)) shouldBe false
        CivsSnapshotValidation.validBuilding(f.after, target.copy(transit = target.transit!!.dropLast(1))) shouldBe false
        civsRouteObstacles(f.before, f.gate) shouldBe emptyList()
        val obstacles = civsRouteObstacles(f.after, target)
        obstacles.size shouldBe 6
        target.transit!!.forEach { p ->
            civsPublicTransit(target, p.x, p.z) shouldBe true
            obstacles.none { CivsAutonomousGeometry.horizontalContains(it, p) } shouldBe true
        }
        (f.gate.transit!!.toSet() - target.transit!!.toSet()).forEach { p ->
            civsPublicTransit(target, p.x, p.z) shouldBe false
            obstacles.count { CivsAutonomousGeometry.horizontalContains(it, p) } shouldBe 1
        }
        civsRouteObstacles(f.after, target.copy(phase = BuildingPhase.CONSTRUCTING), allowPlanned = true) shouldBe obstacles
    }

    test("both immutable captures and the typed receipt survive the normal store without overwriting old geometry") {
        val f = gateRefitFixture()
        val root = Files.createTempDirectory("civs-gate-refit-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(f.before).join()
                store.saveSchematic(f.source.id, f.sourceBytes).join()
                store.savePlan(f.source).join()
                store.saveSchematic(f.receipt.replacement.id, f.replacementBytes).join()
                store.savePlan(f.receipt.replacement).join()
                store.saveInventoryTransaction(f.transaction).join()
                shouldThrow<CompletionException> { store.saveSchematic(f.source.id, f.replacementBytes).join() }
            }
            CivsStore(root, Executor { it.run() }).use { reopened ->
                reopened.load().join() shouldBe listOf(f.before)
                reopened.loadPlan(f.source.id).join() shouldBe f.source
                reopened.loadPlan(f.receipt.replacement.id).join() shouldBe f.receipt.replacement
                reopened.loadSchematic(f.source.id).join()!!.contentEquals(f.sourceBytes) shouldBe true
                reopened.loadInventoryTransactions().join() shouldBe listOf(f.transaction)
                reopened.save(f.after).join()
                CivsGateRefit.retainedPlanId(reopened.load().join().single().building(f.gate.id)!!) shouldBe f.receipt.replacement.id
            }
            val decoded = Gson().fromJson(Gson().toJson(f.transaction), CivInventoryTransaction::class.java).validated()
            decoded shouldBe f.transaction
            CivsSettlementLayout.accepted(f.after, listOf(f.source)) shouldBe emptyList()
            CivsSettlementLayout.accepted(f.after, listOf(f.receipt.replacement)).single().blocks.map { it.after } shouldBe
                f.receipt.replacement.blocks.map { it.after }
            CivsLayoutDebug.planRow(f.after, f.receipt.replacement.id, "retained-building", f.receipt.replacement)["status"] shouldBe "validated"
            CivsLayoutDebug.planRow(f.after, f.source.id, "retained-building", f.source)["status"] shouldBe "unvalidated"
        } finally { root.toFile().deleteRecursively() }
    }

    test("future gate profile charges its real blocks while unchanged floor cells remain unpaid") {
        MockBukkitTestRuntime.open().use {
            val f = gateRefitFixture()
            val open = "minecraft:dark_oak_fence_gate[facing=north,in_wall=false,open=true,powered=false]"
            val oldCells = f.source.blocks.map { cell -> CivSettlementBlock(cell.position,
                if (cell.position.y == 65 && cell.position.z == 22) open else cell.after) }
            val old = CivSettlementPiece(f.gate.id, f.gate.type, f.gate.bounds, f.gate.entrance, oldCells, f.gate.transit!!)
            val next = CivsGateRefit.shape(old, true, f.receipt.witnesses)
            fun price(piece: CivSettlementPiece) = CivsMaterials.required(f.source.copy(blocks = piece.blocks.map {
                CivPlanBlock(it.position, "minecraft:air", it.after)
            }))
            price(old) shouldBe mapOf("COBBLESTONE" to 25L, "STONE_BRICKS" to 5L, "DARK_OAK_FENCE_GATE" to 5L)
            price(next) shouldBe mapOf("COBBLESTONE" to 25L, "STONE_BRICKS" to 6L, "DARK_OAK_LOG" to 27L,
                "DARK_OAK_STAIRS" to 10L, "DARK_OAK_SLAB" to 5L)
            CivsMaterials.required(f.receipt.replacement)["COBBLESTONE"] shouldBe null
            CivsInfrastructure.building(next).schematicId shouldBe CivsGateRefit.PREFIX + f.gate.id
        }
    }
})

internal data class GateRefitFixture(val before: Settlement, val gate: CivBuilding, val source: CivPlacementPlan,
    val receipt: CivGateRefitReceipt, val after: Settlement, val transaction: CivInventoryTransaction,
    val sourceBytes: ByteArray, val replacementBytes: ByteArray)

internal fun gateRefitFixture(alongX: Boolean = true, worldId: UUID = UUID(181, 4), profileVersion: Int = 3): GateRefitFixture {
    val box = CivBox(CivPosition(20, 64, 20), CivPosition(24, 69, 24))
    val id = UUID(181, 1)
    val gate = CivBuilding(id, BuildingType.GATE, 1, box.min, 0, "civs/infrastructure/v1/gate/$id", box,
        CivPosition(22, 65, 19), BuildingPhase.ACTIVE, transit = CivsGateRefit.fullTransit(box))
    val base = settlementPlanCity().copy(style = "caledonia", worldId = worldId, stock = Stock(wood = 20, stone = 20, food = 20))
    val before = CivsRules.validate(base.copy(buildings = base.buildings + gate,
        workers = listOf(CivWorker(UUID(181, 9), "Builder", null, completedCycles = 2))))
    val blocks = buildList {
        for (x in 20..24) for (z in 20..24) {
            add(CivPlanBlock(CivPosition(x, 64, z), "minecraft:air", "minecraft:cobblestone"))
            for (y in 65..68) add(CivPlanBlock(CivPosition(x, y, z), "minecraft:air", "minecraft:air"))
        }
        for (i in 20..24) add(CivPlanBlock(if (alongX) CivPosition(i, 69, 22) else CivPosition(22, 69, i),
            "minecraft:air", "minecraft:stone_bricks"))
    }
    fun bytes(cells: List<CivPlanBlock>, transit: List<CivPosition>) = CivsInfrastructure.schematic(
        CivSettlementPiece(id, BuildingType.GATE, box, gate.entrance, cells.map { CivSettlementBlock(it.position, it.after) }, transit), 1)
    val sourceBytes = bytes(blocks, gate.transit!!)
    val source = CivPlacementPlan(id, before.id, worldId, gate.copy(phase = BuildingPhase.CONSTRUCTING), blocks,
        capturedSha256 = CivsGateRefit.sha256(sourceBytes))
    val witnesses = ((20..24).map { if (alongX) CivPosition(22, 65, it) else CivPosition(it, 65, 22) } +
        (20..24).map { if (alongX) CivPosition(it, 65, 20) else CivPosition(20, 65, it) }).distinct()
    val draft = CivsGateRefit.proposal(before, source, witnesses, UUID(181, 2), profileVersion)
    val replacementBytes = bytes(draft.blocks, draft.building.transit!!)
    val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(replacementBytes))
    val receipt = CivGateRefitReceipt(gate, source, replacement, witnesses)
    val after = CivsRules.validate(before.copy(buildings = before.buildings.map {
        if (it.id == gate.id) replacement.building.copy(phase = BuildingPhase.ACTIVE) else it
    }, revision = before.revision + 1))
    val tx = CivInventoryTransaction(UUID(181, 3), before.id, before.revision, after, emptyList(),
        worldChanges = receipt.changes(), gateRefit = receipt).validated()
    return GateRefitFixture(before, gate, source, receipt, after, tx, sourceBytes, replacementBytes)
}

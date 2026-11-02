package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor

class CivsGateCleanProfileTest : FunSpec({
    test("compact generated gates have grounded posts and survive reload without widening native refit admission") {
        for (alongX in listOf(true, false)) for (stepped in listOf(false, true)) {
            val box = CivBox(CivPosition(20, 64, 20), CivPosition(22, if (stepped) 70 else 69, 22))
            fun point(across: Int, y: Int, depth: Int) = if (alongX) CivPosition(20 + across, y, 20 + depth)
                else CivPosition(20 + depth, y, 20 + across)
            val transit = (0..2).flatMap { across -> (0..2).map { depth ->
                point(across, if (stepped && depth == 2) 66 else 65, depth)
            } }
            val cells = transit.flatMap { feet -> listOf(CivSettlementBlock(feet.copy(y = feet.y - 1), "minecraft:barrier")) +
                (0..3).map { dy -> CivSettlementBlock(feet.copy(y = feet.y + dy), "minecraft:air") } } +
                (0..2).map { CivSettlementBlock(point(it, box.max.y, 1), "minecraft:stone_bricks") }
            val source = CivSettlementPiece(UUID(183, 301), BuildingType.GATE, box, point(1, 65, -1), cells, transit)
            val route = transit.filter { if (alongX) it.x == 21 else it.z == 21 }
            val piece = CivsGateRefit.generatedShape(source, alongX, route)
            piece.transit.size shouldBe 7
            route.all { it in piece.transit } shouldBe true
            val states = piece.blocks.associate { it.position to it.after }
            transit.forEach { feet -> states[feet.copy(y = feet.y - 1)] shouldBe "minecraft:barrier" }
            piece.transit.forEach { feet ->
                states[feet] shouldBe "minecraft:air"
                states[feet.copy(y = feet.y + 1)] shouldBe "minecraft:air"
            }
            for (across in listOf(0, 2)) {
                states[point(across, 65, 1)] shouldBe "minecraft:stone_bricks"
                states[point(across, 66, 1)] shouldBe "minecraft:dark_oak_log[axis=y]"
            }
            piece.blocks.none { it.after.contains("fence_gate") } shouldBe true
            val building = CivsInfrastructure.building(piece).copy(phase = BuildingPhase.ACTIVE)
            CivsGateRefit.validTransit(building) shouldBe true
            CivsGateRefit.validTransit(building.copy(schematicId = "civs/infrastructure/v2/gate/${building.id}")) shouldBe false
            CivsGateRefit.validTransit(building.copy(transit = building.transit!!.drop(1))) shouldBe false
            val city = settlementPlanCity().let { it.copy(style = "caledonia", buildings = it.buildings + building) }
            CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java)) shouldBe city
            val plan = CivPlacementPlan(building.id, city.id, city.worldId, building.copy(phase = BuildingPhase.CONSTRUCTING),
                piece.blocks.map { CivPlanBlock(it.position, "minecraft:air", it.after) })
            shouldThrow<CivsRejection> { CivsGateRefit.proposal(city, plan, route, UUID(183, 302)) }.reason shouldBe "gate-refit-unsupported"
            shouldThrow<CivsRejection> { CivsGateRefit.generatedShape(source, alongX, listOf(point(0, 65, 1))) }
                .reason shouldBe "gate-refit-route-conflict"
            if (!stepped && alongX) System.getenv("CIVS_GATE_COMPACT_OUT")?.let {
                Files.writeString(Path.of(it), Gson().toJson(piece))
            }
        }
    }

    test("new gates have a clear opening while frozen V2 receipts still retain their exact leaves") {
        for (width in 5..6) for (axis in listOf(true, false)) {
            val old = gateVariantFixture(width, axis, profileVersion = 2)
            val fresh = gateVariantFixture(width, axis)
            old.receipt.replacement.blocks.count { it.after.startsWith("minecraft:dark_oak_fence_gate[") } shouldBe width - 2
            fresh.receipt.replacement.blocks.none { it.after.startsWith("minecraft:dark_oak_fence_gate[") } shouldBe true
            fresh.after.building(old.gate.id)!!.transit shouldBe old.after.building(old.gate.id)!!.transit
            Gson().fromJson(Gson().toJson(old.transaction), CivInventoryTransaction::class.java).validated() shouldBe old.transaction
            val clean = cleanGateFixture(old)
            clean.receipt.changes().size shouldBe width - 2
            clean.receipt.changes().all { it.before.startsWith("minecraft:dark_oak_fence_gate[") && it.after == "minecraft:air" } shouldBe true
            clean.receipt.replacement.blocks.map { it.position } shouldBe old.receipt.replacement.blocks.map { it.position }
            clean.after.stock shouldBe old.after.stock
            clean.after.workers shouldBe old.after.workers
            clean.after.inventory shouldBe old.after.inventory
        }
    }

    test("a clean upgrade retargets only the durable gate capture and reloads the identical paid route") {
        val old = gateVariantFixture(reroute = true, profileVersion = 2)
        val clean = cleanGateFixture(old)
        val prior = old.after.roadRouteOverrides!!.getValue(old.gate.id.toString())
        val next = clean.after.roadRouteOverrides!!.getValue(old.gate.id.toString())
        next.route shouldBe prior.route
        next.originalRoute shouldBe prior.originalRoute
        next.gateDetours!!.single() shouldBe prior.gateDetours!!.single().copy(captureId = clean.receipt.replacement.id)
        clean.receipt.before(clean.after) shouldBe old.after
        shouldThrow<CivsRejection> { CivsRules.validate(clean.after.copy(roadRouteOverrides = old.after.roadRouteOverrides)) }
        val directory = Files.createTempDirectory("gate-clean-capture-")
        try {
            CivsStore(directory, Executor { it.run() }).use { store ->
                store.save(old.before).join(); store.save(old.after).join()
                store.saveSchematic(old.receipt.replacement.id, old.replacementBytes).join()
                store.savePlan(old.receipt.replacement).join()
                store.saveSchematic(clean.receipt.replacement.id, clean.replacementBytes).join()
                store.savePlan(clean.receipt.replacement).join()
                store.saveInventoryTransaction(clean.transaction).join(); store.save(clean.after).join()
            }
            CivsStore(directory, Executor { it.run() }).use { store ->
                store.load().join().single() shouldBe clean.after
                store.loadPlan(old.receipt.replacement.id).join() shouldBe old.receipt.replacement
                store.loadInventoryTransactions().join().single() shouldBe clean.transaction
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    test("unknown V2 geometry is rejected and an already clear arch has no native delta") {
        val old = gateVariantFixture(profileVersion = 2)
        val source = old.receipt.replacement
        val altered = source.blocks.toMutableList()
        val index = altered.indexOfFirst { it.after == "minecraft:dark_oak_log[axis=y]" }
        altered[index] = altered[index].copy(after = "minecraft:gold_block")
        shouldThrow<CivsRejection> { CivsGateRefit.proposal(old.after, source.copy(blocks = altered), emptyList(), UUID(183, 8)) }
        val flat = gateRefitFixture(profileVersion = 2)
        val noChange = CivsGateRefit.proposal(flat.after, flat.receipt.replacement, flat.receipt.witnesses, UUID(183, 9))
        noChange.blocks.none { it.before != it.after } shouldBe true
        val clean = cleanGateFixture(old)
        val again = CivsGateRefit.proposal(clean.after, clean.receipt.replacement, clean.receipt.witnesses, UUID(183, 10))
        again.blocks.none { it.before != it.after } shouldBe true
    }

    test("the four actual committed V2 captures need only seven panel removals and no route movement")
        .config(enabled = System.getenv("CIVS_GATE_CLEAN_INPUT") != null) {
            MockBukkitTestRuntime.open().use {
                val gson = Gson()
                fun document(name: String) = JsonParser.parseString(Files.readString(Path.of(requireNotNull(System.getenv(name))))).asJsonObject
                CivsWorksiteLayouts.installCatalog(CivBlueprintCatalog.parse(document("CIVS_GATE_VARIANTS_CATALOG")).worksiteLayouts)
                try {
                    val city = gson.fromJson(document("CIVS_GATE_CLEAN_CITY"), Settlement::class.java).let(CivsRules::validate)
                    val output = document("CIVS_GATE_CLEAN_INPUT").getAsJsonArray("plans").map { row ->
                        val source = gson.fromJson(row.asJsonObject.getAsJsonObject("stored")["plan"], CivPlacementPlan::class.java)
                        val gate = requireNotNull(city.building(source.building.id))
                        fun bytes(plan: CivPlacementPlan) = CivsInfrastructure.schematic(CivSettlementPiece(gate.id, gate.type,
                            gate.bounds, gate.entrance, plan.blocks.map { CivSettlementBlock(it.position, it.after) }, plan.building.transit!!), 4671)
                        CivsGateRefit.sha256(bytes(source)) shouldBe source.capturedSha256
                        val draft = CivsGateRefit.proposal(city, source, gate.transit!!,
                            UUID.nameUUIDFromBytes((gate.id.toString() + "/clean-arch").toByteArray()))
                        val target = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes(draft)))
                        val route = CivsGateRoutes.refit(city, gate, target.building, source.terrain?.route.orEmpty())
                        val receipt = CivGateRefitReceipt(gate, source, target, gate.transit!!,
                            route?.let { mapOf(gate.id.toString() to it) }, route?.let {
                                city.roadRouteOverrides?.filterKeys { id -> id == gate.id.toString() } })
                        if (receipt.changes().isNotEmpty()) receipt.validated(CivsRules.validate(receipt.after(city)))
                        receipt.changes().all { it.before.startsWith("minecraft:dark_oak_fence_gate[") && it.after == "minecraft:air" } shouldBe true
                        target.building.transit shouldBe gate.transit
                        route?.route?.let { it shouldBe city.roadRouteOverrides!!.getValue(gate.id.toString()).route }
                        mapOf("gate" to gate.id, "before" to source, "after" to target,
                            "changedCells" to receipt.changes().size, "routePointsChanged" to false,
                            "routeCaptureRetargeted" to (route != null), "current" to receipt.changes().isEmpty())
                    }
                    output.size shouldBe 4
                    output.map { it.getValue("changedCells") as Int }.sorted() shouldBe listOf(0, 0, 3, 4)
                    System.getenv("CIVS_GATE_CLEAN_OUT")?.let { Files.writeString(Path.of(it), gson.toJson(output)) }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
})

internal fun cleanGateFixture(old: GateRefitFixture): GateRefitFixture {
    val before = old.after
    val gate = requireNotNull(before.building(old.gate.id))
    val source = old.receipt.replacement
    val draft = CivsGateRefit.proposal(before, source, old.receipt.witnesses, UUID(183, 2))
    val bytes = CivsInfrastructure.schematic(CivSettlementPiece(gate.id, gate.type, gate.bounds, gate.entrance,
        draft.blocks.map { CivSettlementBlock(it.position, it.after) }, draft.building.transit!!), 1)
    val target = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes))
    val route = CivsGateRoutes.refit(before, gate, target.building, source.terrain?.route.orEmpty())
    val receipt = CivGateRefitReceipt(gate, source, target, old.receipt.witnesses,
        route?.let { mapOf(gate.id.toString() to it) }, route?.let { before.roadRouteOverrides?.filterKeys { id -> id == gate.id.toString() } })
    val after = CivsRules.validate(receipt.after(before))
    val tx = CivInventoryTransaction(UUID(183, 3), before.id, before.revision, after, emptyList(),
        worldChanges = receipt.changes(), gateRefit = receipt).validated()
    return GateRefitFixture(before, gate, source, receipt, after, tx, old.replacementBytes, bytes)
}

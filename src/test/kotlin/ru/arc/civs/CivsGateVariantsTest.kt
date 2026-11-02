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

class CivsGateVariantsTest : FunSpec({
    test("stepped five and six wide gates retain exact floors and a grounded three or four wide passage") {
        for (width in 5..6) for (alongX in listOf(true, false)) {
            val f = gateVariantFixture(width = width, alongX = alongX)
            val target = f.receipt.replacement
            target.building.bounds shouldBe f.gate.bounds
            target.building.transit!!.size shouldBe width * 5 - 6
            CivsGateRefit.validTransit(target.building) shouldBe true
            val floors = f.gate.transit!!.map { it.copy(y = it.y - 1) }.toSet()
            f.receipt.changes().none { it.position in floors } shouldBe true
            val oldCells = f.source.blocks.associate { it.position to it.after }
            val newCells = target.blocks.associate { it.position to it.after }
            floors.forEach { newCells[it] shouldBe oldCells[it] }
            (f.gate.transit!!.toSet() - target.building.transit!!.toSet()).forEach { feet ->
                newCells[feet] shouldBe "minecraft:stone_bricks"
                for (y in feet.y + 1 until f.gate.bounds.max.y - 1)
                    newCells[feet.copy(y = y)] shouldBe "minecraft:dark_oak_log[axis=y]"
            }
            target.building.transit!!.forEach { feet ->
                (newCells[feet] == "minecraft:air" || newCells[feet]!!.contains("open=true")) shouldBe true
                newCells[feet.copy(y = feet.y + 1)] shouldBe "minecraft:air"
                newCells[feet.copy(y = feet.y + 2)] shouldBe "minecraft:air"
            }
            f.receipt.before(f.after) shouldBe f.before
            f.transaction.validated() shouldBe f.transaction
        }
    }

    test("a gate-owned edge route detours inside its paid floor and keeps the prior external grade repair") {
        val f = gateVariantFixture(reroute = true)
        val previous = f.before.roadRouteOverrides!!.getValue(f.gate.id.toString())
        val moved = f.after.roadRouteOverrides!!.getValue(f.gate.id.toString())
        moved.route.size shouldBe previous.route.size + 2
        moved.originalRoute shouldBe previous.originalRoute
        moved.route.first() shouldBe previous.route.first()
        moved.route.last() shouldBe previous.route.last()
        fun outside(route: List<CivPosition>) = route.filterNot { CivsAutonomousGeometry.horizontalContains(f.gate.bounds, it) }
        outside(moved.route) shouldBe outside(previous.route)
        moved.runs() shouldBe previous.runs()
        moved.runs().all(moved::hump) shouldBe true
        val detour = moved.gateDetours!!.single()
        detour.after.all { it in f.after.building(f.gate.id)!!.transit!! } shouldBe true
        CivAutonomousAccess.from(f.after, listOf(f.receipt.replacement)).routes[f.gate.id] shouldBe moved.route
        f.after.stock shouldBe f.before.stock
        f.after.workers shouldBe f.before.workers
        f.after.inventory shouldBe f.before.inventory
        f.receipt.before(f.after) shouldBe f.before
        val copied = Gson().fromJson(Gson().toJson(f.transaction), CivInventoryTransaction::class.java).validated()
        copied shouldBe f.transaction
        val root = Files.createTempDirectory("civs-gate-detour-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(f.before).join()
                store.saveSchematic(f.source.id, f.sourceBytes).join(); store.savePlan(f.source).join()
                store.saveSchematic(f.receipt.replacement.id, f.replacementBytes).join(); store.savePlan(f.receipt.replacement).join()
                store.saveInventoryTransaction(f.transaction).join(); store.save(f.after).join()
            }
            CivsStore(root, Executor { it.run() }).use { store ->
                store.load().join().single() shouldBe f.after
                store.loadInventoryTransactions().join().single() shouldBe f.transaction
                store.loadPlan(f.source.id).join() shouldBe f.source
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("detour metadata cannot authorize a foreign gate an external edit a new height or a missing capture") {
        val f = gateVariantFixture(reroute = true)
        val value = f.after.roadRouteOverrides!!.getValue(f.gate.id.toString())
        val detour = value.gateDetours!!.single()
        fun reject(value: CivRoadRouteOverride) {
            shouldThrow<CivsRejection> { CivsRules.validate(f.after.copy(roadRouteOverrides =
                f.after.roadRouteOverrides!! + (f.gate.id.toString() to value))) }.reason shouldBe "invalid-state"
        }
        reject(value.copy(gateDetours = null))
        reject(value.copy(gateDetours = listOf(detour.copy(gateId = UUID(182, 999)))))
        reject(value.copy(gateDetours = listOf(detour.copy(captureId = UUID(182, 999)))))
        reject(value.copy(route = value.route.mapIndexed { i, p -> if (i == 1) p.copy(x = p.x + 1) else p }))
        reject(value.copy(route = value.route.map { if (it == detour.after[1]) it.copy(y = it.y + 1) else it }))
        shouldThrow<IllegalArgumentException> { f.receipt.copy(previousRouteOverrides = null).validated(f.after) }
        val legacy = Gson().fromJson("""{"originalRoute":[],"route":[]}""", CivRoadRouteOverride::class.java)
        legacy.gateDetours shouldBe null
    }

    test("preview proof is stable across fresh capture IDs and includes the ordered route") {
        val f = gateVariantFixture(reroute = true)
        val first = CivsGateRefit.proof(f.source, f.receipt.replacement, f.receipt.witnesses, f.receipt.routeOverrides)
        val changedId = UUID(182, 300)
        val value = f.receipt.routeOverrides!!.getValue(f.gate.id.toString())
        val same = value.copy(gateDetours = value.gateDetours!!.map { it.copy(captureId = changedId) })
        CivsGateRefit.proof(f.source, f.receipt.replacement.copy(id = changedId), f.receipt.witnesses,
            mapOf(f.gate.id.toString() to same)) shouldBe first
        (CivsGateRefit.proof(f.source, f.receipt.replacement, f.receipt.witnesses,
            mapOf(f.gate.id.toString() to same.copy(route = same.route.asReversed()))) == first) shouldBe false
    }

    test("typed gate metadata is detached from caller owned lists and ordinary road repairs keep their old contract") {
        val f = gateVariantFixture(reroute = true)
        val value = f.after.roadRouteOverrides!!.getValue(f.gate.id.toString())
        val mutable = value.gateDetours!!.single().after.toMutableList()
        val input = value.copy(gateDetours = listOf(value.gateDetours!!.single().copy(after = mutable)))
        val saved = input.detached()
        val board = civsPlanBoardCapture(f.after.copy(roadRouteOverrides = mapOf(f.gate.id.toString() to input)))
        mutable.removeLast()
        saved shouldBe value
        board.roadRouteOverrides!![f.gate.id.toString()] shouldBe value
        val road = f.gate.copy(type = BuildingType.ROAD)
        shouldThrow<IllegalArgumentException> { value.validated(f.after, road) }
    }

    test("actual native gate variants produce reviewable geometry and route deltas")
        .config(enabled = System.getenv("CIVS_GATE_VARIANTS_INPUT") != null) {
            MockBukkitTestRuntime.open().use {
                val gson = Gson()
                val input = JsonParser.parseString(Files.readString(Path.of(System.getenv("CIVS_GATE_VARIANTS_INPUT")))).asJsonObject
                val catalog = JsonParser.parseString(Files.readString(Path.of(requireNotNull(
                    System.getenv("CIVS_GATE_VARIANTS_CATALOG"))))).asJsonObject
                CivsWorksiteLayouts.installCatalog(CivBlueprintCatalog.parse(catalog).worksiteLayouts)
                try {
                    val city = gson.fromJson(input["city"], Settlement::class.java).let(CivsRules::validate)
                    val plans = input.getAsJsonArray("plans").map { gson.fromJson(it, CivPlacementPlan::class.java) }
                    val routes = input.getAsJsonArray("routes").associate { row ->
                        val obj = row.asJsonObject
                        UUID.fromString(obj["building"].asString) to obj.getAsJsonArray("positions").map { gson.fromJson(it, CivPosition::class.java) }
                    }.mapValues { (id, route) -> city.roadRouteOverrides?.get(id.toString())?.route ?: route }
                    val output = plans.filter { it.building.type == BuildingType.GATE }.map { source ->
                        val gate = requireNotNull(city.building(source.building.id))
                        val id = UUID.nameUUIDFromBytes((gate.id.toString() + "/variants-audit").toByteArray())
                        val shape = CivsGateRefit.proposal(city, source, emptyList(), id)
                        val override = CivsGateRoutes.refit(city, gate, shape.building, source.terrain?.route.orEmpty())
                        val effective = if (override == null) routes else routes + (gate.id to override.route)
                        val witnesses = (effective.values.flatten() + city.buildings.flatMap { CivsAutonomousGeometry.approach(it) } +
                            city.buildings.filter { it.type == BuildingType.ROAD }.flatMap { it.transit.orEmpty() })
                            .filter { CivsAutonomousGeometry.horizontalContains(gate.bounds, it) }.distinct()
                        val draft = CivsGateRefit.proposal(city, source, witnesses, id)
                        val bytes = CivsInfrastructure.schematic(CivSettlementPiece(gate.id, gate.type, gate.bounds, gate.entrance,
                            draft.blocks.map { CivSettlementBlock(it.position, it.after) }, draft.building.transit!!), 1)
                        val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes))
                        val receipt = CivGateRefitReceipt(gate, source, replacement, witnesses,
                            override?.let { mapOf(gate.id.toString() to it) }, override?.let {
                                city.roadRouteOverrides?.filterKeys { key -> key == gate.id.toString() }?.takeIf { it.isNotEmpty() } })
                        receipt.validated(CivsRules.validate(receipt.after(city)))
                        fun price(plan: CivPlacementPlan) = CivsMaterials.required(plan.copy(blocks = plan.blocks.map { it.copy(before = "minecraft:air") }))
                        mapOf("buildingId" to gate.id, "sourceSha256" to source.capturedSha256,
                            "before" to source, "after" to replacement, "changedCells" to receipt.changes().size,
                            "routeBefore" to (city.roadRouteOverrides?.get(gate.id.toString())?.route ?: source.terrain?.route.orEmpty()),
                            "routeAfter" to (override?.route ?: city.roadRouteOverrides?.get(gate.id.toString())?.route ?: source.terrain?.route.orEmpty()),
                            "overrides" to receipt.routeOverrides, "floorChanges" to receipt.changes().count { c ->
                                gate.transit.orEmpty().any { p -> c.position == p.copy(y = p.y - 1) } },
                            "materialsBefore" to price(source), "materialsAfter" to price(replacement))
                    }
                    output.size shouldBe 4
                    System.getenv("CIVS_GATE_VARIANTS_OUT")?.let { Files.writeString(Path.of(it), gson.toJson(output)) }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
})

internal fun gateVariantFixture(width: Int = 5, alongX: Boolean = true, reroute: Boolean = false,
    worldId: UUID = UUID(182, 4), profileVersion: Int = 3): GateRefitFixture {
    val box = CivBox(CivPosition(20, 64, 20), CivPosition(if (alongX) 19 + width else 24, 70, if (alongX) 24 else 19 + width))
    fun p(across: Int, y: Int, depth: Int) = if (alongX) CivPosition(20 + across, y, 20 + depth) else CivPosition(20 + depth, y, 20 + across)
    val id = UUID(182, 1)
    val transit = buildList { for (a in 0 until width) for (d in 0..4) add(p(a, if (a == width - 1 && d <= 1) 65 else 66, d)) }
    val currentRoute = if (reroute) buildList {
        for (d in -20..7) add(p(2, 66, d))
        add(p(1, 66, 7)); add(p(0, 66, 7))
        for (d in 6 downTo 0) add(p(0, 66, d))
        add(p(-1, 66, 0))
    } else emptyList()
    val originalRoute = currentRoute.mapIndexed { i, point -> if (i in 4..7) point.copy(y = point.y + 1) else point }
    val gate = CivBuilding(id, BuildingType.GATE, 1, box.min, 0, "civs/infrastructure/v1/gate/$id", box,
        if (reroute) currentRoute.last() else p(2, 66, -1), BuildingPhase.ACTIVE, transit = transit)
    val base = settlementPlanCity().copy(style = "caledonia", worldId = worldId, stock = Stock(wood = 20, stone = 20, food = 20))
    val previous = if (reroute) mapOf(id.toString() to CivRoadRouteOverride(originalRoute, currentRoute)) else null
    val before = CivsRules.validate(base.copy(buildings = base.buildings + gate, roadRouteOverrides = previous))
    val blocks = buildList {
        for (feet in transit) {
            if ((feet.x + feet.z) % 2 == 0) add(CivPlanBlock(feet.copy(y = feet.y - 1), "minecraft:air", "minecraft:cobblestone"))
            for (dy in 0..3) add(CivPlanBlock(feet.copy(y = feet.y + dy), "minecraft:air",
                if (dy == 0 && (if (alongX) feet.z == 22 else feet.x == 22))
                    "minecraft:dark_oak_fence_gate[facing=${if (alongX) "north" else "east"},in_wall=false,open=true,powered=false]"
                else "minecraft:air"))
        }
        for (a in 0 until width) add(CivPlanBlock(p(a, 70, 2), "minecraft:air", "minecraft:stone_bricks"))
    }
    fun bytes(plan: CivPlacementPlan) = CivsInfrastructure.schematic(CivSettlementPiece(id, gate.type, box, gate.entrance,
        plan.blocks.map { CivSettlementBlock(it.position, it.after) }, plan.building.transit!!), 1)
    val terrain = if (!reroute) null else CivTerraformPlan(
        CivBox(CivPosition(originalRoute.minOf { it.x }, originalRoute.minOf { it.y }, originalRoute.minOf { it.z }),
            CivPosition(originalRoute.maxOf { it.x }, originalRoute.maxOf { it.y }, originalRoute.maxOf { it.z })),
        emptyList(), originalRoute, box.min.y, 0, 0)
    val raw = CivPlacementPlan(id, before.id, worldId, gate.copy(phase = BuildingPhase.CONSTRUCTING), blocks, terrain = terrain)
    val sourceBytes = bytes(raw)
    val source = raw.copy(capturedSha256 = CivsGateRefit.sha256(sourceBytes))
    val captureId = UUID(182, 2)
    val shape = CivsGateRefit.proposal(before, source, emptyList(), captureId, profileVersion)
    val override = CivsGateRoutes.refit(before, gate, shape.building, originalRoute)
    val witnesses = (override?.route ?: currentRoute).filter { CivsAutonomousGeometry.horizontalContains(box, it) }
    val draft = CivsGateRefit.proposal(before, source, witnesses, captureId, profileVersion)
    val replacementBytes = bytes(draft)
    val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(replacementBytes))
    val receipt = CivGateRefitReceipt(gate, source, replacement, witnesses, override?.let { mapOf(id.toString() to it) },
        if (override == null) null else previous)
    val after = CivsRules.validate(receipt.after(before))
    val tx = CivInventoryTransaction(UUID(182, 3), before.id, before.revision, after, emptyList(),
        worldChanges = receipt.changes(), gateRefit = receipt).validated()
    return GateRefitFixture(before, gate, source, receipt, after, tx, sourceBytes, replacementBytes)
}

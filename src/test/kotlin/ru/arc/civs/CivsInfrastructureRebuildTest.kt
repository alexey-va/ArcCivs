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

class CivsInfrastructureRebuildTest : FunSpec({
    test("medievaloak five and seven wide sloped gates preserve paid floors and every proven route") {
        MockBukkitTestRuntime.open().use {
            val exports = mutableListOf<Any>()
            for (width in listOf(5, 7)) for (alongX in listOf(true, false)) {
                val (city, source) = legacyInfrastructureGate(width, alongX)
                val witnesses = source.building.transit!!.filter { if (alongX) it.x == 22 else it.z == 22 }
                val target = CivsInfrastructureRebuild.proposal(city, source, witnesses)
                val after = target.blocks.associate { it.position to it.after }
                target.building.bounds shouldBe source.building.bounds
                target.building.entrance shouldBe source.building.entrance
                target.building.transit!!.containsAll(witnesses) shouldBe true
                CivsGateRefit.validTransit(target.building) shouldBe true
                target.blocks.none { it.after.contains("_fence_gate[") } shouldBe true
                target.building.transit!!.forEach { p ->
                    for (dy in 0..2) after[p.copy(y = p.y + dy)] shouldBe "minecraft:air"
                }
                source.building.transit!!.map { it.copy(y = it.y - 1) }.forEach { p ->
                    after[p] shouldBe source.blocks.single { it.position == p }.after
                }
                CivsMaterials.required(target)["COBBLESTONE"] shouldBe null
                CivsInfrastructureRebuild.matchesSource(source, target) shouldBe true
                val edited = target.blocks.toMutableList()
                edited[0] = edited[0].copy(before = "minecraft:diamond_block")
                CivsInfrastructureRebuild.matchesSource(source, target.copy(blocks = edited)) shouldBe false
                exports += mapOf("name" to "gate-$width-${if (alongX) "x" else "z"}", "bounds" to source.building.bounds,
                    "before" to source.blocks, "after" to target.blocks, "transit" to target.building.transit,
                    "materialsBefore" to CivsMaterials.required(source), "materialsRebuild" to CivsMaterials.required(target))
            }
            System.getenv("CIVS_INFRASTRUCTURE_PREVIEW_OUT")?.let { destination ->
                val path = Path.of(destination)
                Files.createDirectories(path.parent)
                Files.writeString(path, Gson().toJson(exports))
            }
        }
    }

    test("piers use an unused side column and a completely occupied edge is refused") {
        val (city, source) = legacyInfrastructureGate(7, true)
        val edge = source.building.transit!!.filter { it.x == source.building.bounds.min.x && it.z == 22 }
        val target = CivsInfrastructureRebuild.proposal(city, source, edge)
        target.building.transit!!.containsAll(edge) shouldBe true
        shouldThrow<CivsRejection> { CivsInfrastructureRebuild.proposal(city, source,
            source.building.transit!!.filter { it.x == source.building.bounds.min.x })
        }.reason shouldBe "gate-refit-route-conflict"
    }

    test("same level refits use the ordinary durable queue and stop repeating after completion") {
        MockBukkitTestRuntime.open().use {
            val (base, source) = legacyInfrastructureGate(7, false)
            val city = base.copy(stock = Stock(wood = 1000, stone = 1000, food = 1000))
            val proposal = CivsInfrastructureRebuild.proposal(city, source, emptyList())
            val queued = CivsRules.enqueue(city, city.owner, source.building.id)
            val queuedPlan = proposal.copy(id = queuedDesignId(source.building.copy(phase = BuildingPhase.ACTIVE)))
            requireQueuedDesign(queuedPlan, queued, requireNotNull(queued.building(source.building.id)))
            val materials = CivsMaterials.required(proposal)
            val order = CivConstruction(UUID(224, 20), proposal.building.id, 1, 1, 0, ConstructionPhase.CONSTRUCTING,
                CivsAutonomy.cost(BuildingType.GATE, 1, materials), plannedBuilding = proposal.building,
                batches = CivConstructionBatches(1, 16, atomicPairs = true), materials = materials)
            val reserved = CivsRules.reserveBuild(queued, queued.owner, proposal.building, order)
            val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(reserved), Settlement::class.java))
            reloaded.building(source.building.id) shouldBe city.building(source.building.id)
            val completed = CivsRules.completeBatch(reloaded.copy(construction = reloaded.construction!!.copy(
                phase = ConstructionPhase.PLACING)), city.owner, order.id)
            val rebuilt = requireNotNull(completed.building(source.building.id))
            rebuilt.level shouldBe 1
            CivsInfrastructureRebuild.stage(rebuilt) shouldBe 1
            CivsInfrastructureRebuild.needsRefit(completed, rebuilt) shouldBe false
            CivsGateRefit.retainedPlanId(rebuilt) shouldBe proposal.id
            completed.workers shouldBe city.workers
        }
    }

    test("wall stages progress from low timber to masonry using only their reserved columns") {
        val profiles = (1..3).associateWith { stage -> (20..31).flatMap { x -> (20..22).flatMap { z ->
            CivsInfrastructureRebuild.wallColumn("medievaloak", stage, x, 64, z, true, 21, 9)
        } } }
        profiles.getValue(1).any { it.after == "minecraft:oak_planks" } shouldBe true
        profiles.getValue(1).filter { it.position.y > 67 }.all { it.after == "minecraft:air" } shouldBe true
        profiles.getValue(2).count { it.after == "minecraft:stone_bricks" }.let { it > 0 } shouldBe true
        profiles.getValue(3).count { it.after == "minecraft:stone_bricks" }.let {
            it > profiles.getValue(2).count { cell -> cell.after == "minecraft:stone_bricks" }
        } shouldBe true
        profiles.values.map { cells -> cells.map { it.position }.toSet() }.distinct().size shouldBe 1
        for (x in 20..31) for (y in 72..73)
            profiles.getValue(3).single { it.position == CivPosition(x, y, 21) }.after shouldBe "minecraft:air"
        System.getenv("CIVS_INFRASTRUCTURE_PREVIEW_OUT")?.let { destination ->
            val path = Path.of(destination).resolveSibling("walls.json")
            Files.createDirectories(path.parent)
            Files.writeString(path, Gson().toJson(profiles.map { (stage, cells) ->
                mapOf("name" to "wall-stage-$stage", "bounds" to CivBox(CivPosition(20, 64, 20), CivPosition(31, 73, 22)),
                    "after" to cells)
            }))
        }
    }

    test("current lab captures rebuild their exact medievaloak gates without moving roads")
        .config(enabled = System.getenv("CIVS_INFRASTRUCTURE_LIVE_INPUT") != null) {
            MockBukkitTestRuntime.open().use {
                val directory = Path.of(requireNotNull(System.getenv("CIVS_INFRASTRUCTURE_LIVE_INPUT")))
                val gson = Gson()
                val city = gson.fromJson(Files.readString(directory.resolve("latest.json")), Settlement::class.java)
                val sources = Files.list(directory).use { files -> files.filter { it.fileName.toString() != "latest.json" &&
                    it.fileName.toString().endsWith(".json") }.map { file ->
                    gson.fromJson(JsonParser.parseString(Files.readString(file)).asJsonObject["plan"], CivPlacementPlan::class.java)
                }.toList() }
                val access = CivAutonomousAccess.from(city, sources)
                val rows = sources.map { source ->
                    val box = source.building.bounds
                    val witnesses = (access.routes.values.flatten() + city.buildings.flatMap(CivsAutonomousGeometry::approach) +
                        city.buildings.filter { it.type == BuildingType.ROAD }.flatMap { it.transit.orEmpty() })
                        .filter { it.x in box.min.x..box.max.x && it.z in box.min.z..box.max.z }.distinct()
                    val target = CivsInfrastructureRebuild.proposal(city, source, witnesses)
                    CivsGateRefit.validTransit(target.building) shouldBe true
                    target.building.transit!!.containsAll(witnesses) shouldBe true
                    target.blocks.none { cell -> cell.after.contains("_fence_gate[") } shouldBe true
                    source.building.transit!!.map { p -> p.copy(y = p.y - 1) }.toSet().let { floor ->
                        target.blocks.filter { cell -> cell.position in floor }.all { cell -> cell.before == cell.after } shouldBe true
                    }
                    mapOf("name" to "live-${source.building.id}", "bounds" to box, "before" to source.blocks,
                        "after" to target.blocks, "transit" to target.building.transit, "witnesses" to witnesses,
                        "materialsRebuild" to CivsMaterials.required(target), "sourceSha256" to source.capturedSha256)
                }
                rows.size shouldBe 2
                System.getenv("CIVS_INFRASTRUCTURE_PREVIEW_OUT")?.let { destination ->
                    val path = Path.of(destination).resolveSibling("live.json")
                    Files.createDirectories(path.parent)
                    Files.writeString(path, gson.toJson(rows))
                }
            }
        }
})

private fun legacyInfrastructureGate(width: Int, alongX: Boolean): Pair<Settlement, CivPlacementPlan> {
    fun at(a: Int, y: Int, d: Int) = if (alongX) CivPosition(20 + a, y, 20 + d) else CivPosition(20 + d, y, 20 + a)
    val box = CivBox(at(0, 64, 0), at(width - 1, 70, 4))
    val id = UUID(224, width.toLong() * 10 + if (alongX) 1 else 2)
    val transit = (0 until width).flatMap { a -> (0..4).map { d -> at(a, if (a == width - 1) 66 else 65, d) } }
    val gate = CivBuilding(id, BuildingType.GATE, 1, box.min, 0, "civs/infrastructure/v1/gate/$id", box,
        at(width / 2, 65, -1), BuildingPhase.ACTIVE, transit = transit)
    val city = settlementPlanCity().copy(style = "medievaloak", buildings = settlementPlanCity().buildings + gate)
    val blocks = buildList {
        for (feet in transit) {
            add(CivPlanBlock(feet.copy(y = feet.y - 1), "minecraft:air", "minecraft:cobblestone"))
            for (dy in 0..3) add(CivPlanBlock(feet.copy(y = feet.y + dy), "minecraft:air",
                if (dy == 0 && (if (alongX) feet.z == 22 else feet.x == 22))
                    "minecraft:oak_fence_gate[facing=${if (alongX) "north" else "east"},in_wall=false,open=true,powered=false]"
                else "minecraft:air"))
        }
        for (a in 0 until width) add(CivPlanBlock(at(a, 70, 2), "minecraft:air", "minecraft:stone_bricks"))
    }
    val bytes = CivsInfrastructure.schematic(CivSettlementPiece(id, gate.type, box, gate.entrance,
        blocks.map { CivSettlementBlock(it.position, it.after) }, transit), 4671)
    return CivsRules.validate(city) to CivPlacementPlan(id, city.id, city.worldId, gate.copy(phase = BuildingPhase.CONSTRUCTING),
        blocks, capturedSha256 = CivsGateRefit.sha256(bytes))
}

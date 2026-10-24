package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs

class CivsMineV5Test : FunSpec({
    test("all V4 receipt ordinals preserve the exact pre-V5 exported step bytes") {
        val hashes = mapOf(
            "caledonia/1" to "6196ded59727f11f84e8e07c6715361746b98d31343b7129ef40c947d8eb4694",
            "caledonia/2" to "486962948f5c5d6dd0e903dbf6e5ce871b0423019aa527645b64cfca41b998f8",
            "caledonia/3" to "14037c1dbf7afb9c67d4c43dfe8fd980541b4e1c889ec024c6e53f0943aba671",
            "medievaloak/1" to "04e48fda06c35e76d6d915c026344f623203178da7dddcf2df9c313044aa3fbd",
            "medievaloak/2" to "96f636f9dd0c13ed91e413ade80acb3e15ad1ae61bac219ed8f58e56ed4ec515",
            "medievaloak/3" to "1cd2746d59f694289c79a2ab332ba1b3d519b89717c759aab2771fbc7ae28ed7",
            "nordic/1" to "c4b4d8be3cf60fcab3ab8e9ea34b104075fe03c2e6c0c997ec348d422c83d99b",
            "nordic/2" to "47745e0ef09db576961e1fe05a800fe228a6e3390b36197dbc8511447b330768",
            "nordic/3" to "da8bc8a05508c4f925f02cdf37aff4549a44cd3c4cd874594192ce98a6df0fd0",
        )
        for ((key, hash) in hashes) {
            val (style, level) = key.split('/')
            val mine = spatialBuilding(style, "miner", level.toInt(), CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val bytes = Gson().toJson(plan.steps).toByteArray(Charsets.UTF_8)
            withClue(key) { MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() shouldBe hash }
        }
    }

    test("all styles retain the single shaft topology with guarded openings and three-wide framed galleries") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val old = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
            plan.floors shouldBe old.floors
            plan.entranceLanding shouldBe old.entranceLanding
            plan.generator shouldBe old.generator
            plan.steps.filter { it.after?.startsWith("minecraft:ladder[") == true } shouldBe
                old.steps.filter { it.after?.startsWith("minecraft:ladder[") == true }
            val final = requireNotNull(plan.completed(plan.steps.size.toLong()))
            val previous = requireNotNull(old.completed(old.steps.size.toLong()))
            for (y in plan.lowest..plan.bottom.y) {
                final[plan.backing.copy(y = y)] shouldBe previous[plan.backing.copy(y = y)]
                final[plan.bottom.copy(y = y)] shouldBe previous[plan.bottom.copy(y = y)]
            }
            fun passable(point: CivPosition) = final.containsKey(point) && (final[point] == null || final[point] == "minecraft:torch")
            for (y in plan.lowest..plan.bottom.y) for ((x, z) in plan.openingColumns)
                final[CivPosition(x, y, z)] shouldBe null
            for ((x, z) in plan.openingColumns) final[CivPosition(x, plan.lowest - 1, z)] shouldBe "minecraft:cobblestone"
            for (floor in listOf(requireNotNull(plan.entranceLanding)) + plan.floors) {
                val rails = plan.steps.filter { it.floor == floor.index && it.branch == "railing" }
                rails.size shouldBe 16
                rails.forEach { rail ->
                    (rail.point.x to rail.point.z in plan.openingColumns) shouldBe false
                    plan.containsWorkStanding(rail.point) shouldBe false
                    final[rail.point] shouldBe rail.after
                    val neighbours = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).count { (dx, dz) ->
                        rails.any { it.point == rail.point.copy(x = rail.point.x + dx, z = rail.point.z + dz) }
                    }
                    neighbours shouldBe 2
                }
                for (support in floor.cells.drop(floor.sections.first().second)) {
                    passable(support.copy(y = floor.feetY)) shouldBe true
                    passable(support.copy(y = floor.feetY + 1)) shouldBe true
                }
                plan.steps.count { it.floor == floor.index && it.branch == "lining" } shouldBe 26 * floor.height
                // An outer diagonal corner is hidden behind two solid wall faces.
                plan.steps.filter { it.floor == floor.index && it.branch == "lining" }.forEach {
                    (abs(it.point.x - plan.center.x) == 5 && abs(it.point.z - plan.center.z) == 5) shouldBe false
                }
                if (floor.index == 0) plan.steps.none { it.floor == 0 && it.after?.endsWith("_planks") == true } shouldBe true
            }
            for (rotation in listOf(0, 90, 180, 270)) {
                val rotated = mine.copy(rotation = rotation)
                plan.steps.forEach { step -> CivsMinePlan.localPoint(rotated, CivsWorksiteLayouts.point(rotated, step.point)) shouldBe step.point }
            }
        }
    }

    test("every V5 step has a completed predecessor and each upper railing precedes exposure of its drop") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
            val prefix = mutableMapOf<CivPosition, String?>()
            var temporaryStanding = false
            for ((index, step) in plan.steps.withIndex()) {
                val approach = requireNotNull(step.approach)
                withClue("$style L$level step $index $step") {
                    plan.builtStanding(approach, index.toLong()) shouldBe true
                    val ladder = prefix[approach]?.startsWith("minecraft:ladder[") == true
                    (approach.y >= plan.bottom.y || ladder || prefix[approach.copy(y = approach.y - 1)] == "minecraft:cobblestone") shouldBe true
                    (step.point == approach || step.point == approach.copy(y = approach.y + 1)) shouldBe false
                    (abs(step.point.x - approach.x) <= 4 && abs(step.point.z - approach.z) <= 4) shouldBe true
                }
                if (!plan.containsWorkStanding(approach)) temporaryStanding = true
                prefix[step.point] = step.after
            }
            temporaryStanding shouldBe true
            val landings = listOf(requireNotNull(plan.entranceLanding)) + plan.floors
            for ((upper, lower) in landings.zipWithNext()) {
                val lastRail = plan.steps.indexOfLast { it.floor == upper.index && it.branch == "railing" }
                val firstDrop = plan.steps.indexOfFirst { it.floor == lower.index && it.branch == "opening" && it.point.y == upper.feetY - 1 }
                (lastRail in 0 until firstDrop) shouldBe true
            }
            val bottom = plan.steps.indexOfLast { it.branch == "bottom" }
            val finalRail = plan.steps.indexOfFirst { it.floor == plan.floors.last().index && it.branch == "railing" }
            (bottom in 0 until finalRail) shouldBe true
            plan.steps.take(plan.shaftEnd).all { it.stage == "shaft" } shouldBe true
            plan.steps.drop(plan.shaftEnd).all { it.stage == "gallery" } shouldBe true
        }
    }

    test("every framed tier and gallery can be paid from visible completed stances before each new obstruction") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            for (style in listOf("nordic", "medievaloak", "caledonia")) {
                val world = CivsWorksiteWorldFixture("v5-entrance-$style").also(paper.server::addWorld)
                for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
                val mine = spatialBuilding(style, "miner", 3, CivPosition(0, 63, 0))
                val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
                val bottom = CivsWorksiteLayouts.point(mine, plan.bottom)
                for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
                val floors = (listOf(requireNotNull(plan.entranceLanding)) + plan.floors).associateBy { it.index }
                val stances = floors.mapValues { (_, floor) ->
                    floor.cells.map { it.copy(y = floor.feetY) } + plan.bottom.copy(y = floor.feetY)
                }
                for ((index, step) in plan.steps.withIndex()) {
                    val target = CivsWorksiteLayouts.point(mine, step.point)
                    if (step.branch in setOf("railing", "lining", "frame") || step.stage == "gallery") {
                        // The native shaft safety rule admits only its immediate neighbours
                        // for targets within two columns. Retain that rule in this oracle.
                        val gallery = (step.point.x to step.point.z) !in plan.shaftColumns && plan.floors.any { floor ->
                            floor.cells.any { it.x == step.point.x && it.z == step.point.z && step.point.y in it.y..it.y + 4 }
                        }
                        val nearShaft = !gallery && abs(step.point.x - plan.bottom.x) <= 2 && abs(step.point.z - plan.bottom.z) <= 2
                        val candidates = (listOfNotNull(step.approach) + stances.getValue(requireNotNull(step.floor))).distinct()
                            .filter { abs(it.x - step.point.x) <= 4 && abs(it.z - step.point.z) <= 4 }
                        val reachable = candidates.any { local ->
                            if (!plan.builtStanding(local, index.toLong()) || nearShaft &&
                                (abs(local.x - plan.bottom.x) > 1 || abs(local.z - plan.bottom.z) > 1)) false
                            else {
                                val p = CivsWorksiteLayouts.point(mine, local)
                                citizenStandable(world, p) &&
                                    citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false) &&
                                    citizenCanReachBlock(world, Location(world, p.x + .5, p.y + 1.62, p.z + .5), target,
                                        citizenWorkReach(BuildingType.QUARRY))
                            }
                        }
                        withClue("$style step $index $step") { reachable shouldBe true }
                    }
                    world.getBlockAt(target.x, target.y, target.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                }
                world.getBlockAt(bottom.x, bottom.y, bottom.z).type shouldBe Material.LADDER
            }
        }
    }

    test("export actual V5 blocks and exact additional native material operations for review") {
        val directory = Path.of("build", "mine-plan-v5-preview")
        Files.createDirectories(directory)
        val summaries = mutableListOf<Map<String, Any>>()
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
            val old = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            fun bill(p: CivsMinePlan) = p.steps.mapNotNull { it.after?.substringBefore('[')?.removePrefix("minecraft:")?.uppercase() }
                .groupingBy { it }.eachCount().toSortedMap()
            val before = bill(old); val after = bill(plan)
            val delta = after.mapValues { (material, count) -> count - (before[material] ?: 0) }.filterValues { it != 0 }
            val wood = when (style) { "nordic" -> "SPRUCE"; "caledonia" -> "DARK_OAK"; else -> "OAK" }
            val tiers = level + 2
            delta shouldBe sortedMapOf("COBBLESTONE" to 52 + 98 * tiers, "${wood}_LOG" to 8 + 48 * tiers,
                "${wood}_PLANKS" to 81 * tiers, "${wood}_FENCE" to 16 * (tiers + 1))
            val clears = plan.steps.count { it.after == null } - old.steps.count { it.after == null }
            clears shouldBe 60 * tiers
            val summary = mapOf("style" to style, "level" to level, "tiers" to tiers, "steps" to plan.steps.size,
                "before" to before, "after" to after, "additionalMaterials" to delta, "additionalClears" to clears)
            summaries += summary
            Files.writeString(directory.resolve("$style-l$level-v5.json"), Gson().toJson(mapOf(
                "schematicId" to mine.schematicId, "buildingLevel" to level, "version" to plan.version,
                "bottom" to plan.bottom, "backing" to plan.backing, "center" to plan.center,
                "floors" to plan.floors, "entranceLanding" to plan.entranceLanding,
                "shaftEnd" to plan.shaftEnd, "steps" to plan.steps,
                "generator" to plan.generator, "generatorStanding" to plan.generatorStanding, "summary" to summary)))
        }
        Files.writeString(directory.resolve("counts.json"), Gson().toJson(summaries))
    }
})

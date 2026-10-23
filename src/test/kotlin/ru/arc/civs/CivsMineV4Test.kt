package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CivsMineV4Test : FunSpec({
    test("V2 and V3 receipt ordinals retain the exact published 0_1_52 step bytes") {
        // Captured by invoking the private plan constructor in the published 0.1.52
        // JAR, with its bundled Gson. These are old outputs, not a second generator.
        val hashes = listOf(
            "nordic/1/2" to "af5e740df0dbe63b4983a538e432cb6f4a50a32262886217c61add311d0b4214",
            "nordic/1/3" to "d53f07b32eed9a8703fefecc8d069fb7089c765a9e534239afd3fa62278ce095",
            "nordic/2/2" to "49a1c276b6f6d7b0d6b257833f826866062363225a47bad3fafac88264719d3a",
            "nordic/2/3" to "18291381c40b7b8c6cc74811f330e049c2e087067208fa0476c3e8d76dfa5368",
            "nordic/3/2" to "4ab1af43cd0db50225f7457f50496d89bac71541d9c78fb397cf1339e89d19d8",
            "nordic/3/3" to "f15fc98e3c5f279f5dfd4f1ab251ade50595d29c4f04663eb60689e577d33342",
            "medievaloak/1/2" to "30f3947bd2724e72b73bdcd95dc19586d9b5c78a319beb58ac5c76a00741e217",
            "medievaloak/1/3" to "a877aa85308018814dce1dc59afa657a1deb80c0dc1eadf07c9898569ab21dc8",
            "medievaloak/2/2" to "03a45cf6bf6e6644333e305f98d373c78c5699e3152d73cb40143f7e44a7b944",
            "medievaloak/2/3" to "bcdb3d3e6bbf983451d5c2555c00833837eff60d3c462023bc2e6f4e1e85564b",
            "medievaloak/3/2" to "f91b75765ebdd3d0a91186e5bd4da8a532894704604f28f2a5468b60e4832115",
            "medievaloak/3/3" to "431cbc5f48fa12af55ec143723af407bdcd5f944c06360c851ff579279ce744b",
            "caledonia/1/2" to "665445f2f961012cd3dba9e5dfd04fe710e4975fb2a68d921d11bd69c078d3dc",
            "caledonia/1/3" to "65103616bfa8b13258ce3d2dd2593772168824d1d36d7c5e02fc71f93348ca09",
            "caledonia/2/2" to "f13a397b54fe8a5f1d75655739161050b6e6db123ccb11d3ceba8d5cb03655a9",
            "caledonia/2/3" to "e5942171e0b373955fd159e258dc28fc6344078d3de7675d04828ad7cfe72318",
            "caledonia/3/2" to "79eff8bdbfe2b168ba2fda350bf23bd13afa5956c2896ef08c13cfb32cae3d8e",
            "caledonia/3/3" to "99cfa2bf04a806cc1e9a5cb09f4f84040a6a9a90926c47631277aa86e7a3cb4e",
        )
        for ((key, hash) in hashes) {
            val (style, level, version) = key.split('/')
            val mine = spatialBuilding(style, "miner", level.toInt(), CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, version.toInt()))
            val bytes = Gson().toJson(plan.steps).toByteArray(Charsets.UTF_8)
            withClue(key) { MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() shouldBe hash }
        }
    }

    test("all styles have one continuous ladder aligned open tiers and connected three-wide branch junctions") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            plan.version shouldBe 4
            val rungs = plan.steps.filter { it.after?.startsWith("minecraft:ladder[") == true }.map { it.point }
            rungs.toSet() shouldBe (plan.lowest until plan.bottom.y).map { plan.bottom.copy(y = it) }.toSet()
            rungs.size shouldBe rungs.toSet().size
            plan.floors.size shouldBe level + 2
            plan.floors.zipWithNext().all { (a, b) -> a.feetY - b.feetY == 4 } shouldBe true
            plan.floors.map { it.cells.map { p -> p.x to p.z }.toSet() }.distinct().size shouldBe 1
            val changes = plan.steps.groupBy { it.point }
            for (y in plan.lowest..plan.bottom.y) for ((x, z) in plan.openingColumns) {
                val at = changes.getValue(CivPosition(x, y, z))
                at.all { it.after == null } shouldBe true
            }
            val forward = when (plan.facing) { "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0 }
            fun point(d: Int, s: Int) = plan.center.x + forward.first * d - forward.second * s to
                plan.center.z + forward.second * d + forward.first * s
            for (floor in plan.floors) {
                floor.height shouldBe 3
                floor.cells.size shouldBe floor.cells.toSet().size
                val columns = floor.cells.map { it.x to it.z }.toSet()
                columns.intersect(plan.openingColumns).isEmpty() shouldBe true
                (plan.bottom.x to plan.bottom.z in columns) shouldBe false
                (plan.backing.x to plan.backing.z in columns) shouldBe false
                for (distance in 5..20) for (side in -1..1) {
                    (point(distance, side) in columns) shouldBe true
                    (point(side, distance) in columns) shouldBe true
                    (point(side, -distance) in columns) shouldBe true
                }
                for (side in listOf(-1, 1)) for (direction in listOf(-1, 1))
                    for (distance in 1..8) for (width in -1..1)
                        (point(direction * distance, side * 12 + width) in columns) shouldBe true
                // Nine solid columns remain between the main arm and each secondary arm.
                for (d in 5..8) for (s in 2..10) (point(d, s) in columns) shouldBe false
                for (rotation in listOf(0, 90, 180, 270)) {
                    val rotated = mine.copy(rotation = rotation)
                    floor.cells.forEach { p -> CivsMinePlan.localPoint(rotated, CivsWorksiteLayouts.point(rotated, p)) shouldBe p }
                }
            }
        }
    }

    test("every operation approaches from an already open supported predecessor and all landings precede branches") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val prefix = mutableMapOf<CivPosition, String?>()
            for ((index, step) in plan.steps.withIndex()) {
                val approach = requireNotNull(step.approach)
                withClue("$style L$level step $index $step") {
                    plan.builtStanding(approach, index.toLong()) shouldBe true
                    val onLadder = prefix[approach]?.startsWith("minecraft:ladder[") == true
                    val supported = prefix[approach.copy(y = approach.y - 1)] == "minecraft:cobblestone"
                    (approach.y >= plan.bottom.y || onLadder || supported) shouldBe true
                    (step.point == approach || step.point == approach.copy(y = approach.y + 1)) shouldBe false
                }
                prefix[step.point] = step.after
            }
            plan.steps.take(plan.shaftEnd).all { it.stage == "shaft" } shouldBe true
            plan.steps.drop(plan.shaftEnd).all { it.stage == "gallery" } shouldBe true
            for (floor in listOf(requireNotNull(plan.entranceLanding)) + plan.floors) {
                val firstOpening = plan.steps.indexOfFirst { it.floor == floor.index && it.branch == "opening" }
                val opening = plan.steps.filter { it.floor == floor.index && it.branch == "opening" }
                opening.map { it.point.y }.distinct() shouldBe (floor.feetY..floor.feetY + floor.height).toList()
                opening.all { it.approach?.y == floor.feetY } shouldBe true
                val landingComplete = plan.steps.indexOfLast { it.floor == floor.index &&
                    it.branch in setOf("entrance", "landing") }
                (landingComplete in 0 until firstOpening) shouldBe true
                (firstOpening < plan.shaftEnd) shouldBe true
                floor.torches.filter { p -> floor.cells.take(floor.sections.first().second).any {
                    it.x == p.x && it.z == p.z
                } }.forEach { torch ->
                    (plan.steps.indexOfFirst { it.point == torch && it.after == "minecraft:torch" } in 0 until firstOpening) shouldBe true
                }
            }
        }
    }

    test("retained V3 prefix admits the observed escape ladder but excludes the disconnected future stance") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
        val old = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
        for (world in listOf(CivPosition(-96, 59, -47), CivPosition(-96, 60, -47), CivPosition(-96, 61, -47),
            CivPosition(-93, 61, -47), CivPosition(-93, 62, -47), CivPosition(-93, 63, -47)))
            withClue(world) { old.builtStanding(CivsMinePlan.localPoint(mine, world), 83) shouldBe true }
        val future = CivsMinePlan.localPoint(mine, CivPosition(-96, 55, -47))
        old.containsWorkStanding(future) shouldBe true
        old.builtStanding(future, 83) shouldBe false
        old.builtStanding(old.bottom, -1) shouldBe false
        old.builtStanding(old.bottom, old.steps.size + 1L) shouldBe false
    }

    test("export the actual V4 geometry and approaches for offline spatial review") {
        val directory = Path.of("build", "mine-plan-v4-preview")
        Files.createDirectories(directory)
        val summaries = mutableListOf<Map<String, Any>>()
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val name = "$style-l$level-v4.json"
            Files.writeString(directory.resolve(name), Gson().toJson(mapOf(
                "schematicId" to mine.schematicId, "buildingLevel" to level, "version" to plan.version,
                "bottom" to plan.bottom, "backing" to plan.backing, "center" to plan.center,
                "floors" to plan.floors, "entranceLanding" to plan.entranceLanding,
                "shaftEnd" to plan.shaftEnd, "steps" to plan.steps,
                "generator" to plan.generator, "generatorStanding" to plan.generatorStanding)))
            summaries += mapOf("style" to style, "level" to level, "floors" to plan.floors.size,
                "steps" to plan.steps.size, "shaftSteps" to plan.shaftEnd,
                "clears" to plan.steps.count { it.after == null },
                "cobblestone" to plan.steps.count { it.after == "minecraft:cobblestone" },
                "ladders" to plan.steps.count { it.after?.startsWith("minecraft:ladder[") == true },
                "torches" to plan.steps.count { it.after == "minecraft:torch" })
        }
        Files.writeString(directory.resolve("counts.json"), Gson().toJson(summaries))
    }
})

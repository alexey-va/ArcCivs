package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsHallProofGuardTest : FunSpec({
    test("a Hall prefix grants no work on independent routes, authored transit, foreign plots or missing founding captures") {
        val hall = CivBuilding(UUID(0,1), BuildingType.TOWN_HALL, 1, CivPosition(0,68,0), 0, "fixture/hall",
            CivBox(CivPosition(-4,66,-7),CivPosition(4,78,-1)), CivPosition(0,69,0),BuildingPhase.ACTIVE)
        val site = JsonParser.parseString("""{"schematicId":"fixture/hall","level":1,"type":"TOWN_HALL",
            "schematicBounds":{"min":[-4,-2,-7],"max":[4,10,-1]},"forecourt":["minecraft:dirt_path"]}""").asJsonObject
        CivsWorksiteLayouts.installCatalog(mapOf(hall.schematicId to site))
        try {
            val house = hall.copy(id=UUID(0,2),type=BuildingType.HOUSE,schematicId="fixture/house",rotation=180,
                entrance=CivPosition(0,67,12),anchor=CivPosition(0,66,12),
                bounds=CivBox(CivPosition(-2,64,13),CivPosition(2,74,16)))
            val city = Settlement(UUID(0,10),UUID(0,11),"Guard", UUID(0,12),hall.anchor,60,"nordic",Stock(),
                listOf(hall,house),emptyList(),autonomy=CivAutonomy())
            val route=(0..12).map { d -> CivPosition(0,69-minOf(d,2),d) }
            val envelope=CivBox(CivPosition(-15,55,-15),CivPosition(20,85,20))
            val founding=CivPlacementPlan(hall.id,city.id,city.worldId,hall,emptyList(),capturedSha256="a".repeat(64))
            val outbound=CivPlacementPlan(house.id,city.id,city.worldId,house,emptyList(),capturedSha256="b".repeat(64),
                terrain=CivTerraformPlan(envelope,emptyList(),route,house.anchor.y,0,0))
            val originals=listOf(founding,outbound)
            fun targets(state:Settlement,plans:List<CivPlacementPlan>)=CivsRoadMaintenance.prepare(state,plans,false).targets.filter { it.hall!=null }
            val admitted=targets(city,originals)
            check(admitted.isNotEmpty())
            targets(city,listOf(outbound)).size shouldBe 0
            targets(city,listOf(founding.copy(adminPlacement=true),outbound)).size shouldBe 0
            targets(city,listOf(founding.copy(worldId=UUID(0,99)),outbound)).size shouldBe 0
            val shared=admitted.first { it.floor==CivPosition(0,68,1) }
            val foreign=house.copy(id=UUID(0,3),anchor=CivPosition(10,65,4),entrance=CivPosition(10,66,3),
                bounds=CivBox(CivPosition(9,64,4),CivPosition(11,72,7)))
            val withForeign=city.copy(buildings=city.buildings+foreign)
            val crossing=(0..10).map { x->CivPosition(x,66,3) }
            val independent=outbound.copy(id=foreign.id,building=foreign,terrain=CivTerraformPlan(envelope,emptyList(),crossing,65,0,0))
            targets(withForeign,originals+independent).size shouldBe 0
            val onPlot=foreign.copy(bounds=CivBox(CivPosition(-2,65,1),CivPosition(0,75,2)))
            targets(city.copy(buildings=city.buildings+onPlot),originals).size shouldBe 0
            val road=foreign.copy(type=BuildingType.ROAD,bounds=CivBox(shared.floor,shared.floor),
                transit=listOf(shared.floor.copy(y=shared.floor.y+1)))
            targets(city.copy(buildings=city.buildings+road),originals).size shouldBe 0
            val stagedRoute = (0..12).map { d -> CivPosition(0, when (d) { in 0..2 -> 69; in 3..5 -> 68; else -> 67 }, d) }
            val staged = outbound.copy(terrain=CivTerraformPlan(envelope,emptyList(),stagedRoute,66,0,0))
            val wall = foreign.copy(type=BuildingType.WALL,anchor=CivPosition(10,66,6),entrance=CivPosition(10,67,6),
                bounds=CivBox(CivPosition(11,65,6),CivPosition(12,70,8)))
            fun cross(y: Int): List<CivRoadUpkeep> {
                val paidCrossing = (-8..10).map { x -> CivPosition(x, if (x>5) 67 else y, 6) }
                val captured = outbound.copy(id=wall.id,building=wall,terrain=CivTerraformPlan(envelope,emptyList(),paidCrossing,66,0,0))
                return targets(city.copy(buildings=city.buildings+wall), listOf(founding, staged, captured))
            }
            val fit=cross(67)
            for (lane in -1..1) {
                fit.single { it.floor == CivPosition(lane,67,5) }.after shouldBe
                    "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
                fit.single { it.floor == CivPosition(lane,66,6) }.after shouldBe "minecraft:dirt_path"
            }
            cross(68).size shouldBe 0
            val beside = (1..10).map { CivPosition(2,67,it) } + (3..10).map { CivPosition(it,67,10) }
            val parallel=wall.copy(entrance=beside.last(),bounds=CivBox(CivPosition(11,65,10),CivPosition(12,70,12)))
            val sidePlan=outbound.copy(id=parallel.id,building=parallel,terrain=CivTerraformPlan(envelope,emptyList(),beside,66,0,0))
            targets(city.copy(buildings=city.buildings+parallel),listOf(founding,staged,sidePlan)).size shouldBe 0
            // A transverse street just beyond the flat end owns the edge of its original paving.
            // The Hall can repair its own lawn while making no claim on those signed shoulder floors.
            val transverse = (-8..0).map { CivPosition(it,67,4) } +
                (0..10).map { CivPosition(it,67,5) }
            fun terminalStreet(points: List<CivPosition>): List<CivRoadUpkeep> {
                val owner = wall.copy(entrance=points.last())
                val plan = outbound.copy(id=owner.id,building=owner,
                    terrain=CivTerraformPlan(envelope,emptyList(),points,points.last().y-1,0,0))
                return targets(city.copy(buildings=city.buildings+owner),originals+plan)
            }
            val ending = terminalStreet(transverse)
            check(ending.isNotEmpty())
            for (s in -1..1) {
                check(ending.none { it.floor.x == s && it.floor.z == 3 })
                ending.single { it.floor == CivPosition(s,67,2) }.after shouldBe
                    "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
            }
            // Omitting a row for one compatible street must not conceal another owner there.
            val firstStreet = wall.copy(entrance=transverse.last())
            val secondRoute = transverse.map { it.copy(y=68) }
            val secondStreet = firstStreet.copy(id=UUID(0,44),entrance=secondRoute.last())
            val firstPlan = outbound.copy(id=firstStreet.id,building=firstStreet,
                terrain=CivTerraformPlan(envelope,emptyList(),transverse,66,0,0))
            val secondPlan = outbound.copy(id=secondStreet.id,building=secondStreet,
                terrain=CivTerraformPlan(envelope,emptyList(),secondRoute,67,0,0))
            targets(city.copy(buildings=city.buildings+firstStreet+secondStreet),
                originals+firstPlan+secondPlan).size shouldBe 0
            terminalStreet(transverse.map { it.copy(y=68) }).size shouldBe 0
            terminalStreet((4..10).map { CivPosition(0,67,it) } + (1..10).map { CivPosition(it,67,10) }).size shouldBe 0
            val inside = (-8..-1).map { CivPosition(it,67,4) } + (-1..1).map { CivPosition(it,if (it==0) 66 else 67,3) } +
                listOf(CivPosition(1,67,4)) + (1..10).map { CivPosition(it,67,5) }
            terminalStreet(inside).size shouldBe 0
            // The three Hall lanes still have their exact old Y. The adjacent lower side cell
            // makes that paid shoulder a stair, so it cannot stand in for the Hall's flat end.
            terminalStreet(transverse.map { if (it.z==4 && it.x== -2) it.copy(y=66) else it }).size shouldBe 0
            val proof=requireNotNull(shared.hall)
            val approach=requireNotNull(CivsTownHallApproach.from(city,hall,proof))
            val prior=route[1].copy(y=route[1].y-1)
            approach.paidFinish(prior,"minecraft:spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]") shouldBe true
            approach.paidFinish(prior,"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]") shouldBe false
            approach.paidFinish(prior,"minecraft:spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]") shouldBe false
            check(runCatching { shared.copy(hall=proof.copy(founding=proof.founding.copy(id=UUID(0,44)))).validated(city) }.isFailure)
            check(runCatching { shared.copy(after="minecraft:oak_planks").validated(city) }.isFailure)
            check(runCatching { shared.copy(floor=shared.floor.copy(x=15)).validated(city) }.isFailure)
            check(runCatching { shared.copy(route=route).validated(city) }.isFailure)
            // .301 used the old currently sealed centre mask, including the left lane. The newer physical axis is separate.
            val shifted = site.deepCopy().also { it.add("hallApproach", JsonParser.parseString(
                """{"axis":1,"rows":["minecraft:dirt_path"]}""")) }
            CivsWorksiteLayouts.installCatalog(mapOf(hall.schematicId to shifted))
            val historic = CivRoadUpkeep(city.id, city.worldId, city.owner, hall.id, hall, CivPosition(-1,68,-1),
                "minecraft:dirt_path", listOf(CivPosition(0,69,1),hall.entrance), emptyList())
            val oldJson = com.google.gson.Gson().toJsonTree(historic).asJsonObject.also { it.remove("hall") }
            val restored = com.google.gson.Gson().fromJson(oldJson, CivRoadUpkeep::class.java)
            restored.hall shouldBe null
            restored.validated(city)
            check(restored.floor !in requireNotNull(CivsTownHallApproach.from(city,hall,proof)).surfaces)
            CivsWorksiteLayouts.installCatalog(mapOf(hall.schematicId to site))
            shared.validated(city)
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})

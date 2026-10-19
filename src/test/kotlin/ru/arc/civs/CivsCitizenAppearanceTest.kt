package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.net.URI
import java.util.Base64
import java.util.UUID

class CivsCitizenAppearanceTest : FunSpec({
    test("bundled skins have signed texture snapshots and stable varied resident selection") {
        val skins = loadCitizenSkins()
        (skins.size >= 2) shouldBe true
        (skins.map { it.texture }.distinct().size >= 2) shouldBe true
        for (skin in skins) {
            val texture = String(Base64.getDecoder().decode(skin.texture), Charsets.UTF_8)
            val url = URI.create(JsonParser.parseString(texture).asJsonObject.getAsJsonObject("textures")
                .getAsJsonObject("SKIN").get("url").asString)
            url.host shouldBe "textures.minecraft.net"
            url.path.startsWith("/texture/") shouldBe true
            (Base64.getDecoder().decode(skin.signature).size >= 256) shouldBe true
        }
        val workerIds = (0 until skins.size).map { UUID(0, it.toLong()) }
        val selected = workerIds.map { citizenSkin(skins, it) }
        selected.toSet().size shouldBe skins.size
        workerIds.map { citizenSkin(loadCitizenSkins(), it) } shouldBe selected
        (citizenSkin(skins, UUID(0, Int.MIN_VALUE.toLong())) in skins) shouldBe true
    }

    test("each profession carries a recognizable tool and an unassigned resident has an empty hand") {
        citizenTool(appearancePlan(BuildingType.TOWN_HALL)) shouldBe Material.IRON_SHOVEL
        citizenTool(appearancePlan(BuildingType.BUILDER_HUT)) shouldBe Material.IRON_SHOVEL
        citizenTool(appearancePlan(BuildingType.BUILDER_HUT, "placing")) shouldBe Material.BRICKS
        citizenTool(appearancePlan(BuildingType.FORESTER)) shouldBe Material.IRON_AXE
        citizenTool(appearancePlan(BuildingType.QUARRY)) shouldBe Material.IRON_PICKAXE
        citizenTool(appearancePlan(BuildingType.FARM)) shouldBe Material.IRON_HOE
        citizenTool(appearancePlan(null, "unassigned")) shouldBe null
    }

    test("work gestures stop throughout travel delivery waiting pause and closed lifecycle") {
        val working = appearancePlan(BuildingType.FORESTER)
        for (phase in CitizenPhase.entries) {
            citizenWorking(working, phase, null) shouldBe (phase == CitizenPhase.WORKING)
        }
        for (reason in listOf("unassigned", "no-construction", "waiting-materials", "construction-paused",
            "building-unavailable")) {
            for (phase in CitizenPhase.entries) {
                citizenWorking(appearancePlan(BuildingType.BUILDER_HUT, reason), phase,
                    ConstructionPhase.PLACING) shouldBe false
            }
        }
        val placing = appearancePlan(BuildingType.BUILDER_HUT, "placing")
        for (phase in ConstructionPhase.entries) {
            citizenWorking(placing, CitizenPhase.IDLE, phase) shouldBe (phase == ConstructionPhase.PLACING)
        }
        citizenWorking(placing, CitizenPhase.CLOSED, ConstructionPhase.PLACING) shouldBe false
    }

    test("a late work pulse performs one swing and never replays gestures missed during a pause") {
        val animation = CitizenWorkAnimation()
        animation.swing(1_000) shouldBe true
        repeat(5) { animation.swing(1_000) shouldBe false }
        animation.swing(1_999) shouldBe false
        animation.swing(2_000) shouldBe true
        animation.swing(60_000) shouldBe true
        animation.swing(60_001) shouldBe false
        animation.swing(61_000) shouldBe true
    }
})

private fun appearancePlan(type: BuildingType?, idleReason: String? = null) = CitizenPlan(
    CitizenToken(UUID(0, 1), 0, 0, null), CivPosition(0, 64, 0), CivPosition(10, 64, 0),
    type, 10_000, idleReason,
)

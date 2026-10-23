package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URI
import java.util.Base64
import java.util.UUID

class CivsCitizenAppearanceTest : FunSpec({
    test("persisted sex selects the matching signed pool independently of UUID and profession") {
        val skins = loadCitizenSkins()
        for (id in (0L..20L).map { UUID(0, it) }) for (sex in CivSex.entries) {
            val worker = CivWorker(id, if (sex == CivSex.FEMALE) "Мария" else "Иван", null, sex = sex)
            citizenSkin(skins, worker).sex shouldBe sex
            citizenSkin(skins, worker.copy(assignment = UUID.randomUUID())) shouldBe citizenSkin(skins, worker)
        }
    }

    test("reviewed signed skin identities stay in the matching visual pool") {
        val skins = loadCitizenSkins()
        skins.filter { it.sex == CivSex.MALE }.map { it.name } shouldBe
            listOf("civs-citizen-1", "civs-citizen-2", "civs-citizen-4")
        skins.filter { it.sex == CivSex.FEMALE }.map { it.name } shouldBe
            listOf("civs-citizen-3", "civs-citizen-female-1")
        // The historical "Лука / Medieval Boy" label did not describe this signed asset.
        val fairHair = skins.single { it.name == "civs-citizen-3" }
        val texture = JsonParser.parseString(String(Base64.getDecoder().decode(fairHair.texture), Charsets.UTF_8))
        URI.create(texture.asJsonObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString)
            .path shouldBe "/texture/7d2b48a91b2e1f4387a45981023cf9d7da88c7cbd67d99eee4cbbf8ce0face35"
        fairHair.sex shouldBe CivSex.FEMALE
    }

    test("existing named residents with explicit or legacy sex select the corrected deterministic pool") {
        val skins = loadCitizenSkins()
        val residents = listOf(
            Triple("Вячеслав", "c4302bcb-3060-436c-9eed-4b4a9a017a57", "civs-citizen-1"),
            Triple("Дмитрий", "9e0dab98-8258-49ec-930c-10a3a320a2fd", "civs-citizen-4"),
        )
        for ((name, id, skin) in residents) for (sex in listOf(null, CivSex.MALE)) {
            val worker = CivWorker(UUID.fromString(id), name, null, sex = sex)
            citizenSkin(skins, worker).name shouldBe skin
            citizenSkin(skins, worker).sex shouldBe CivSex.MALE
            citizenSkin(loadCitizenSkins(), worker.copy(assignment = UUID(0, 7))) shouldBe citizenSkin(skins, worker)
        }
        for (sex in listOf(null, CivSex.FEMALE)) {
            val worker = CivWorker(UUID.fromString("71c4eaae-8a08-45d4-9537-b58ec387006a"), "Валерия", null, sex = sex)
            citizenSkin(skins, worker).name shouldBe "civs-citizen-3"
            citizenSkin(skins, worker).sex shouldBe CivSex.FEMALE
        }
    }

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

    test("profession props never invent the work tool a resident still needs to receive") {
        citizenTool(appearancePlan(BuildingType.TOWN_HALL)) shouldBe Material.BOOK
        citizenTool(appearancePlan(BuildingType.BUILDER_HUT, "placing")) shouldBe null
        for (type in listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY,
            BuildingType.FARM, BuildingType.SAWMILL, BuildingType.BLACKSMITH, BuildingType.STONEMASON)) {
            citizenTool(appearancePlan(type)) shouldBe null
            citizenTool(appearancePlan(type, "waiting-tool")) shouldBe null
        }
        citizenTool(appearancePlan(BuildingType.LIBRARY)) shouldBe Material.FEATHER
        citizenTool(appearancePlan(null, "unassigned")) shouldBe null
    }

    test("a courier parcel does not duplicate its cargo as an oversized hand prop") {
        val warehouse = UUID(0, 20)
        val destination = UUID(0, 21)
        val cargo = CivCargo(UUID(0, 22), warehouse, warehouse, destination, CivPosition(0, 64, 0), "OAK_LOG", 64)
        val courier = CivCourierPlan(cargo, destination, CivPosition(4, 64, 0), true)
        citizenTool(appearancePlan(BuildingType.WAREHOUSE).copy(courier = courier)) shouldBe null
        citizenTool(appearancePlan(BuildingType.WAREHOUSE).copy(courier = courier.copy(carrying = false))) shouldBe null
    }

    test("children use half scale and retain the same signed skin after growing up") {
        val adult = CivWorker(UUID(0, 12), "Resident", null)
        val child = adult.copy(childMinutes = 0, parentIds = setOf(UUID(0, 10), UUID(0, 11)))
        citizenScale(child) shouldBe .5
        citizenScale(child.copy(childMinutes = 39)) shouldBe .5
        citizenScale(adult) shouldBe 1.0
        val skins = loadCitizenSkins()
        citizenSkin(skins, child.id) shouldBe citizenSkin(skins, adult.id)
    }

    test("work gestures stop throughout travel delivery waiting pause and closed lifecycle") {
        val working = appearancePlan(BuildingType.FORESTER)
        for (phase in CitizenPhase.entries) {
            citizenWorking(working, phase) shouldBe (phase == CitizenPhase.WORKING)
        }
        for (reason in listOf("unassigned", "no-construction", "waiting-materials", "construction-paused",
            "building-unavailable")) {
            for (phase in CitizenPhase.entries) {
                citizenWorking(appearancePlan(BuildingType.BUILDER_HUT, reason), phase) shouldBe false
            }
        }
        val placing = appearancePlan(BuildingType.BUILDER_HUT, "placing")
        citizenWorking(placing, CitizenPhase.IDLE) shouldBe false
        citizenWorking(placing, CitizenPhase.CLOSED) shouldBe false
    }

    test("only a matching stationary builder presents accepted materials and a timer never fabricates a placement") {
        val order = UUID.randomUUID()
        val plan = appearancePlan(BuildingType.BUILDER_HUT, "placing")
            .let { it.copy(token = it.token.copy(constructionId = order)) }
        for (phase in CitizenPhase.entries) {
            citizenWorking(plan, phase) shouldBe false
            citizenConstructionWorking(plan, phase, order) shouldBe
                (phase in setOf(CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING))
            citizenConstructionWorking(plan, phase, UUID.randomUUID()) shouldBe false
        }
        citizenConstructionWorking(plan.copy(idleReason = "waiting-materials"), CitizenPhase.WORKING, order) shouldBe false
        MockBukkitTestRuntime.open().use {
            citizenPlacementItem(Material.OAK_PLANKS) shouldBe Material.OAK_PLANKS
            citizenPlacementItem(Material.STONE) shouldBe Material.STONE
            citizenPlacementItem(Material.WATER) shouldBe Material.WATER_BUCKET
            citizenPlacementItem(Material.AIR) shouldBe null
        }
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

    test("field care props require matching farm inputs and an explicit crop focus") {
        val plan = appearancePlan(BuildingType.FARM)
        val focus = CivPosition(12, 64, 0)
        val watering = CivWorldWork(UUID(0, 3), 0, requireNotNull(plan.token.assignmentId),
            requireNotNull(plan.workplace), emptyList(), durationMillis = 4_000, status = "watering", focus = focus)
        citizenWorkFocus(watering) shouldBe focus
        citizenFieldCareItem(plan.copy(worldWork = watering)) shouldBe Material.WATER_BUCKET
        citizenFieldCareItem(plan.copy(worldWork = watering.copy(status = "fertilizing"))) shouldBe null
        val fertilizing = watering.copy(status = "fertilizing", inputs = mapOf("BONE_MEAL" to 1L))
        citizenFieldCareItem(plan.copy(worldWork = fertilizing)) shouldBe Material.BONE_MEAL
        citizenFieldCareItem(plan.copy(worldWork = fertilizing.copy(inputs = mapOf("BONE_MEAL" to 0L)))) shouldBe null
        citizenFieldCareItem(plan.copy(type = BuildingType.QUARRY, worldWork = watering)) shouldBe null
        citizenFieldCareItem(plan.copy(idleReason = "waiting-growth", worldWork = watering)) shouldBe null
        citizenFieldCareItem(plan.copy(worldWork = watering.copy(focus = null))) shouldBe null
        citizenWorkFocus(watering.copy(status = "working-field")) shouldBe null
        citizenWorkFocus(watering.copy(changes = listOf(CivPlanBlock(focus.copy(y = focus.y - 1),
            "minecraft:farmland[moisture=3]", "minecraft:farmland[moisture=7]")))) shouldBe focus
        // Ordinary physical work may not substitute an unrelated block for the changed block.
        citizenWorkFocus(watering.copy(changes = listOf(CivPlanBlock(focus.copy(x = 13),
            "minecraft:stone", "minecraft:air")))) shouldBe null
    }
})

private fun appearancePlan(type: BuildingType?, idleReason: String? = null) = CitizenPlan(
    CitizenToken(UUID(0, 1), 0, 0, null), CivPosition(0, 64, 0), CivPosition(10, 64, 0),
    type, 10_000, idleReason,
)

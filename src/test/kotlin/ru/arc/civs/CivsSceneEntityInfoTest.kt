package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsSceneEntityInfoTest : FunSpec({
    val worldId = UUID(0, 1)
    val workerId = UUID(0, 2)
    val id = "packet-npc:$workerId"
    val bounds = ArcSceneBounds(worldId, 0.0, 0.0, 0.0, 16.0, 128.0, 16.0)
    val homeId = UUID(0, 3)
    val jobId = UUID(0, 4)
    fun building(id: UUID, type: BuildingType) = CivBuilding(id, type, 2, CivPosition(1, 64, 1), 0, "captured",
        CivBox(CivPosition(0, 64, 0), CivPosition(4, 68, 4)), CivPosition(1, 64, 0), BuildingPhase.ACTIVE)
    fun city() = Settlement(UUID(0, 5), UUID(9, 1), "Берёзки", worldId, CivPosition(0, 64, 0), 128, "medieval", Stock(900, 800, 700),
        listOf(building(homeId, BuildingType.HOUSE), building(jobId, BuildingType.FARM)),
        listOf(CivWorker(workerId, "Анна", jobId, homeId = homeId, inventory = mapOf("DIAMOND" to 99))),
        members = setOf(UUID(9, 2)), memberNames = mapOf("private-owner" to "private-member"))
    fun carrier(valid: Boolean = true, visible: Boolean = true, uuid: UUID = workerId) = mockk<PacketPlayerNpc> {
        every { isValid } returns valid
        every { isVisibleByDefault } returns visible
        every { uniqueId } returns uuid
    }
    fun snapshot(actor: PacketPlayerNpc? = carrier()) = CivsNpcHologramSnapshot(workerId, worldId, 1.0, 64.0, 1.0,
        "Анна", "workers.status.waiting", 18.5, 22.0, actor = actor, profession = "farmer", hunger = 7,
        cargo = mapOf("DIAMOND" to 99))

    test("public resident detail contains only Russian plain public fields and detached literal text") {
        MockBukkitTestRuntime.open().use {
            val text = CivsText.fromYaml(civsLocaleSource(), defaultLocale = "en")
            val info = requireNotNull(civsSceneEntityInfo(text, bounds, setOf(id), snapshot(), city()))
            info.entityId shouldBe id
            info.kind shouldBe "resident"
            info.title shouldBe "Анна"
            info.fields.associate { it.key to (it.label to it.value) } shouldBe linkedMapOf(
                "settlement" to ("Город" to "Берёзки"),
                "profession" to ("Профессия" to "Фермер"),
                "status" to ("Состояние" to "Ожидает работу"),
                "home" to ("Дом" to "Дом · Уровень 2"),
                "workplace" to ("Место работы" to "Ферма · Уровень 2"),
                "health" to ("Здоровье" to "18.5 / 22"),
                "hunger" to ("Сытость" to "7 / 20"),
            )
            val changed = city().copy(name = "<red>Literal village</red>", workers = city().workers.map { it.copy(name = "Новое имя") })
            civsSceneEntityInfo(text, bounds, setOf(id), snapshot().copy(name = "<red>Анна</red>"), changed)!!.title shouldBe "<red>Анна</red>"
            info.title shouldBe "Анна"
            info.fields.first().value shouldBe "Берёзки"
            civsSceneEntityInfo(text, bounds, setOf(id), snapshot().copy(status = "workers.status.waiting-inputs",
                statusInputs = listOf("GRAVEL")), city())!!.fields.first { it.key == "status" }.value shouldBe "Ждёт: Гравий"
        }
    }

    test("unrequested hidden retired foreign out of bounds and dead actors never disclose information") {
        MockBukkitTestRuntime.open().use {
            val text = CivsText.fromYaml(civsLocaleSource())
            fun query(npc: CivsNpcHologramSnapshot, city: Settlement = city(), ids: Set<String> = setOf(id)) =
                civsSceneEntityInfo(text, bounds, ids, npc, city)
            query(snapshot(), ids = setOf("someone-else")) shouldBe null
            query(snapshot(actor = null)) shouldBe null
            query(snapshot(carrier(visible = false))) shouldBe null
            query(snapshot(carrier(valid = false))) shouldBe null
            query(snapshot(carrier(uuid = UUID(0, 99)))) shouldBe null
            query(snapshot().copy(worldId = UUID(0, 99))) shouldBe null
            query(snapshot().copy(x = 16.0)) shouldBe null
            query(snapshot().copy(y = 128.0)) shouldBe null
            query(snapshot().copy(health = 0.0)) shouldBe null
            query(snapshot().copy(health = Double.NaN)) shouldBe null
            query(snapshot(), city().copy(workers = emptyList())) shouldBe null
            query(snapshot(), city().copy(pendingDeletion = CivDeletion(null))) shouldBe null
        }
    }

    test("missing public home workplace and unknown status rows are omitted without invented values") {
        MockBukkitTestRuntime.open().use {
            val text = CivsText.fromYaml(civsLocaleSource())
            val city = city().copy(buildings = emptyList())
            val info = requireNotNull(civsSceneEntityInfo(text, bounds, setOf(id),
                snapshot().copy(status = "unknown-private-status", profession = "unknown-private-job"), city))
            info.fields.map { it.key } shouldBe listOf("settlement", "health", "hunger")
            civsSceneEntityInfo(text, bounds, setOf(id), snapshot().copy(child = true), city)!!
                .fields.first { it.key == "profession" }.value shouldBe "Ребёнок"
        }
    }
})

package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsTavernMealsTest : FunSpec({
    test("a retired pickup cannot inherit a replacement service for the same guest") {
        residentRuntime { h ->
            try {
                val keeperId = h.standingTavernkeeper()
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == keeperId) it else it.copy(inventory = mapOf("IRON_AXE" to 1L))
                })
                h.tavernServiceSource = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(8, 64, 0))
                val queued = mutableListOf<CivResidentFoodSource>()
                h.physicalFoodPickup = { source ->
                    queued += source
                    CompletableFuture<Settlement>()
                }
                h.owner.residentLifeEnabled = true
                h.navigationNow = 5_000
                h.owner.pulse(5_000)
                fun awaitPickup(count: Int) {
                    for (tick in 1..800) {
                        h.navigate()
                        if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                        if (queued.size == count) return
                    }
                    error("Missing pickup $count: ${h.owner.debug(h.city.id, null)}")
                }
                awaitPickup(1)
                val firstTicket = h.mealState()["ticket"]
                val actors = h.owner.javaClass.getDeclaredField("actors").also { it.isAccessible = true }.get(h.owner) as Map<*, *>
                val guest = requireNotNull(actors[h.workerId]) as CitizenActor
                val life = h.owner.javaClass.getDeclaredField("life").also { it.isAccessible = true }.get(h.owner) as CivsCitizenLife
                life.stopMeal(guest)
                h.navigationNow = requireNotNull(h.navigationNow) + 1_000
                h.owner.pulse(requireNotNull(h.navigationNow))
                awaitPickup(2)
                (h.mealState()["ticket"] != firstTicket) shouldBe true
                val retired = queued.first()
                retired.servingTicket shouldBe firstTicket
                shouldThrow<CivsRejection> {
                    h.owner.validateTavernPickup(h.city.id, keeperId, h.workerId, retired.buildingId, retired.material, retired.servingTicket)
                }.reason shouldBe "stale-meal"
                val current = queued.last()
                current.servingTicket shouldBe h.mealState()["ticket"]
                h.owner.validateTavernPickup(h.city.id, keeperId, h.workerId, current.buildingId, current.material, current.servingTicket)
                h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("all four recorded adjacent chairs admit adult and child geometry and retain their stable identity") {
        residentRuntime { h ->
            try {
                h.prepareDiningSeat()
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.world.getBlockAt(6, 65, 1).type = Material.AIR
                h.world.getBlockAt(6, 64, 1).type = Material.SPRUCE_PLANKS
                val chairs = listOf(Triple("north", CivPosition(6, 64, 0), 0f), Triple("south", CivPosition(6, 64, 2), 180f),
                    Triple("west", CivPosition(5, 64, 1), 270f), Triple("east", CivPosition(7, 64, 1), 90f))
                val rows = com.google.gson.JsonArray()
                fun record(id: String, kind: String, point: CivPosition, state: String, facing: String? = null) {
                    rows.add(com.google.gson.JsonObject().apply {
                        addProperty("id", id); addProperty("kind", kind)
                        add("point", com.google.gson.JsonParser.parseString("[${point.x - 6},${point.y - 64},${point.z}]"))
                        addProperty("tableId", "main/table"); addProperty("group", "main")
                        addProperty("currentVanillaBlockState", state); addProperty("sourceId", "fixture/tavern1")
                        addProperty("sourceSha256", "a".repeat(64)); facing?.let { addProperty("facing", it) }
                    })
                }
                record("main/table", "table", CivPosition(6, 64, 1), "minecraft:spruce_planks")
                chairs.forEach { (facing, point, _) ->
                    val state = "minecraft:spruce_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
                    h.world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state)
                    record("main/chair-$facing", "chair", point, state, facing)
                }
                h.world.getBlockAt(6, 63, 0).type = Material.DIRT_PATH
                CivsWorksiteLayouts.installCatalog(mapOf(tavern.schematicId to com.google.gson.JsonObject().apply {
                    add("tavern", com.google.gson.JsonObject().apply { add("furniture", rows) })
                }))
                citizenDiningCandidates(tavern).toSet() shouldBe chairs.map { it.second }.toSet()
                var observations = 0
                for (scale in listOf(.5, 1.0)) for ((facing, point, yaw) in chairs) {
                    val seat = withClue("scale=$scale facing=$facing") {
                        requireNotNull(citizenDiningSeat(h.world, tavern, point, scale) { true })
                    }
                    seat.feet.y + .7040625 * scale shouldBe 64.5
                    seat.feet.yaw shouldBe yaw
                    seat.furnitureId shouldBe "main/chair-$facing"
                    seat.table shouldBe CivPosition(6, 64, 1)
                    seat.tableContact shouldBe true
                    citizenDiningSeatClear(h.world, seat) shouldBe true
                    h.world.getBlockAt(6, 64, 1).type = Material.STONE
                    citizenDiningSeatClear(h.world, seat) shouldBe false
                    citizenDiningSeat(h.world, tavern, point, scale) { true } shouldBe null
                    h.world.getBlockAt(6, 64, 1).type = Material.SPRUCE_PLANKS
                    observations++
                }
                observations shouldBe 8
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a chair faces a full block table across a clear knee gap") {
        residentRuntime { h ->
            try {
                h.prepareDiningSeat()
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.world.getBlockAt(6, 64, 2).type = Material.SPRUCE_PLANKS
                h.world.getBlockAt(6, 65, 1).type = Material.AIR
                h.world.getBlockAt(6, 64, 4).blockData = Bukkit.createBlockData(
                    "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
                val seat = requireNotNull(citizenDiningSeat(h.world, tavern, CivPosition(6, 64, 0), 1.0) { true })
                seat.feet.y shouldBe 64.5 - .7040625
                seat.feet.yaw shouldBe 0f
                citizenDiningSeatClear(h.world, seat) shouldBe true
                h.world.getBlockAt(6, 64, 0).blockData = Bukkit.createBlockData(
                    "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
                citizenDiningSeat(h.world, tavern, CivPosition(6, 64, 0), 1.0) { true } shouldBe null
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("serving conserves both real bags and replay cannot deliver or feed twice") {
        residentRuntime { h ->
            try {
                h.prepareDiningSeat()
                val guest = h.city.workers.first { it.id == h.workerId }
                val keeper = h.city.workers.first { it.id != h.workerId }
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == keeper.id) CivsEquipment.collectSupplies(it, mapOf("BREAD" to 4L)) else it
                })
                val initial = h.city
                val beforeKeeper = initial.workers.first { it.id == keeper.id }
                val proof = CivTavernDelivery(UUID.randomUUID(), requireNotNull(keeper.assignment), keeper.id, guest.id,
                    CivsEquipment.slots(beforeKeeper), CivsEquipment.slots(guest), guest.life?.meals ?: 0L, "BREAD", 1)
                val served = CivsTavernMeals.deliver(initial, proof)
                served.stock shouldBe initial.stock
                served.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 6
                served.workers.first { it.id == guest.id }.hunger shouldBe 14
                CivsEquipment.plainCount(served.workers.first { it.id == keeper.id }, "BREAD") shouldBe 3
                shouldThrow<CivsRejection> { CivsTavernMeals.deliver(served, proof) }.reason shouldBe "stale-meal"
                val plan = requireNotNull(CivsResidentLife.mealPlan(served, served.workers.first { it.id == guest.id }))
                val fed = CivsResidentLife.finishMeal(served, plan)
                fed.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 5
                fed.workers.first { it.id == guest.id }.hunger shouldBe 20
                shouldThrow<CivsRejection> { CivsTavernMeals.deliver(fed, proof) }.reason shouldBe "stale-meal"
                shouldThrow<CivsRejection> { CivsTavernMeals.deliver(initial.copy(workers = initial.workers.map {
                    if (it.id == keeper.id) it.copy(assignment = null) else it
                }), proof) }.reason shouldBe "stale-meal"
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (personalBread in listOf(0L, 2L))
    test("a hungry guest sits before real service and eats only after delivery with $personalBread personal bread") {
        residentRuntime { h ->
            try {
                val keeperId = h.standingTavernkeeper()
                val npc = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                h.owner.residentLifeEnabled = true
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == h.workerId) it.copy(inventory = mapOf("IRON_AXE" to 1L) +
                        if (personalBread > 0) mapOf("BREAD" to personalBread) else emptyMap()) else it
                })
                val source = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(8, 64, 0))
                h.tavernServiceSource = source
                var nativeBread = 4
                var pickups = 0
                var deliveries = 0
                var meals = 0
                h.physicalFoodPickup = { order ->
                    npc.isSeated shouldBe true
                    h.usingMainHand shouldBe false
                    order.servingFor shouldBe h.workerId
                    h.owner.validateTavernPickup(h.city.id, keeperId, h.workerId, tavern.id, "BREAD", requireNotNull(order.servingTicket))
                    nativeBread -= 4
                    h.city = h.city.copy(workers = h.city.workers.map {
                        if (it.id == keeperId) CivsEquipment.collectSupplies(it, mapOf("BREAD" to 4L)) else it
                    }, revision = h.city.revision + 1)
                    pickups++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.deliverTavernMeal = { cityId, proof ->
                    h.owner.validateTavernDelivery(cityId, proof)
                    npc.isSeated shouldBe true
                    h.usingMainHand shouldBe false
                    h.city = CivsTavernMeals.deliver(h.city, proof)
                    deliveries++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    deliveries shouldBe 1
                    npc.isSeated shouldBe true
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.navigationNow = 5_000
                h.owner.pulse(5_000)
                withClue(h.owner.debug(h.city.id, h.workerId)) { npc.isSeated shouldBe true }
                h.usingMainHand shouldBe false
                h.city.workers.first { it.id == h.workerId }.hunger shouldBe 14
                for (tick in 1..800) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (meals != 0) break
                }
                withClue("guest=${h.owner.debug(h.city.id, h.workerId)} keeper=${h.owner.debug(h.city.id, keeperId)}") {
                    pickups shouldBe 1
                    deliveries shouldBe 1
                    meals shouldBe 1
                }
                nativeBread shouldBe 0
                h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 3 + personalBread.toInt()
                h.city.workers.first { it.id == h.workerId }.hunger shouldBe 20
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                npc.isSeated shouldBe false
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a city queue longer than two seconds keeps only continuously observed service fresh") {
        residentRuntime { h ->
            try {
                val keeperId = h.standingTavernkeeper()
                val npc = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                h.owner.residentLifeEnabled = true
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == keeperId) CivsEquipment.collectSupplies(it, mapOf("BREAD" to 4L))
                    else it.copy(inventory = mapOf("IRON_AXE" to 1L))
                })
                h.tavernServiceSource = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(8, 64, 0))
                var proof: CivTavernDelivery? = null
                val pending = CompletableFuture<Settlement>()
                h.owner.deliverTavernMeal = { cityId, offered ->
                    h.owner.validateTavernDelivery(cityId, offered)
                    proof = offered
                    pending
                }
                h.navigationNow = 5_000
                h.owner.pulse(5_000)
                for (tick in 1..600) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (proof != null) break
                }
                val accepted = requireNotNull(proof)
                var observedAt = requireNotNull(h.navigationNow)
                repeat(4) { observedAt += 1_000; h.owner.pulse(observedAt) }
                npc.isSeated shouldBe true
                h.usingMainHand shouldBe false
                h.owner.validateTavernDelivery(h.city.id, accepted)
                h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 4
                h.owner.collisionActors().first { it.uniqueId == keeperId }.teleport(org.bukkit.Location(h.world, .5, 64.0, .5))
                observedAt += 1_000; h.owner.pulse(observedAt)
                shouldThrow<CivsRejection> { h.owner.validateTavernDelivery(h.city.id, accepted) }.reason shouldBe "stale-meal"
                pending.completeExceptionally(CivsRejection("stale-meal"))
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (interruption in listOf("night", "service-timeout", "chair-removed"))
    test("$interruption cancels uncommitted service without spending keeper food or feeding the guest") {
        residentRuntime { h ->
            try {
                val keeperId = h.standingTavernkeeper()
                val npc = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                h.owner.residentLifeEnabled = true
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == keeperId) CivsEquipment.collectSupplies(it, mapOf("BREAD" to 4L))
                    else it.copy(inventory = mapOf("IRON_AXE" to 1L))
                })
                h.tavernServiceSource = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(8, 64, 0))
                var proof: CivTavernDelivery? = null
                val pending = CompletableFuture<Settlement>()
                h.owner.deliverTavernMeal = { cityId, offered ->
                    h.owner.validateTavernDelivery(cityId, offered)
                    proof = offered
                    pending
                }
                h.navigationNow = 5_000
                h.owner.pulse(5_000)
                for (tick in 1..600) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (proof != null) break
                }
                val accepted = withClue(h.owner.debug(h.city.id, keeperId)) { requireNotNull(proof) }
                npc.isSeated shouldBe true
                h.usingMainHand shouldBe false
                if (interruption == "night") h.world.time = 13_000
                if (interruption == "chair-removed") h.world.getBlockAt(6, 64, 0).type = Material.AIR
                h.owner.pulse(requireNotNull(h.navigationNow) + if (interruption == "service-timeout") 46_000 else 1_000)
                npc.isSeated shouldBe false
                shouldThrow<CivsRejection> { h.owner.validateTavernDelivery(h.city.id, accepted) }.reason shouldBe "stale-meal"
                h.city.workers.first { it.id == h.workerId }.hunger shouldBe 14
                h.city.workers.sumOf { CivsEquipment.plainCount(it, "BREAD") } shouldBe 4
                pending.completeExceptionally(CivsRejection("stale-meal"))
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }
})

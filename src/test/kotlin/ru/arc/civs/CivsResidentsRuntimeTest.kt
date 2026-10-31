package ru.arc.civs

import com.destroystokyo.paper.entity.Pathfinder
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.ChunkMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsResidentsRuntimeTest : FunSpec({
    for (sourceType in listOf(BuildingType.TAVERN, BuildingType.TOWN_HALL))
    test("food trips show the actual destination and then pickup without replanning type=$sourceType") {
        residentRuntime { h ->
            h.assignFarm()
            h.city = h.city.copy(buildings = h.city.buildings.map {
                if (it.type == BuildingType.TOWN_HALL) it.copy(control = it.entrance) else it
            })
            h.owner.pulse(2_000)
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            val hall = h.city.buildings.first()
            val source = if (sourceType == BuildingType.TOWN_HALL) hall else hall.copy(id = UUID.randomUUID(),
                type = sourceType, schematicId = "tavern1", anchor = CivPosition(6, 64, 0), entrance = CivPosition(6, 64, 0),
                control = CivPosition(6, 64, 0), bounds = CivBox(CivPosition(4, 64, -2), CivPosition(8, 68, 2)))
            if (source != hall) h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + source))
            val control = requireNotNull(source.control)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            h.foodSource = source.id
            h.owner.pulse(3_000)
            val workPlans = h.workPlans
            val expected = "workers.status.${if (sourceType == BuildingType.TAVERN) "moving-to-tavern" else "moving-to-town-hall"}"
            h.owner.status(h.workerId) shouldBe expected
            h.owner.hologramSnapshots().single().status shouldBe expected
            h.workPlans shouldBe workPlans
            npc.teleport(Location(h.world, control.x + 1.5, control.y.toDouble(), control.z + .5))
            h.owner.pulse(4_000)
            h.supplyRequests shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.collecting-supplies"
            h.owner.hologramSnapshots().single().status shouldBe "workers.status.collecting-supplies"
        }
    }

    test("status and nameplate reads reuse observed plans and typed recipe inputs") {
        residentRuntime { h ->
            h.assignFarm()
            h.statusInputs = listOf("COBBLESTONE", "CHARCOAL")
            h.owner.pulse(2_000)
            val workPlans = h.workPlans
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-inputs"
            h.owner.statusInputs(h.workerId) shouldBe h.statusInputs
            val snapshot = h.owner.hologramSnapshots().single()
            snapshot.status shouldBe "workers.status.waiting-inputs"
            snapshot.statusInputs shouldBe h.statusInputs
            h.workPlans shouldBe workPlans
        }
    }

    test("an indoor workstation belongs to the accepted cycle and rotates only after committed production") {
        residentRuntime { h ->
            val smeltery = h.assignFarm().copy(type = BuildingType.SMELTERY, schematicId = "smeltery1", production = "STONE")
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == smeltery.id) smeltery else it })
            val first = CivWorkstation(CivPosition(23, 64, 0), CivPosition(24, 64, 0), "minecraft:furnace")
            val next = CivWorkstation(CivPosition(23, 64, 1), CivPosition(24, 64, 1), "minecraft:furnace")
            var offered = first
            var selections = 0
            h.workstationProvider = { _, _ -> selections++; offered }
            h.recreateResidents()
            val items = h.city.workers.single().items
            fun plan(): Map<*, *> = (((h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>)["plan"] as Map<*, *>)
            h.owner.pulse(2_000)
            plan()["workplace"] shouldBe first.standing
            plan()["focus"] shouldBe first.focus
            plan()["workstation"] shouldBe mapOf("standing" to first.standing, "focus" to first.focus, "material" to first.material)
            selections shouldBe 1
            offered = next
            h.owner.pulse(3_000)
            plan()["workplace"] shouldBe first.standing
            selections shouldBe 1
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(completedCycles = it.completedCycles + 1) })
            h.owner.pulse(4_000)
            plan()["workplace"] shouldBe next.standing
            selections shouldBe 2
            h.city.workers.single().items shouldBe items
            h.deliveries shouldBe 0
        }
    }

    fun prepareCarriedMealAtTavern(h: ResidentRuntime, guard: Boolean = false): PacketPlayerNpc {
        val npc = h.prepareDiningSeat(guard)
        val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
        val target = requireNotNull(citizenStorageTarget(h.city, tavern, h.world) {
            citizenStandable(h.world, it) && citizenPacketBodyClear(h.world, it.point(), false)
        })
        npc.teleport(Location(h.world, target.x + .5, target.y.toDouble(), target.z + .5))
        civsCanReachContainer(npc.location, requireNotNull(tavern.control)) shouldBe true
        return npc
    }

    test("a carried meal stays at its actual position through navigation ticks and spends one item after four observed seconds") {
        residentRuntime { h ->
            try {
                val npc = prepareCarriedMealAtTavern(h)
                val mealAt = npc.location
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    npc.isSeated shouldBe false
                    h.usingMainHand shouldBe true
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.pulse(3_000)
                withClue(h.owner.debug(h.city.id, h.workerId)) { h.usingMainHand shouldBe true }
                val seatedAt = npc.location
                seatedAt.y shouldBe 64.0
                val origin = requireNotNull(h.owner.residentFeet(h.workerId))
                origin shouldBe mealAt
                repeat(60) { h.navigate() }
                npc.isSeated shouldBe false
                h.usingMainHand shouldBe true
                npc.location shouldBe seatedAt
                h.owner.pulse(4_000); h.owner.pulse(5_000); h.owner.pulse(6_000)
                meals shouldBe 0
                CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "BREAD") shouldBe 2
                h.city.stock.food shouldBe 2L
                h.owner.pulse(7_000)
                meals shouldBe 1
                h.city.stock.food shouldBe 2L
                CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "BREAD") shouldBe 1
                h.owner.pulse(8_000)
                npc.isSeated shouldBe false
                h.usingMainHand shouldBe false
                h.displayedHand shouldBe Material.IRON_AXE
                npc.location shouldBe origin
                h.city.workers.first { it.id == h.workerId }.life!!.meals shouldBe 1L
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("adult and child dining anchors follow the rendered hip independently of the native mount offset") {
        residentRuntime { h ->
            try {
                h.prepareDiningSeat()
                val building = h.city.buildings.first { it.type == BuildingType.TAVERN }
                for (scale in listOf(.5, 1.0)) {
                    val seat = requireNotNull(citizenDiningSeat(h.world, building, CivPosition(6, 64, 0), scale) { true })
                    seat.feet.y shouldBe 64.5 - .7040625 * scale
                    seat.feet.y + .7040625 * scale shouldBe 64.5
                    citizenDiningSeatClear(h.world, seat) shouldBe true
                    citizenDiningTransitionClear(h.world, seat, Location(h.world, 1.5, 64.0, .5), scale) shouldBe false
                }
                h.world.getBlockAt(6, 65, 0).type = Material.STONE
                citizenDiningSeat(h.world, building, CivPosition(6, 64, 0), 1.0) { true } shouldBe null
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (interruption in listOf("damage", "night", "night-guard", "support-removed", "moved", "world-unloaded", "removed"))
    test("$interruption cancels a carried meal and its retained payment proof") {
        residentRuntime { h ->
            try {
                val npc = prepareCarriedMealAtTavern(h, guard = interruption == "night-guard")
                val pending = CompletableFuture<Settlement>()
                var proof: CivResidentMeal? = null
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    proof = plan
                    pending
                }
                for (now in 3_000L..7_000L step 1_000) h.owner.pulse(now)
                npc.isSeated shouldBe false
                h.usingMainHand shouldBe true
                h.displayedHand shouldBe Material.BREAD
                val accepted = requireNotNull(proof)
                when (interruption) {
                    "damage" -> h.owner.damage(h.workerId, 1.0)
                    "night", "night-guard" -> { h.world.time = 13_000; h.owner.pulse(8_000) }
                    "support-removed" -> { val at = npc.location; h.world.getBlockAt(at.blockX, at.blockY - 1, at.blockZ).type = Material.AIR; h.owner.pulse(8_000) }
                    "moved" -> { npc.teleport(Location(h.world, 8.5, 64.0, 4.5)); h.owner.pulse(8_000) }
                    "world-unloaded" -> h.owner.onWorldUnload(org.bukkit.event.world.WorldUnloadEvent(h.world))
                    else -> { h.city = h.city.copy(workers = h.city.workers.filterNot { it.id == h.workerId }); h.owner.pulse(8_000) }
                }
                npc.isSeated shouldBe false
                h.usingMainHand shouldBe false
                h.displayedHand shouldBe Material.IRON_AXE
                h.city.workers.firstOrNull { it.id == h.workerId }?.let { CivsEquipment.plainCount(it, "BREAD") shouldBe 2 }
                shouldThrow<CivsRejection> { h.owner.validateResidentMeal(h.city.id, accepted) }.reason shouldBe "stale-meal"
                h.city.stock.food shouldBe 2L
                h.city.workers.firstOrNull { it.id == h.workerId }?.hunger?.shouldBe(14)
                h.deliveries shouldBe 0
                pending.completeExceptionally(CivsRejection("stale-meal"))
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a newly enclosed meal position cancels eating without spending food or making a distant recovery jump") {
        residentRuntime { h ->
            try {
                val npc = prepareCarriedMealAtTavern(h)
                h.owner.pulse(3_000)
                h.usingMainHand shouldBe true
                val at = npc.location
                val teleports = h.teleports
                h.world.getBlockAt(at.blockX, at.blockY, at.blockZ).type = Material.STONE
                h.world.getBlockAt(at.blockX, at.blockY + 1, at.blockZ).type = Material.STONE
                h.owner.damage(h.workerId, 1.0)
                h.usingMainHand shouldBe false
                h.displayedHand shouldBe Material.IRON_AXE
                npc.location shouldBe at
                h.teleports shouldBe teleports
                h.city.stock.food shouldBe 2L
                CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "BREAD") shouldBe 2
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a partly observed job returns from a meal without resetting work or earning travel time") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            val work = h.beginCare("watering")
            h.owner.pulse(3_000)
            val partial = h.observedWork()
            (partial in 1 until work.durationMillis) shouldBe true
            h.city = h.city.copy(workers = h.city.workers.map {
                CivsEquipment.collectSupplies(it, mapOf("BREAD" to 1L)).copy(hunger = 14,
                    life = CivResidentLife(awakeMinutes = 6))
            })
            h.owner.residentLifeEnabled = true
            var meals = 0
            h.owner.finishResidentMeal = { id, plan ->
                h.owner.validateResidentMeal(id, plan)
                h.observedWork() shouldBe partial
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                meals++
                CompletableFuture.completedFuture(h.city)
            }
            h.navigationNow = 4_000
            h.owner.pulse(4_000)
            for (tick in 1..2_400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (h.observedWork() >= partial) shouldBe true
                if (h.deliveries == 1) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { meals shouldBe 1; h.deliveries shouldBe 1 }
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.city.workers.single().completedCycles shouldBe 0L
            CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 0L
        }
    }

    test("a lesson requires four continuous observed seconds after a child leaves and returns") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            val childId = UUID.randomUUID()
            val libraryId = UUID.randomUUID()
            val hall = h.city.buildings.first().copy(level = 3)
            val library = CivBuilding(libraryId, BuildingType.LIBRARY, 1, CivPosition(11, 64, 0), 0,
                "library1", CivBox(CivPosition(9, 63, -2), CivPosition(14, 68, 2)),
                CivPosition(11, 64, -3), BuildingPhase.ACTIVE, control = CivPosition(10, 64, 0))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(hall, library), workers = h.city.workers.map { it.copy(assignment = libraryId,
                    nextDeliveryAt = 1, homeId = hall.id, skills = mapOf("LIBRARY" to 10L)) } +
                    CivWorker(childId, "Child", null, homeId = hall.id, childMinutes = 6,
                        parentIds = setOf(h.workerId, UUID.randomUUID()), life = CivResidentLife(awakeMinutes = 6))))
            var lessons = 0
            h.owner.finishResidentLesson = { id, proof ->
                h.owner.validateResidentLesson(id, proof)
                h.city = CivsResidentLife.finishLesson(h.city, proof)
                lessons++
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            val teacher = actors.getValue(h.workerId)
            val child = actors.getValue(childId)
            teacher.teleport(Location(h.world, 12.5, 64.0, .5))
            child.teleport(Location(h.world, 13.5, 64.0, .5))
            h.owner.residentLifeEnabled = true
            for (now in 3_000L..5_000L step 1_000) h.owner.pulse(now)
            lessons shouldBe 0
            child.teleport(Location(h.world, 16.5, 64.0, .5))
            h.owner.pulse(6_000)
            child.teleport(Location(h.world, 13.5, 64.0, .5))
            for (now in 7_000L..10_000L step 1_000) h.owner.pulse(now)
            lessons shouldBe 0
            h.owner.pulse(11_000)
            withClue(h.owner.debug(h.city.id, childId)) { lessons shouldBe 1 }
            h.city.workers.first { it.id == childId }.life!!.education shouldBe mapOf("LIBRARY" to 1L)
            h.city.workers.first { it.id == childId }.skills shouldBe null
            h.deliveries shouldBe 0
        }
    }

    test("a discovered threat raises a city alarm civilians shelter and an assigned guard defends") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            h.owner.residentLifeEnabled = true
            for (x in -1..1) h.world.loadChunk(x, -2)
            val guardId = UUID.randomUUID()
            val towerId = UUID.randomUUID()
            val tower = CivBuilding(towerId, BuildingType.GUARD_TOWER, 1, CivPosition(10, 64, 0), 0,
                "guardtower1", CivBox(CivPosition(9, 63, -1), CivPosition(11, 68, 1)),
                CivPosition(10, 64, -2), BuildingPhase.ACTIVE, control = CivPosition(10, 64, 0))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = h.city.buildings + tower,
                workers = h.city.workers + CivWorker(guardId, "Guard", towerId, nextDeliveryAt = 1)))
            h.owner.pulse(2_000)
            h.owner.pulse(3_000)
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            val civilian = actors.getValue(h.workerId)
            val guard = actors.getValue(guardId)
            civilian.teleport(Location(h.world, 4.5, 64.0, .5))
            guard.teleport(Location(h.world, 4.5, 64.0, 1.5))
            val monster = h.monsterAt(6.5, .5)
            h.owner.pulse(4_000)
            h.owner.pulse(5_000)
            h.owner.status(h.workerId) shouldBe "workers.status.alarm-shelter"
            h.owner.status(guardId) shouldBe "workers.status.defending"
            verify(exactly = 0) { civilian.swingMainHand() }
            verify(atLeast = 1) { guard.swingMainHand() }
            verify(atLeast = 1) { monster.damage(1.0) }
            h.owner.awakeResidentIds(h.city.id) shouldBe setOf(guardId)
            h.deliveries shouldBe 0
            h.city.workers.all { it.completedCycles == 0L && it.skills.isNullOrEmpty() } shouldBe true
        }
    }

    for (automatic in listOf(false, true))
    test("awake idle resident eats one existing carried item only after a sustained session Auto $automatic") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            h.owner.residentLifeEnabled = true
            h.city = h.city.copy(inventory = if (automatic) null else CivInventory(CivStorageMode.VIRTUAL),
                autonomy = if (automatic) CivAutonomy() else null, stock = if (automatic) Stock(food = 3) else Stock(),
                workers = h.city.workers.map { it.copy(hunger = 14, life = CivResidentLife(awakeMinutes = 6),
                    inventory = mapOf("BREAD" to 2L)) })
            var meals = 0
            h.owner.finishResidentMeal = { cityId, plan ->
                h.owner.validateResidentMeal(cityId, plan)
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                meals++
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            h.owner.awakeResidentIds(h.city.id) shouldBe setOf(h.workerId)
            repeat(220) { tick ->
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { meals shouldBe 1 }
            h.city.workers.single().hunger shouldBe 20
            h.city.workers.single().life!!.meals shouldBe 1L
            if (automatic) h.city.stock.food shouldBe 3L
            CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
            h.city.workers.single().completedCycles shouldBe 0L
            h.deliveries shouldBe 0
        }
    }

    test("night cancels an uncommitted meal and cannot consume food through an old arrival proof") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            h.owner.residentLifeEnabled = true
            h.city = h.city.copy(autonomy = CivAutonomy(), stock = Stock(food = 2),
                workers = h.city.workers.map { it.copy(hunger = 14, inventory = mapOf("BREAD" to 2L),
                    life = CivResidentLife(awakeMinutes = 6)) })
            var captured: CivResidentMeal? = null
            val pending = CompletableFuture<Settlement>()
            h.owner.finishResidentMeal = { cityId, plan -> h.owner.validateResidentMeal(cityId, plan); captured = plan; pending }
            h.owner.pulse(2_000)
            for (tick in 1..300) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (captured != null) break
            }
            val plan = requireNotNull(captured)
            h.world.time = 13_000
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            h.owner.awakeResidentIds(h.city.id) shouldBe emptySet()
            shouldThrow<CivsRejection> { h.owner.validateResidentMeal(h.city.id, plan) }.reason shouldBe "stale-meal"
            h.city.stock.food shouldBe 2L
            h.city.workers.single().hunger shouldBe 14
            h.city.workers.single().completedCycles shouldBe 0L
            pending.completeExceptionally(CivsRejection("stale-meal"))
        }
    }

    test("tree growth probes the current scaled resident body without moving it") {
        residentRuntime { h ->
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 4.1, 64.0, 4.5))
            h.owner.growthBlocked(h.world.uid, CivPosition(3, 65, 4)) shouldBe true
            h.owner.growthBlocked(h.world.uid, CivPosition(4, 64, 4)) shouldBe true
            h.owner.growthBlocked(h.world.uid, CivPosition(4, 66, 4)) shouldBe false
            h.owner.growthBlocked(UUID.randomUUID(), CivPosition(4, 64, 4)) shouldBe false
            val before = npc.location
            h.owner.growthBlocked(h.world.uid, CivPosition(3, 65, 4))
            npc.location shouldBe before
            h.deliveries shouldBe 0
        }
    }

    for (obstruction in listOf(Material.OAK_LEAVES, Material.STONE))
    test("a new $obstruction overlap escapes without work or a recovery jump while injury persistence is pending") {
        residentRuntime { h ->
            val messages = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.navigationNow = 2_000
                val npc = h.owner.collisionActors().single()
                npc.teleport(Location(h.world, 4.5, 64.0, 4.5))
                val before = h.city
                h.world.getBlockAt(4, 65, 4).type = obstruction
                h.owner.pulse(2_000) // The production environmental check accepts the real suffocation injury.
                h.hits shouldBe 1
                h.pending.isDone shouldBe false
                h.phase() shouldBe CitizenPhase.CLOSED
                h.owner.canReassign(h.city.id, h.workerId) shouldBe false
                val start = npc.location
                h.owner.residentStepAllowed = { _, _, _ -> false }
                repeat(10) { h.navigate() }
                npc.location shouldBe start
                h.owner.residentStepAllowed = { _, _, _ -> true }
                repeat(32) {
                    val from = npc.location
                    h.navigate()
                    (from.distance(npc.location) <= .050001) shouldBe true
                }
                citizenPacketBodyClear(h.world, CitizenPoint(npc.location.x, npc.location.y, npc.location.z), false) shouldBe true
                h.pending.isDone shouldBe false
                h.city shouldBe before
                h.deliveries shouldBe 0
                h.world.getBlockAt(4, 65, 4).type shouldBe obstruction
                messages.count { it.level == java.util.logging.Level.WARNING &&
                    "event=citizen_terrain_escape" in it.message && "outcome=blocked" in it.message } shouldBe 1
                messages.count { "event=citizen_terrain_escape" in it.message && "outcome=cleared" in it.message } shouldBe 1
                h.commitHit()
                h.owner.pulse(4_000)
                h.hits shouldBe 1 // Committed health remains authoritative; the environment is now clear.
            } finally { h.logger.removeHandler(handler) }
        }
    }

    test("idle collection walks into actual reach before one bounded pickup request") {
        residentRuntime { h ->
            h.collectionEnabled = true
            h.navigationNow = 2_000
            val start = h.owner.residentFeet(h.workerId)!!
            val item = h.groundAt(start.clone().add(5.0, 0.0, 0.0))
            h.owner.pulse(2_000)
            h.groundRequests shouldBe emptyList()
            (h.routeIdentity() != null) shouldBe true // A real leisure route owns the approach.
            var largestStep = 0.0
            for (frame in 1..160) {
                val before = h.owner.residentFeet(h.workerId)!!
                h.navigate()
                val after = h.owner.residentFeet(h.workerId)!!
                largestStep = maxOf(largestStep, before.distance(after))
                if (frame % 5 == 0) h.owner.pulse(2_000L + frame * 50L)
                if (h.groundRequests.isNotEmpty()) break
            }
            h.groundRequests.single() shouldBe listOf(item.uniqueId)
            (h.owner.residentFeet(h.workerId)!!.distanceSquared(item.location) <= 4.0) shouldBe true
            (largestStep <= .3) shouldBe true
        }
    }

    test("near-feet collection batches at most eight and pending persistence prevents repeat pickup") {
        residentRuntime { h ->
            h.owner.canReassign(h.city.id, h.workerId) shouldBe true
            h.owner.canReassign(h.city.id, UUID.randomUUID()) shouldBe true
            h.owner.canReassign(UUID.randomUUID(), h.workerId) shouldBe false
            h.collectionEnabled = true
            val feet = h.owner.residentFeet(h.workerId)!!
            repeat(9) { h.groundAt(feet.clone().add(1.0, 0.0, 0.0)) }
            h.owner.pulse(2_000)
            h.groundRequests.single().size shouldBe 8
            h.owner.canReassign(h.city.id, h.workerId) shouldBe false
            h.owner.pulse(3_000)
            h.owner.pulse(8_000)
            h.groundRequests.size shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
        }
    }

    for (atHome in listOf(false, true)) test("night shelter only permits unloading when already beside real home container atHome=$atHome") {
        residentRuntime { h ->
            h.collectionEnabled = true
            val feet = h.owner.residentFeet(h.workerId)!!
            val container = CivPosition(feet.blockX + if (atHome) 1 else 10, feet.blockY, feet.blockZ)
            h.world.getBlockAt(container.x, container.y, container.z).type = Material.BARREL
            h.collectionDestination = CivCollectedHome(h.city.buildings.single().id, container, true)
            h.city = h.city.copy(workers = h.city.workers.map { worker -> worker.copy(hasCollectedItems = true,
                items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("COAL", 8) else null }) })
            h.world.time = 13_000
            h.owner.pulse(2_000)
            h.groundRequests shouldBe emptyList()
            h.homeDeposits shouldBe if (atHome) listOf(container) else emptyList()
        }
    }

    test("a built V4 prefix traverses two native landings and returns through the complete authored ladder without teleport jumps") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("resident-v4-two-level-traversal").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 80, 0))
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val secondFloor = geometry.floors[1]
            val prefix = geometry.steps.indexOfLast { it.floor == secondFloor.index && it.branch == "opening" } + 1
            (prefix in 1 until geometry.shaftEnd) shouldBe true
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            for (step in geometry.steps.take(prefix)) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
            }
            val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
            // Curated miner1 SHA c54e00bc1c0d4034: the complete authored column has
            // ladder rungs at local -5..-3, air at -2/-1, and a dirt cap at 0.
            for (localY in -5..0) {
                val point = bottom.copy(y = mine.anchor.y + localY)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(when {
                    localY <= -3 -> "minecraft:ladder[facing=west,waterlogged=false]"
                    localY < 0 -> "minecraft:air"
                    else -> "minecraft:dirt"
                })
                if (localY < 0) {
                    val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = point.y)
                    world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
                }
            }
            val state = CivMinePlanState(4, prefix.toLong())
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                minePlans = mapOf(mine.id.toString() to state))
            fun at(point: CivPosition) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5)
            fun passage(point: CitizenPoint) = citizenMineWorkRouteAllowed(mine, point, prefix.toLong(), state)
            fun feet(floor: CivsMinePlan.Floor) = CivsWorksiteLayouts.point(mine, floor.cells.first().copy(y = floor.feetY))
            val first = feet(geometry.floors[0])
            val second = feet(secondFloor)
            val top = bottom.copy(y = mine.anchor.y - 3)
            val future = feet(geometry.floors[2])
            passage(CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5)) shouldBe false
            var position = at(top)
            val start = position.clone()
            val observedDistances = mutableListOf<Double>()
            var walkedDistance = 0.0
            var minimumY = position.y
            var completedLegs = 0
            // Production capture/solver/movement runs against the faithful native-geometry
            // fixture. This driver never invokes the NPC lifecycle or recovery teleport.
            for (destination in listOf(first, second, first, top)) {
                var legs = 0
                while (position.distanceSquared(at(destination)) > .000001 && legs++ < 6) {
                    val waypoint = citizenMineWaypoint(city, world, position, destination) ?: destination
                    val target = at(waypoint)
                    val route = CitizenPacketRoute(world, position, target,
                        { p -> city.contains(p.x, p.z) && passage(CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5)) },
                        { _, to -> passage(to) }, { 1.0 })
                    val operationLimit = CitizenNavigationCapture.MAXIMUM_BLOCKS + CitizenNavigationSearch.MAXIMUM_NODES * 64
                    var operations = 0
                    while (route.searching && operations++ < operationLimit) route.expand()
                    withClue("from=$position waypoint=$waypoint destination=$destination prefix=$prefix " +
                        "nodes=${route.nodeCount} captured=${route.capturedBlocks} phase=${route.preparationPhase}") {
                        route.searching shouldBe false
                        route.failure shouldBe null
                    }
                    val frameLimit = 128 + 4 * kotlin.math.ceil(position.distance(target) / .172).toInt()
                    var frames = 0
                    while (!route.finished && route.failure == null && frames++ < frameLimit) {
                        val before = position
                        route.move(before, .8) { true }?.let { next ->
                            observedDistances += before.distance(next)
                            for (sample in 0..10) {
                                val point = before.clone().add(next.toVector().subtract(before.toVector()).multiply(sample / 10.0))
                                citizenPacketBodyClear(world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                            }
                            passage(CitizenPoint(next.x, next.y, next.z)) shouldBe true
                            position = next
                            minimumY = minOf(minimumY, position.y)
                        }
                    }
                    withClue("from=$position target=$target destination=$destination prefix=$prefix frames=$frames " +
                        "nodes=${route.nodeCount} remaining=${route.remainingNodes}") {
                        route.failure shouldBe null
                        route.finished shouldBe true
                        position.distanceSquared(target) shouldBe 0.0
                    }
                    walkedDistance += route.forwardProgress
                    completedLegs++
                }
                position.distanceSquared(at(destination)) shouldBe 0.0
            }
            val teleportJumps = observedDistances.count { it > .215001 }
            withClue("moves=${observedDistances.size} legs=$completedLegs walked=$walkedDistance " +
                "minimumY=$minimumY teleportJumps=$teleportJumps") {
                observedDistances.isNotEmpty() shouldBe true
                teleportJumps shouldBe 0
                (minimumY <= second.y) shouldBe true
                (walkedDistance >= 2 * (start.y - second.y)) shouldBe true
                position.distanceSquared(start) shouldBe 0.0
            }
        }
    }

    test("a permitted loaded city spawns and completes physical field work without viewers then retires on revoke") {
        residentRuntime { h ->
            Bukkit.getOnlinePlayers().forEach { it.teleport(Location(h.world, 1_000.5, 64.0, .5)) }
            h.owner.pulse(1_001)
            h.owner.activeCount() shouldBe 0
            h.background = true
            h.owner.pulse(1_500)
            h.owner.activeCount() shouldBe 1
            h.spawns shouldBe 2
            val work = h.beginCare("watering")
            for (at in 3_000L..6_000L step 1_000L) h.owner.pulseFieldWork(at)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.commitWork()
            h.owner.pulse(7_000)
            h.city.workers.single().completedCycles shouldBe 1
            h.background = false
            h.owner.pulse(8_000)
            h.owner.activeCount() shouldBe 0
            h.owner.pulseFieldWork(9_000)
            h.deliveries shouldBe 1
        }
    }

    test("an unassigned bootstrap resident finds ground below its depot without spawning on the control") {
        residentRuntime { h ->
            h.viewing = false
            h.owner.pulse(1_001)
            val control = CivPosition(0, 68, 0)
            h.city = h.city.copy(autonomy = CivAutonomy(), buildings = h.city.buildings.map {
                it.copy(phase = BuildingPhase.SITE, control = control)
            })
            h.world.getBlockAt(0, 67, 0).type = Material.STONE
            h.world.getBlockAt(0, 68, 0).type = Material.BARREL
            val chunks = h.world.loadedChunks.map { it.x to it.z }.toSet()
            h.viewing = true

            h.owner.pulse(2_000)

            h.owner.activeCount() shouldBe 1
            val feet = requireNotNull(h.owner.residentFeet(h.workerId))
            feet.y shouldBe 64.0
            citizenStandable(h.world, CivPosition(feet.blockX, feet.blockY, feet.blockZ)) shouldBe true
            citizenControlClear(h.city, CivPosition(feet.blockX, feet.blockY, feet.blockZ)) shouldBe true
            h.world.loadedChunks.map { it.x to it.z }.toSet() shouldBe chunks
        }
    }

    test("absent residents expose bounded spawn evidence and recover after exhausted retries when terrain changes") {
        residentRuntime { h ->
            h.viewing = false
            h.owner.pulse(1_001)
            h.city = h.city.copy(autonomy = CivAutonomy(), buildings = h.city.buildings.map {
                it.copy(phase = BuildingPhase.SITE, control = CivPosition(0, 64, 0))
            })
            for (x in -2..2) for (z in -2..2) for (y in 60..69) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.viewing = true
            h.owner.pulse(2_000)
            h.owner.pulse(7_000)
            h.owner.pulse(22_000)

            h.owner.activeCount() shouldBe 0
            val debug = h.owner.debug(h.city.id, h.workerId)
            debug["total"] shouldBe 0
            val admission = debug["admission"] as Map<*, *>
            admission["total"] shouldBe 1
            val row = (admission["entries"] as List<*>).single() as Map<*, *>
            row["reason"] shouldBe "no-safe-spawn"
            row["origin"] shouldBe CivPosition(0, 64, 0)
            row["retry_attempts"] shouldBe 3
            row["retry_exhausted"] shouldBe true
            row["next_retry_at_ms"] shouldBe 82_000L
            (row["origin_chunk"] as Map<*, *>)["load_level"] shouldBe org.bukkit.Chunk.LoadLevel.ENTITY_TICKING
            val search = row["spawn"] as Map<*, *>
            search["inspected"] shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
            (search["samples"] as List<*>).size shouldBe 16
            search["truncated"] shouldBe true

            for (x in -2..2) for (z in -2..2) for (y in 64..69) h.world.getBlockAt(x, y, z).type = Material.AIR
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.owner.pulse(81_999)
            h.owner.activeCount() shouldBe 0
            h.owner.pulse(82_000)
            h.owner.activeCount() shouldBe 1
            val recovered = h.owner.debug(h.city.id, h.workerId)["admission"] as Map<*, *>
            recovered["total"] shouldBe 0
        }
    }

    test("many absent residents share two bounded spawn attempts per pulse") {
        residentRuntime { h ->
            h.viewing = false
            h.owner.pulse(1_001)
            h.city = h.city.copy(autonomy = CivAutonomy(), workers = List(5) { ordinal ->
                h.city.workers.single().copy(id = if (ordinal == 0) h.workerId else UUID.randomUUID(), name = "Resident$ordinal")
            }, buildings = h.city.buildings.map { it.copy(phase = BuildingPhase.SITE, control = CivPosition(0, 64, 0)) })
            for (x in -2..2) for (z in -2..2) for (y in 60..69) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.viewing = true

            h.owner.pulse(2_000)

            val admission = h.owner.debug(h.city.id, null)["admission"] as Map<*, *>
            admission["total"] shouldBe 5
            val rows = (admission["entries"] as List<*>).map { it as Map<*, *> }
            rows.count { it["spawn"] != null } shouldBe 2
            rows.sumOf { ((it["spawn"] as? Map<*, *>)?.get("inspected") as? Int) ?: 0 } shouldBe
                2 * CITIZEN_SPAWN_CANDIDATE_LIMIT
            rows.count { it["reason"] == "spawn-budget" } shouldBe 3
        }
    }

    for ((farmerCount, ripensDuringCare) in listOf(1 to false, 2 to false, 1 to true))
        test("$farmerCount farmers harvest three nearby crops without rescanning between receipts (ripens during care: $ripensDuringCare)") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("resident-multi-harvest-$farmerCount").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ResidentMultiHarvest")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", farmerCount, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val workers = List(farmerCount) { spatialWorker(farm, "IRON_HOE") }
                var city = spatialCity(world.uid, listOf(farm), workers.first()).copy(workers = workers)
                val plots = CivsWorksiteLayouts.field(farm)
                // All three are outside the old second farmer's stripe. Consecutive targets
                // are about three blocks apart, but more than sixteen scan entries apart.
                val mature = listOf(20, 40, 64).map { plots[it].let { soil -> soil.copy(y = soil.y + 1) } }
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = 3 }
                }
                fun ripen() = mature.forEach { point -> world.getBlockAt(point.x, point.y, point.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge } }
                if (!ripensDuringCare) ripen()
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                farm.control!!.let { world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL }
                val viewer = paper.addPlayer("HarvestViewer")
                viewer.teleport(Location(world, 9.5, 65.0, -19.5))
                val packets = mockk<PaperPacketNpcs>(relaxed = true)
                val actors = mutableMapOf<UUID, PacketPlayerNpc>()
                every { packets.spawnPlayer(any(), any()) } answers {
                    var actorAt = firstArg<Location>().clone()
                    var actorValid = true
                    var actorBodyYaw = actorAt.yaw
                    var actorHeadYaw = actorAt.yaw
                    var actorPitch = actorAt.pitch
                    val profile = secondArg<PacketNpcProfile>()
                    mockk<PacketPlayerNpc>(relaxed = true) {
                        every { uniqueId } returns profile.uuid
                        every { isValid } answers { actorValid }
                        every { this@mockk.world } returns world
                        every { location } answers { actorAt.clone().also { it.yaw = actorBodyYaw; it.pitch = actorPitch } }
                        every { bodyYaw } answers { actorBodyYaw }
                        every { bodyYaw = any() } answers { actorBodyYaw = firstArg() }
                        every { headYaw } answers { actorHeadYaw }
                        every { headYaw = any() } answers { actorHeadYaw = firstArg() }
                        every { pitch } answers { actorPitch }
                        every { pitch = any() } answers { actorPitch = firstArg() }
                        every { height } returns 1.8
                        every { eyeHeight } returns 1.62
                        every { scale } returns 1.0
                        every { pose } returns PacketNpcPose.STANDING
                        every { teleport(any()) } answers {
                            actorAt = firstArg<Location>().clone()
                            actorBodyYaw = actorAt.yaw; actorPitch = actorAt.pitch
                            Unit
                        }
                        every { remove() } answers { actorValid = false; Unit }
                    }.also { actors[profile.uuid] = it }
                }
                every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
                var paused = false
                var now = 1_000L
                val harvested = mutableListOf<CivPosition>()
                val queued = ArrayDeque<Triple<UUID, Long, CompletableFuture<Settlement>>>()
                var pending: Triple<CivInventoryTransaction, CivWorldWork, CompletableFuture<Settlement>>? = null
                var sealedAt = 0L
                val workingSince = mutableMapOf<Pair<UUID, Long>, Long>()
                val awaitingNext = mutableMapOf<UUID, Pair<Long, Int>>()
                var ripe = !ripensDuringCare
                var preempted = false
                var busyWorkingChecked = false
                var busyWorkingUntil = 0L
                var busyWorkingPlan: CivWorldWork? = null
                var busyWorkingPosition: Vector? = null
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        // Measure scheduler pulses deterministically; MockK/JVM wall time is not a game tick.
                        // Advancing-clock worksite regressions separately exercise the shared 2 ms deadline.
                        val runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) },
                            storage::isWorldLocked, { paused }, nanoTime = { 0L })
                        lateinit var residents: CivsCitizens
                        residents = CivsCitizens(plugin, { listOf(city) }, { cityId, workerId, _, _, _, _, at ->
                            cityId shouldBe city.id
                            CompletableFuture<Settlement>().also { queued += Triple(workerId, at, it) }
                        }, { _, _, _ -> }, workPlan = runtime::plan, workIdleReason = runtime::idleReason, packetOwner = packets)
                        fun placeAtWork(worker: CivWorker) {
                            val work = requireNotNull(runtime.plan(city, worker))
                            actors.getValue(worker.id).teleport(work.standing.let { Location(world, it.x + .5, it.y.toDouble(), it.z + .5) })
                        }
                        try {
                            repeat(12) { runtime.pulse(listOf(city)) }
                            workers.forEach { requireNotNull(runtime.plan(city, it)).cropCare shouldBe ripensDuringCare }
                            residents.pulse(now)
                            workers.forEach(::placeAtWork)
                            var steps = 0
                            while (harvested.size < 3 && steps++ < 120) {
                                now += 250
                                if (busyWorkingUntil != 0L && now >= busyWorkingUntil) {
                                    runtime.plan(city, city.workers.single()) shouldBe busyWorkingPlan
                                    actors.getValue(workers.single().id).location.toVector() shouldBe busyWorkingPosition
                                    queued.isEmpty() shouldBe true
                                    city.workers.single().completedCycles shouldBe 0L
                                    paused = false
                                    busyWorkingUntil = 0
                                    busyWorkingChecked = true
                                }
                                if (!ripe && now == 4_250L) {
                                    residents.status(workers.first().id) shouldBe "workers.status.watering"
                                    ripen()
                                    ripe = true
                                }
                                pending?.takeIf { now - sealedAt >= 500 }?.let { (receipt, work, completion) ->
                                    work.changes.forEach { cell ->
                                        world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                                    }
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    city = receipt.after
                                    storage.unlock(receipt.id)
                                    runtime.invalidate(work.workerId)
                                    paused = false
                                    pending = null
                                    harvested += requireNotNull(work.focus)
                                    completion.complete(city)
                                    val claimed = city.workers.mapNotNull { runtime.plan(city, it)?.takeUnless(CivWorldWork::cropCare)?.focus }.toSet()
                                    if (mature.any { it !in harvested && it !in claimed }) awaitingNext[work.workerId] = now to 0
                                }
                                // The production scheduler admits one worksite every five ticks.
                                runtime.pulse(listOf(city))
                                if (ripensDuringCare && ripe && !preempted) {
                                    val work = requireNotNull(runtime.plan(city, city.workers.single()))
                                    if (!work.cropCare) {
                                        placeAtWork(city.workers.single())
                                        preempted = true
                                    } else (now - 4_250 < ((plots.size + 15) / 16) * 250L) shouldBe true
                                }
                                for ((workerId, wait) in awaitingNext.toMap()) {
                                    // Another farmer may reserve the final ripe crop between two
                                    // receipts. Only currently unclaimed crops require an immediate harvest.
                                    val unclaimed = mature.any { point -> point !in harvested && city.workers.none { other ->
                                        other.id != workerId && runtime.plan(city, other)?.focus == point
                                    } }
                                    if (!unclaimed) { awaitingNext.remove(workerId); continue }
                                    val (committedAt, previousPulses) = wait
                                    val admittedPulses = previousPulses + if (paused) 0 else 1
                                    val worker = city.workers.first { it.id == workerId }
                                    val work = runtime.plan(city, worker)
                                    if (work != null) {
                                        work.cropCare shouldBe false
                                        (admittedPulses <= farmerCount) shouldBe true
                                        if (farmerCount == 1) (now - committedAt <= 250L) shouldBe true
                                        placeAtWork(worker) // This fixture tests observed work/receipts, not native navigation.
                                        awaitingNext.remove(workerId)
                                    } else withClue("next known harvest must not require another complete field scan: now=$now paused=$paused worker=$workerId wait=$wait state=${runtime.debug(setOf(workerId))}") {
                                        (admittedPulses < farmerCount) shouldBe true
                                        awaitingNext[workerId] = committedAt to admittedPulses
                                    }
                                }
                                residents.pulseFieldWork(now)
                                if (now % 1_000 == 0L) residents.pulse(now)
                                if (farmerCount == 1 && !ripensDuringCare && !busyWorkingChecked && busyWorkingUntil == 0L &&
                                    residents.status(workers.single().id) == "workers.status.working-field") {
                                    busyWorkingPlan = requireNotNull(runtime.plan(city, city.workers.single()))
                                    busyWorkingPosition = actors.getValue(workers.single().id).location.toVector()
                                    busyWorkingUntil = now + 500
                                    paused = true
                                }
                                if (busyWorkingUntil != 0L) {
                                    runtime.plan(city, city.workers.single()) shouldBe busyWorkingPlan
                                    actors.getValue(workers.single().id).location.toVector() shouldBe busyWorkingPosition
                                    queued.isEmpty() shouldBe true
                                    residents.status(workers.single().id) shouldBe "workers.status.working-field"
                                }
                                for (worker in city.workers) {
                                    val work = runtime.plan(city, worker) ?: continue
                                    if (residents.status(worker.id) == "workers.status.working-field")
                                        workingSince.putIfAbsent(worker.id to worker.completedCycles, now)
                                    val unclaimed = mature.filter { ripe && it !in harvested }.filter { point ->
                                        city.workers.none { other -> other.id != worker.id && runtime.plan(city, other)?.focus == point }
                                    }
                                    if (unclaimed.isNotEmpty() && (!ripensDuringCare || preempted)) {
                                        work.cropCare shouldBe false
                                        (residents.status(worker.id) == "workers.status.searching-work") shouldBe false
                                    }
                                }
                                if (pending == null && queued.isNotEmpty()) {
                                    val (workerId, at, completion) = queued.removeFirst()
                                    val current = city.workers.first { it.id == workerId }
                                    val work = requireNotNull(runtime.plan(city, current))
                                    work.cropCare shouldBe false
                                    work.durationMillis shouldBe 1_500L
                                    work.outputs shouldBe mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 1L)
                                    work.inputs shouldBe emptyMap()
                                    (at - workingSince.getValue(workerId to current.completedCycles) >=
                                        CivsResidents.workDuration(current, work.durationMillis)) shouldBe true
                                    residents.requireWorldWorkArrival(city.id, workerId, work)
                                    runtime.validate(city, work)
                                    val id = UUID.randomUUID()
                                    storage.reserve(city, id)
                                    val plan = storage.prepareWork(city, farm.id, work.storageInputs(current), emptyMap(), work.changes)
                                    val after = CivsRules.finishWorldWork(city, work, at).copy(inventory = plan.inventoryAfter)
                                    val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                                        worldChanges = plan.worldChanges).validated()
                                    storage.seal(receipt, keepViewersOpen = true)
                                    paused = true
                                    sealedAt = now
                                    pending = Triple(receipt, work, completion)
                                    shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "city-busy"
                                }
                            }
                            harvested.toSet() shouldBe mature.toSet()
                            harvested.size shouldBe 3
                            city.workers.sumOf(CivWorker::completedCycles) shouldBe 3L
                            city.inventory!!.buildingStock(farm.id) shouldBe emptyMap()
                            city.workers.sumOf { CivsEquipment.plainCount(it, "WHEAT") } shouldBe 3L
                            city.workers.sumOf { CivsEquipment.plainCount(it, "WHEAT_SEEDS") } shouldBe 3L
                            mature.forEach { point -> (world.getBlockAt(point.x, point.y, point.z).blockData as Ageable).age shouldBe 0 }
                            plots.filter { it.copy(y = it.y + 1) !in mature }.forEach { soil ->
                                (world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData as Ageable).age shouldBe 3
                            }
                            if (farmerCount == 2) city.workers.all { it.completedCycles > 0 } shouldBe true
                            if (ripensDuringCare) preempted shouldBe true
                            if (farmerCount == 1 && !ripensDuringCare) busyWorkingChecked shouldBe true
                        } finally {
                            try { residents.closeAsync().join() } finally {
                                // Packet mock answers/call histories retain their world and actors.
                                // Release this fixture's mocks only, after all behavior assertions.
                                clearMocks(packets, *actors.values.toTypedArray())
                                tasks.close()
                            }
                        }
                    }
                }
            }
        }
    }

    test("a field route queued below the arrival deadline keeps its identity and full movement deadline after planning") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.navigationNow = 2_000L
                h.holdNavigation = true
                h.owner.pulse(2_000)
                val beforeSearch = actor.location
                val route = requireNotNull(h.routeIdentity())
                val teleports = h.teleports
                for (tick in 1..512) {
                    if (h.pendingNavigationWorkers > 0) break
                    h.navigate(advanceMillis = 0)
                }
                h.pendingNavigationWorkers shouldBe 1
                repeat(24) {
                    // An owned planner keeps its identity before the monotonic arrival timeout.
                    h.navigate(advanceMillis = 2_000)
                    h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                    (h.routeIdentity() === route) shouldBe true
                    actor.location.toVector() shouldBe beforeSearch.toVector()
                    actor.bodyYaw shouldBe beforeSearch.yaw
                    h.teleports shouldBe teleports
                    h.deliveries shouldBe 0
                }
                h.navigationNow shouldBe 50_000L
                h.pendingNavigationWorkers shouldBe 1
                h.releaseNavigation()
                var arrived = false
                for (tick in 1..512) {
                    val before = actor.location
                    h.navigate()
                    val after = actor.location
                    (before.distance(after) <= .172001) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(before.yaw, after.yaw)) <= 8.0001f) shouldBe true
                    if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                    if (h.deliveries == 1) {
                        citizenCanReachBlock(actor, focus) shouldBe true
                        h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
                        arrived = true
                        break
                    }
                }
                withClue("status=${h.owner.status(h.workerId)} location=${actor.location} focus=$focus " +
                    "reachable=${citizenCanReachBlock(actor, focus)} deliveries=${h.deliveries} " +
                    "planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") {
                    arrived shouldBe true
                }
                withClue("diagnostics=${messages.filter { "citizen_route_recovered" in it || "ARCCIVS_NAVIGATION_FAILED" in it }} " +
                    "planner=${h.navigationState()}") {
                    messages.none { "citizen_route_recovered" in it || "ARCCIVS_NAVIGATION_FAILED" in it } shouldBe true
                }
                h.spawns shouldBe 1
                h.steps.isNotEmpty() shouldBe true
                h.deliveries shouldBe 1
                h.pendingDelivery.isDone shouldBe false
                h.city.workers.single().completedCycles shouldBe 0L
                h.commitWork()
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                h.city.workers.single().completedCycles shouldBe 1L
                h.deliveries shouldBe 1
            } finally { h.logger.removeHandler(handler) }
        }
    }

    for (denial in listOf("cancelled event", "receipt lock"))
    test("a $denial keeps a closed gate authoritative across retries and recovery until a real walk is permitted") {
        residentRuntime { h ->
            val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id == warehouse.id) warehouse else it
            }))
            for (x in -1..23) for (z in -1..1) for (y in 64..66)
                if (x == -1 || x == 23 || z != 0 || y == 66) h.world.getBlockAt(x, y, z).type = Material.STONE
            // The selected east side of the real barrel needs room to walk around it.
            // This room is beyond the gate; the origin corridor still has no other exit.
            for (x in 18..22) for (z in listOf(-1, 1)) for (y in 64..65)
                h.world.getBlockAt(x, y, z).type = Material.AIR
            val gate = h.world.getBlockAt(10, 64, 0)
            gate.blockData = Bukkit.createBlockData("minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]")
            var denied = true
            h.passageLocked = denial == "receipt lock"
            Bukkit.getPluginManager().registerEvents(object : Listener {
                @EventHandler
                fun passage(event: CivsResidentOpenDoorEvent) {
                    if (denial == "cancelled event" && denied) event.isCancelled = true
                }
            }, requireNotNull(Bukkit.getPluginManager().getPlugin("ResidentsTest")))
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 64.0, .5, -90f, 0f))
            h.navigationNow = 2_000L
            h.owner.pulse(2_000L)
            fun awaitDenial() {
                for (tick in 1..512) {
                    if (h.routeFailure() != null) break
                    val before = actor.location
                    h.navigate(advanceMillis = 0)
                    (before.distance(actor.location) <= .172001) shouldBe true
                    (actor.location.x < 10.0) shouldBe true
                }
                withClue(h.navigationState()) { h.routeFailure() shouldBe "passage-blocked" }
                (gate.blockData as Gate).isOpen shouldBe false
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
                h.city.workers.single().completedCycles shouldBe 0L
            }
            awaitDenial()
            repeat(3) {
                val now = requireNotNull(h.navigationNow)
                val failed = requireNotNull(h.routeIdentity())
                val feet = actor.location
                val teleports = h.teleports
                h.owner.pulse(now)
                for (delay in listOf(1_000L, 4_999L)) {
                    h.navigationNow = now + delay
                    h.navigate(advanceMillis = 0)
                    h.owner.pulse(now + delay)
                    (h.routeIdentity() === failed) shouldBe true
                    actor.location.toVector() shouldBe feet.toVector()
                    h.teleports shouldBe teleports
                }
                h.navigationNow = now + 5_000L
                h.owner.pulse(now + 5_000L)
                h.routeIdentity() shouldBe null
                actor.location.toVector() shouldBe feet.toVector()
                h.teleports shouldBe teleports
                h.navigationNow = now + 5_001L
                h.owner.pulse(now + 5_001L)
                awaitDenial()
            }
            denied = false
            h.passageLocked = false
            h.owner.pulse(requireNotNull(h.navigationNow))
            h.navigationNow = requireNotNull(h.navigationNow) + 5_000L
            h.owner.pulse(requireNotNull(h.navigationNow))
            h.navigationNow = requireNotNull(h.navigationNow) + 1L
            h.owner.pulse(requireNotNull(h.navigationNow))
            for (tick in 1..512) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .172001) shouldBe true
                if (h.owner.status(h.workerId) == "workers.status.waiting-orders") break
            }
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            (actor.location.x > 10.5) shouldBe true
            (gate.blockData as Gate).isOpen shouldBe true
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    for (journey in listOf("shelter", "empty courier return"))
    test("a transient planner rejection backs off then resumes a real $journey walk without teleporting") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val sheltering = journey == "shelter"
            val destination = if (sheltering) {
                h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                    schematicId = "house1", anchor = CivPosition(12, 64, 0), entrance = CivPosition(12, 64, 0),
                    bounds = CivBox(CivPosition(10, 64, -2), CivPosition(14, 68, 2)), control = null).also { home ->
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
                        workers = h.city.workers.map { it.copy(homeId = home.id) }))
                    h.world.time = 13_000
                }
            } else {
                h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null).also { warehouse ->
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                        if (it.id == warehouse.id) warehouse else it
                    }))
                }
            }
            h.city.workers.single().cargo shouldBe null
            val travellingStatus = if (sheltering) "workers.status.sheltering" else "workers.status.moving-to-storage"
            val arrivedStatus = if (sheltering) "workers.status.sleeping" else "workers.status.waiting-orders"
            val maximumStep = if (sheltering) .118251 else .172001
            h.navigationNow = 2_000L
            h.navigationRejections = 1
            h.owner.pulse(2_000L)
            h.owner.status(h.workerId) shouldBe travellingStatus
            val failedRoute = requireNotNull(h.routeIdentity())
            val origin = requireNotNull(h.owner.residentFeet(h.workerId))
            val teleports = h.teleports
            for (tick in 1..64) {
                if (h.routeFailure() != null) break
                h.navigate(advanceMillis = 0)
            }
            h.navigationRejections shouldBe 0
            h.routeFailure() shouldBe "planner-busy"
            h.owner.pulse(2_000L) // The first observed preparation failure starts its five-second backoff.
            for (now in listOf(2_500L, 4_000L, 6_999L)) {
                h.navigationNow = now
                h.navigate(advanceMillis = 0)
                h.owner.pulse(now)
                (h.routeIdentity() === failedRoute) shouldBe true
                h.routeFailure() shouldBe "planner-busy"
                h.owner.residentFeet(h.workerId) shouldBe origin
                h.teleports shouldBe teleports
                h.deliveries shouldBe 0
            }
            h.navigationNow = 7_000L
            h.navigate(advanceMillis = 0)
            h.owner.pulse(7_000L)
            h.routeIdentity() shouldBe null
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.teleports shouldBe teleports

            h.navigationNow = 7_001L
            h.owner.pulse(7_001L)
            val retry = requireNotNull(h.routeIdentity())
            (retry === failedRoute) shouldBe false
            h.teleports shouldBe teleports
            var arrived = false
            var walked = 0.0
            for (tick in 1..512) {
                val before = requireNotNull(h.owner.residentFeet(h.workerId))
                val yaw = actor.bodyYaw
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val after = requireNotNull(h.owner.residentFeet(h.workerId))
                val distance = before.distance(after)
                (distance <= maximumStep) shouldBe true
                if (actor.pose != PacketNpcPose.SLEEPING)
                    (kotlin.math.abs(citizenAngleChange(yaw, actor.bodyYaw)) <= 8.0001f) shouldBe true
                walked += distance
                if (h.owner.status(h.workerId) == arrivedStatus) {
                    arrived = true
                    break
                }
            }
            withClue("journey=$journey status=${h.owner.status(h.workerId)} location=${actor.location} planner=${h.navigationState()}") {
                arrived shouldBe true
            }
            (walked > 1.0) shouldBe true
            val feet = requireNotNull(h.owner.residentFeet(h.workerId))
            if (sheltering) {
                actor.pose shouldBe PacketNpcPose.SLEEPING
                destination.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            } else {
                val control = requireNotNull(destination.control)
                citizenAtStorage(h.world, CitizenPoint(feet.x, feet.y, feet.z), control) shouldBe true
                citizenCanReachBlock(actor, control) shouldBe true
            }
            h.spawns shouldBe 1
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
            h.city.workers.single().cargo shouldBe null
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("closing residents waits for accepted work on its owned navigation executor") {
        residentRuntime(useOwnedNavigationExecutor = true) { h ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            try {
                h.ownedNavigationExecutor().execute {
                    entered.countDown()
                    try { release.await() } finally { exited.countDown() }
                }
                entered.await(2, TimeUnit.SECONDS) shouldBe true
                val retired = h.owner.closeAsync()
                h.owner.activeCount() shouldBe 0
                retired.isDone shouldBe false
                release.countDown()
                retired.get(2, TimeUnit.SECONDS)
                exited.await(2, TimeUnit.SECONDS) shouldBe true
                retired.isDone shouldBe true
            } finally { release.countDown() }
        }
    }

    test("monster defence recruits only nearby residents of its victim's city and limits visible melee to once a second") {
        residentRuntime { h ->
            val victim = h.owner.collisionActors().single()
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            val monster = h.monsterAt(6.5, .5)
            // Discovery occurs while only the victim exists. Later helpers must be recruited
            // by the actual pursuit callback, not become independent targets of the scan.
            h.owner.pulse(2_000)
            h.world.threatEntities.clear()
            val helperId = UUID.randomUUID()
            val distantId = UUID.randomUUID()
            val outsiderId = UUID.randomUUID()
            h.city = h.city.copy(workers = h.city.workers + listOf(
                CivWorker(helperId, "Helper", null), CivWorker(distantId, "Distant", null)))
            h.otherCities += h.city.copy(id = UUID.randomUUID(), workers = listOf(CivWorker(outsiderId, "Outsider", null)))
            h.owner.pulse(3_000)
            h.owner.pulse(4_000)
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.size shouldBe 4
            val helper = actors.getValue(helperId)
            val distant = actors.getValue(distantId)
            val outsider = actors.getValue(outsiderId)
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            helper.teleport(Location(h.world, 4.5, 64.0, 1.5))
            distant.teleport(Location(h.world, 18.5, 64.0, .5))
            outsider.teleport(Location(h.world, 4.5, 64.0, -1.5))
            val residents = h.city.workers
            h.navigate()
            h.owner.pulse(5_000)
            h.owner.status(h.workerId) shouldBe "workers.status.defending"
            h.owner.status(helperId) shouldBe "workers.status.defending"
            h.owner.status(distantId) shouldBe "workers.status.unassigned"
            h.owner.status(outsiderId) shouldBe "workers.status.unassigned"
            verify(exactly = 1) { victim.swingMainHand() }
            verify(exactly = 1) { helper.swingMainHand() }
            verify(exactly = 0) { distant.swingMainHand() }
            verify(exactly = 0) { outsider.swingMainHand() }
            verify(exactly = 2) { monster.damage(1.0) }
            for (at in 5_100L..5_900L step 100) h.owner.pulse(at)
            verify(exactly = 2) { monster.damage(any<Double>()) }
            h.owner.pulse(6_000)
            verify(exactly = 4) { monster.damage(1.0) }

            // A newly placed native wall blocks both residents' next strikes even though
            // the pursuit is already active and the monster stays within melee distance.
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.STONE
            h.owner.pulse(7_000)
            verify(exactly = 4) { monster.damage(any<Double>()) }
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.AIR
            h.owner.pulse(8_000)
            verify(exactly = 6) { monster.damage(1.0) }
            h.city.workers shouldBe residents
            h.deliveries shouldBe 0
            h.hits shouldBe 0
        }
    }

    test("effective immortal residents finish observed work without mob aggro or an injury receipt") {
        residentRuntime { h ->
            h.testingAllowed = true
            h.city = h.city.copy(settings = CivSettlementSettings(immortalResidents = true),
                workers = h.city.workers.map { it.copy(health = 1.0) })
            val work = h.beginCare("watering")
            val actor = h.owner.collisionActors().single()
            val at = actor.location
            val monster = h.monsterAt(24.5, .5)
            val monsterPathfinder = mockk<Pathfinder>(relaxed = true)
            every { monster.pathfinder } returns monsterPathfinder
            for (now in 3_000L..6_000L step 1_000L) {
                h.owner.pulse(now)
                h.navigate()
                actor.location.toVector() shouldBe at.toVector()
            }
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.hits shouldBe 0
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.combatState()["attacker"] shouldBe null
            verify(exactly = 0) { monsterPathfinder.moveTo(any<Location>(), any<Double>()) }
            verify(exactly = 0) { monster.damage(any<Double>()) }
        }
    }

    for (revocation in listOf("setting", "permission"))
    test("enabling effective immortality clears stale combat in place and $revocation revocation restores it") {
        residentRuntime { h ->
            h.city = h.city.copy(settings = CivSettlementSettings(immortalResidents = true),
                workers = h.city.workers.map { it.copy(health = 1.0) })
            h.beginCare("watering")
            h.phase() shouldBe CitizenPhase.WORKING
            val actor = h.owner.collisionActors().single()
            val monster = h.monsterAt(24.5, .5)
            val monsterPathfinder = mockk<Pathfinder>(relaxed = true)
            every { monster.pathfinder } returns monsterPathfinder
            h.navigationNow = 2_000
            h.owner.pulse(3_000)
            h.navigate()
            h.owner.pulse(4_000)
            // The persisted flag alone cannot suppress ordinary threats without the existing permission.
            h.owner.status(h.workerId) shouldBe "workers.status.retreating"
            h.combatState()["attacker"] shouldBe monster.uniqueId
            val combatRoute = requireNotNull(h.routeIdentity())
            val at = actor.location

            h.testingAllowed = true
            h.owner.pulse(5_000)
            h.combatState()["attacker"] shouldBe null
            h.combatState()["target"] shouldBe null
            h.combatState()["status"] shouldBe null
            (h.routeIdentity() !== combatRoute) shouldBe true
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.status(h.workerId) shouldBe "workers.status.watering"
            actor.location.toVector() shouldBe at.toVector()
            repeat(20) { h.navigate() }
            actor.location.toVector() shouldBe at.toVector()
            h.hits shouldBe 0
            verify(exactly = 1) { monsterPathfinder.stopPathfinding() }

            if (revocation == "setting") h.city = h.city.copy(settings = CivSettlementSettings())
            else h.testingAllowed = false
            h.owner.pulse(6_000)
            h.navigate()
            h.owner.pulse(7_000)
            h.owner.status(h.workerId) shouldBe "workers.status.retreating"
            h.combatState()["attacker"] shouldBe monster.uniqueId
            h.deliveries shouldBe 0
            h.hits shouldBe 0
        }
    }

    test("wounded adults and children walk away from a pursuing monster without attacking or earning work credit") {
        for (child in listOf(false, true)) residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { if (child)
                it.copy(childMinutes = 1, parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()))
                else it.copy(health = 7.0) })
            val victim = h.owner.collisionActors().single()
            val start = Location(h.world, 10.5, 64.0, .5)
            victim.teleport(start)
            val monster = h.monsterAt(12.5, .5)
            h.owner.pulse(2_000)
            h.navigate()
            h.owner.pulse(3_000)
            h.owner.status(h.workerId) shouldBe "workers.status.retreating"
            // A child may have made its harmless idle gesture before the threat pulse spotted the monster.
            clearMocks(victim, answers = false, recordedCalls = true)
            for (tick in 0 until 2_000) {
                val before = victim.location
                h.navigate()
                (before.distance(victim.location) <= .236501) shouldBe true
                if (victim.location.distanceSquared(start) >= 4.0 * 4.0) break
            }
            (victim.location.x < start.x - 3.0) shouldBe true
            (victim.location.distanceSquared(monster.location) > start.distanceSquared(monster.location)) shouldBe true
            verify(exactly = 0) { victim.swingMainHand() }
            verify(exactly = 0) { monster.damage(any<Double>()) }
            h.city.workers.single().completedCycles shouldBe 0
            h.city.workers.single().skills.orEmpty() shouldBe emptyMap()
            h.deliveries shouldBe 0
            h.hits shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("a processor collects supplies only at its barrel and cannot start work before the receipt") {
        residentRuntime { h ->
            val mill = h.assignFarm().copy(type = BuildingType.BLACKSMITH, production = null)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == mill.id) mill else it })
            h.workReady = false
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.owner.pulse(3_000)
            h.supplyRequests shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id) }
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.workReady = true
            h.pendingSupplies.complete(h.city)
            h.owner.pulse(5_000)
            h.owner.pulse(6_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    test("builder exterior geometry clears a larger future terrace without widening native probes") {
        residentRuntime { h ->
            val target = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                entrance = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0), control = null,
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val changed = (24..36).flatMap { x -> (-6..6).map { z -> civsTerrainKey(x, z) } }.toSet()
            val site = CivConstructionSite(UUID.randomUUID(), target, changed)
            site.exteriorColumns.size shouldBe 16
            site.exteriorColumns.none { civsTerrainKey(it.x, it.z) in changed } shouldBe true
            for (x in 1..2) for (z in -1..0) h.world.loadChunk(x, z)
            val stance = requireNotNull(citizenConstructionTarget(h.city, h.world, site))
            (kotlin.math.abs(stance.x - target.entrance.x) + kotlin.math.abs(stance.z - target.entrance.z)) shouldBe 7
            citizenConstructionStanding(h.city, h.world, site, stance) shouldBe true
        }
    }

    for (autonomous in listOf(false, true))
    test("builder walks once to a safe exterior stance across terrain rows and recovery autonomous=$autonomous") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 4, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(4, 16), workCredit = 1,
                preparationWorkplace = CivPosition(22, 64, 0))
            h.constructionColumns = setOf(civsTerrainKey(27, 0), civsTerrainKey(33, 0))
            for (x in -1..2) for (z in -2..1) h.world.loadChunk(x, z)
            h.city = h.city.copy(autonomy = if (autonomous) CivAutonomy() else null,
                inventory = if (autonomous) null else h.city.inventory,
                buildings = listOf(h.city.buildings.first(), hut, target), construction = order)
            val stance = h.constructionTarget()
            stance shouldBe CivPosition(30, 64, -3)
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val row = (h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>
            val plan = row["plan"] as Map<*, *>
            plan["workplace"] shouldBe stance
            h.owner.constructionReady(h.city.id, order.id) shouldBe false
            (shouldThrow<CivsRejection> { h.owner.requireConstructionArrival(h.city.id, h.workerId, order.id) }).reason shouldBe
                "route-unreachable"
            val actor = h.owner.collisionActors().single()
            for (tick in 1..1_200) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                if (h.owner.constructionReady(h.city.id, order.id)) break
            }
            h.owner.constructionReady(h.city.id, order.id) shouldBe true
            (h.steps.size > 10) shouldBe true
            val at = actor.location.toVector()
            val moves = h.teleports
            for ((batch, focus) in listOf(CivPosition(23, 65, 0), CivPosition(26, 64, 0), CivPosition(30, 67, 1)).withIndex()) {
                h.city = h.city.copy(construction = order.copy(workDone = batch + 1, preparationWorkplace = focus))
                h.owner.constructionReady(h.city.id, order.id) shouldBe true
                h.owner.constructionPlaced(h.city.id, order.id, focus, Material.STONE)
                h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + (batch + 1) * 250)
                actor.location.toVector() shouldBe at
                h.owner.constructionReady(h.city.id, order.id) shouldBe true
            }
            h.teleports shouldBe moves
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            (h.gestures[h.workerId]!! >= 3) shouldBe true
            // Retiring and reconstructing the resident keeps the same deterministic site, not the latest terrain front.
            h.viewing = false; h.owner.pulse(requireNotNull(h.navigationNow) + 2_000)
            h.viewing = true; h.owner.pulse(requireNotNull(h.navigationNow) + 3_000)
            val recovered = (h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>
            (recovered["plan"] as Map<*, *>)["workplace"] shouldBe stance
            h.owner.constructionReady(h.city.id, order.id) shouldBe false
            h.world.getBlockAt(stance.x, stance.y - 1, stance.z).type = Material.AIR
            citizenConstructionStanding(h.city, h.world, requireNotNull(h.constructionSite()), stance) shouldBe false
        }
    }

    for (fullBeforeWork in listOf(false, true))
    test("full autonomous storage permits leisure then resumes at real delivery points fullBeforeWork=$fullBeforeWork") {
        residentRuntime { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val producer = h.assignQuarry().copy(production = null,
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, -1)), control = null)
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            val hall = h.city.buildings.first().copy(
                bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -1)))
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(hall, producer)))
            val token = requireNotNull(citizenPlan(h.city, h.city.workers.single())).token
            val full = Stock(stone = CivsRules.capacity(h.city))
            if (fullBeforeWork) h.city = h.city.copy(stock = full)
            h.onDelivery = {
                it shouldBe token
                h.owner.requireAutonomousDelivery(h.city.id, h.workerId, it)
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.owner.collisionActors().single()
            for (tick in 1..2_400) {
                if (h.phase() == CitizenPhase.PAUSED) break
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                // Fill storage only after observed work; the completed trip must survive its pause.
                if (!fullBeforeWork && h.phase() == CitizenPhase.RETURNING) h.city = h.city.copy(stock = full)
            }
            h.phase() shouldBe CitizenPhase.PAUSED
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            h.deliveries shouldBe 0
            val pausedAt = actor.location
            val pausedCity = h.city
            val roadSteps = h.steps.size
            for (tick in 1..800) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .118251) shouldBe true
                if (actor.location.distance(pausedAt) >= 2.0 && h.routeIdentity() != null) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") {
                (actor.location.distance(pausedAt) >= 2.0) shouldBe true
                h.phase() shouldBe CitizenPhase.PAUSED
            }
            h.city shouldBe pausedCity
            h.steps.size shouldBe roadSteps
            h.deliveries shouldBe 0
            shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            val leisureRoute = requireNotNull(h.routeIdentity())

            h.city = h.city.copy(stock = Stock())
            h.owner.pulse(requireNotNull(h.navigationNow))
            h.phase() shouldBe if (fullBeforeWork) CitizenPhase.TO_STORAGE else CitizenPhase.RETURNING
            (h.routeIdentity() === leisureRoute) shouldBe false
            h.deliveries shouldBe 0
            for (tick in 1..2_400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (!fullBeforeWork) (h.phase() == CitizenPhase.WORKING) shouldBe false
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token)
            h.city.stock shouldBe Stock()
            h.city.workers.single().completedCycles shouldBe 0L
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            h.city = CivsRules.deliver(h.city, h.city.owner, h.workerId, producer.id,
                token.completedCycles, token.nextDeliveryAt, requireNotNull(h.navigationNow))
            h.pendingDelivery.complete(h.city)
            h.owner.pulse(requireNotNull(h.navigationNow) + 4_000)
            h.city.workers.single().completedCycles shouldBe 1L
            h.city.stock.stone shouldBe CivsRules.deliveryAmount(producer)
            h.deliveries shouldBe 1
        }
    }

    test("a full producer bag visits its workplace then resumes the captured work without travel credit") {
        residentRuntime(workerId = UUID(0, 19)) { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 25.5, 64.0, .5))
            val focus = CivPosition(26, 63, 0)
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(27, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                outputs = mapOf("WHEAT" to 1L), durationMillis = 1_500, status = "working-field", focus = focus)
            h.work = work
            h.onDelivery = { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            val fullBag = List(CivsEquipment.SLOTS) { slot ->
                if (slot >= CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 64) else null
            }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = fullBag) })
            val returned = CompletableFuture<Settlement>()
            var requests = 0
            h.owner.returnResidentOutputs = { cityId, workerId, buildingId ->
                buildingId shouldBe farm.id
                h.owner.requireStorageArrival(cityId, workerId, buildingId)
                requests++
                returned
            }
            h.owner.pulse(3_500)
            h.owner.status(h.workerId) shouldBe "workers.status.returning-output"
            h.city.workers.single().items shouldBe fullBag
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(4_000)
            requests shouldBe 1
            h.owner.pulse(5_000)
            requests shouldBe 1
            h.deliveries shouldBe 0
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) }, revision = h.city.revision + 1)
            returned.complete(h.city)
            h.owner.pulse(6_000)
            (h.routeIdentity() != null) shouldBe true
            citizenCanReachBlock(actor, focus) shouldBe false
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 27.5, 64.0, .5))
            h.owner.pulse(7_000)
            h.deliveries shouldBe 0
            h.owner.pulse(8_500)
            h.deliveries shouldBe 1
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("a full producer output barrel reports storage full and bounds retries without dropping its bag") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            val farm = h.assignFarm()
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 25.5, 64.0, .5))
            val focus = CivPosition(26, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(27, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                outputs = mapOf("WHEAT" to 1L), durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            val bag = List(CivsEquipment.SLOTS) { slot -> if (slot >= CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 64) else null }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag) })
            var attempts = 0
            h.owner.returnResidentOutputs = { _, _, _ ->
                attempts++
                CompletableFuture.failedFuture(CivsRejection("storage-full"))
            }
            h.owner.pulse(3_500)
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(4_000)
            attempts shouldBe 1
            h.owner.pulse(4_050)
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            h.owner.pulse(7_000)
            attempts shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            h.city.workers.single().items shouldBe bag
            h.deliveries shouldBe 0
            val localWalk = requireNotNull(h.routeIdentity())
            h.owner.pulse(9_100)
            // Retry expiry must not cancel the admitted local walk and send the worker back to a full barrel.
            attempts shouldBe 1
            (h.routeIdentity() === localWalk) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            h.city.workers.single().items shouldBe bag
        }
    }

    for (ore in listOf(false, true)) test("full stone stock blocks ordinary stone but permits an accepted ore walk ore=$ore") {
        residentRuntime { h ->
            val producer = h.assignQuarry().copy(production = null,
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, -1)),
                control = CivPosition(22, 67, -2))
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            val hall = h.city.buildings.first().copy(phase = BuildingPhase.SITE,
                bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -1)), control = CivPosition(0, 67, -2))
            for (building in listOf(hall, producer)) building.control!!.let {
                h.world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL
            }
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(stone = CivsRules.capacity(h.city)), buildings = listOf(hall, producer),
                research = if (ore) CivResearchState(setOf(CivResearchId.MATERIALS)) else null))
            val before = h.city
            var observed: CitizenPlan? = null
            h.onDelivery = { observed = h.owner.requireAutonomousDelivery(h.city.id, h.workerId, it) }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            if (!ore) {
                h.phase() shouldBe CitizenPhase.PAUSED
                h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
                repeat(40) { h.navigate() }
                h.deliveries shouldBe 0
                h.city shouldBe before
            } else {
                val actor = h.owner.collisionActors().single()
                var walked = 0.0
                for (tick in 1..2_400) {
                    val previous = actor.location
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    val step = previous.distance(actor.location)
                    (step <= .215001) shouldBe true
                    walked += step
                    if (h.deliveries == 1) break
                }
                withClue("at=${actor.location} walked=$walked ${h.navigationState()}") {
                    h.deliveries shouldBe 1
                    (walked > 35) shouldBe true
                }
                val work = requireNotNull(observed?.industryWork)
                work.recipe.id shouldBe "RAW_IRON"
                h.city shouldBe before
                val worker = h.city.workers.single()
                h.city = CivsIndustry.complete(h.city, worker.id, worker.completedCycles, worker.nextDeliveryAt,
                    work, requireNotNull(h.navigationNow))
                h.pendingDelivery.complete(h.city)
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                h.city.stock shouldBe before.stock
                h.city.autonomy!!.products shouldBe mapOf(CivIndustryProduct.RAW_IRON to 1L)
                h.city.workers.single().completedCycles shouldBe 1L
                h.city.inventory shouldBe null
                h.city.workers.single().items shouldBe null
                h.deliveries shouldBe 1
            }
        }
    }

    test("autonomous quarry walks a complete production trip beside a floating hall SITE barrel") {
        residentRuntime { h ->
            val producer = h.assignQuarry().copy(production = null,
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, -1)),
                control = CivPosition(22, 67, -2))
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            val hall = h.city.buildings.first().copy(phase = BuildingPhase.SITE,
                bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -1)),
                control = CivPosition(0, 67, -2))
            for (building in listOf(hall, producer)) building.control!!.let {
                h.world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL
            }
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(hall, producer)))
            val token = requireNotNull(citizenPlan(h.city, h.city.workers.single())).token
            h.onDelivery = { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, it) }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.owner.collisionActors().single()
            shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            var walked = 0.0
            var furthestX = actor.location.x
            for (tick in 1..2_400) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val step = before.distance(actor.location)
                (step <= .215001) shouldBe true
                walked += step
                furthestX = maxOf(furthestX, actor.location.x)
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} walked=$walked ${h.navigationState()}") {
                h.deliveries shouldBe 1
                (walked > 35) shouldBe true
                (furthestX > 19) shouldBe true
                citizenNearEndpoint(CitizenPoint(actor.location.x, actor.location.y, actor.location.z), hall.entrance) shouldBe true
            }
            h.city.workers.single().completedCycles shouldBe 0L
            h.city.stock shouldBe Stock()
            h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token)
            val accepted = h.city
            val staleCities = listOf(
                accepted.copy(buildings = listOf(hall, producer.copy(level = 2))),
                accepted.copy(buildings = listOf(hall.copy(entrance = hall.entrance.copy(x = 1)), producer)),
                accepted.copy(workers = accepted.workers.map { it.copy(assignment = null) }),
                accepted.copy(autonomy = CivAutonomy(paused = true)),
                accepted.copy(worldId = UUID.randomUUID()),
            )
            for (stale in staleCities) {
                h.city = stale
                shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            }
            h.city = accepted
            shouldThrow<CivsRejection> {
                h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token.copy(completedCycles = 1))
            }
            h.world.getBlockAt(hall.entrance.x, 63, hall.entrance.z).type = Material.AIR
            shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            h.world.getBlockAt(hall.entrance.x, 63, hall.entrance.z).type = Material.STONE
            h.city = accepted.copy(autonomy = null)
            shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, h.workerId, hall.id) }
            h.city = accepted
            h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token)
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            h.city = CivsRules.deliver(h.city, h.city.owner, h.workerId, producer.id,
                token.completedCycles, token.nextDeliveryAt, requireNotNull(h.navigationNow))
            h.pendingDelivery.complete(h.city)
            h.owner.pulse(requireNotNull(h.navigationNow) + 4_000)
            h.city.workers.single().completedCycles shouldBe 1L
            h.city.stock.stone shouldBe CivsRules.deliveryAmount(producer)
            h.deliveries shouldBe 1
            shouldThrow<CivsRejection> { h.owner.requireAutonomousDelivery(h.city.id, h.workerId, token) }
            h.workPlans shouldBe 0
        }
    }

    test("native placement counts toward the next builder session without premature credit or a second idle session") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(reservedBatch = 0), workCredit = 1)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), hut, target), construction = order,
                workers = h.city.workers.map { CivsEquipment.collectSupplies(it, mapOf("STONE" to 1L)) }))
            h.world.getBlockAt(30, 64, 0).type = Material.BARREL
            val actor = h.owner.collisionActors().single()
            actor.teleport(h.constructionTarget().let { Location(h.world, it.x + .5, it.y.toDouble(), it.z + .5) })
            h.onDelivery = { token -> token.constructionId shouldBe order.id }
            h.owner.pulse(2_000)
            for (tick in 1..12) h.owner.pulseFieldWork(2_000 + tick * 250L)
            h.phase() shouldBe CitizenPhase.READY
            h.owner.constructionReady(h.city.id, order.id) shouldBe true
            h.deliveries shouldBe 0 // Existing credited placement never pre-fills more credit.
            h.city.workers.single().completedCycles shouldBe 0L
            h.city = CivsRules.completeBatch(h.city, h.city.owner, order.id)
            h.city.construction!!.workCredit shouldBe 0
            h.owner.pulseFieldWork(5_250)
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.deliveries shouldBe 1 // First 250ms handoff, without another 2s session or full pulse.
            h.owner.status(h.workerId) shouldBe "workers.status.building"
            repeat(8) { h.owner.pulseFieldWork(5_500 + it * 250L) }
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.construction!!.workCredit shouldBe 0
            val worker = h.city.workers.single()
            h.city = CivsRules.deliver(h.city, h.city.owner, worker.id, hut.id, worker.completedCycles, worker.nextDeliveryAt, 5_250)
            h.pendingDelivery.complete(h.city)
            h.owner.pulseFieldWork(7_500)
            h.city.workers.single().completedCycles shouldBe 1L
            h.city.construction!!.workCredit shouldBe 1 // Only the remaining row is credited.
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.constructionReady(h.city.id, order.id) shouldBe true
            h.deliveries shouldBe 1
        }
    }

    for (carried in listOf(false, true)) test("a builder renews exhausted work credit at the site when supplied carried=$carried") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(reservedBatch = 0), workCredit = 1)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), hut, target), construction = order,
                workers = h.city.workers.map { if (carried) CivsEquipment.collectSupplies(it, mapOf("STONE" to 1L)) else it }))
            h.world.getBlockAt(30, 64, 0).type = Material.BARREL
            val actor = h.owner.collisionActors().single()
            val site = h.constructionTarget().let { Location(h.world, it.x + .5, it.y.toDouble(), it.z + .5) }
            actor.teleport(site)
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            h.city = CivsRules.completeBatch(h.city, h.city.owner, order.id)
            h.city.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
            h.workReady = CivsEquipment.usableCount(h.city.workers.single(), "STONE") > 0
            h.owner.pulse(3_000)
            if (carried) repeat(20) { h.navigate() }
            else for (tick in 1..512) {
                h.navigate()
                val at = actor.location
                if (citizenAtStorage(h.world, CitizenPoint(at.x, at.y, at.z), hut.control!!)) break
            }
            h.supplyRequests shouldBe 0
            if (carried) {
                actor.location shouldBe site
                h.owner.workingResidentIds(h.city.id) shouldBe setOf(h.workerId)
            } else {
                citizenAtStorage(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z), hut.control!!) shouldBe true
                h.owner.pulse(4_000)
                h.supplyRequests shouldBe 1
                h.owner.requireStorageArrival(h.city.id, h.workerId, hut.id)
                h.owner.pulse(5_000)
                h.supplyRequests shouldBe 1
                h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
                h.owner.constructionReady(h.city.id, order.id) shouldBe false
            }
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an accepted packet hit freezes work until durable injury and viewer retirement cannot heal it") {
        residentRuntime { h ->
            h.hit(7.0)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.hit(7.0)
            h.hits shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.visualHealth shouldBe 13.0
            h.viewing = false; h.owner.pulse(3_000)
            h.owner.activeCount() shouldBe 0
            h.viewing = true; h.owner.pulse(4_000)
            h.owner.activeCount() shouldBe 1
            h.visualHealth shouldBe 13.0
        }
    }

    test("durable death removes the worker and never creates a replacement packet actor") {
        residentRuntime { h ->
            h.hit(20.0)
            h.owner.activeCount() shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 0
            repeat(5) { h.owner.pulse(3_000L + it * 1_000) }
            h.spawns shouldBe 1
            h.city.workers shouldBe emptyList()
        }
    }

    test("unknown injury commit stays frozen until explicit canonical reconciliation") {
        residentRuntime { h ->
            h.hit(3.0)
            h.pending.completeExceptionally(IllegalStateException("unknown commit"))
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.owner.reconcileResidents()
            h.owner.pulse(3_000)
            h.visualHealth shouldBe 20.0
        }
    }

    for (outdoorStart in listOf(CivPosition(20, 64, -11), CivPosition(19, 64, 9)))
    test("an outdoor miner at $outdoorStart approaches the entrance and works only from completed supported ground") {
        residentRuntime { h ->
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(20, 70, 0), 180)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
            bottom shouldBe CivPosition(23, 65, 10)
            mine.entrance shouldBe CivPosition(20, 71, 0)
            for (x in -1..3) for (z in -2..2) h.world.loadChunk(x, z)
            // Native outdoor footing is y64, one block below the authored bottom. A
            // real staircase reaches the entrance; the inside steps reach its ladder.
            for (rise in 1..7) for (x in 19..21) for (y in 64..63 + rise)
                h.world.getBlockAt(x, y, rise - 8).type = Material.STONE
            for (x in 19..21) for (y in 64..70) h.world.getBlockAt(x, y, 0).type = Material.STONE
            for (z in 1..10) for (y in 64..maxOf(66, 70 - z))
                h.world.getBlockAt(20, y, z).type = Material.STONE
            for (x in 21..22) for (y in 64..66) h.world.getBlockAt(x, y, 10).type = Material.STONE
            for (y in bottom.y..bottom.y + 2) {
                h.world.getBlockAt(bottom.x, y, bottom.z).type = Material.LADDER
                val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = y)
                h.world.getBlockAt(backing.x, y, backing.z).type = Material.COBBLESTONE
            }
            val first = geometry.steps.first()
            val focus = CivsWorksiteLayouts.point(mine, first.point)
            h.work = CivWorldWork(h.workerId, 0, mine.id, bottom,
                listOf(CivPlanBlock(focus, "minecraft:air", requireNotNull(first.after))), inputs = mapOf("COBBLESTONE" to 1L),
                durationMillis = 1_500, focus = focus, status = "digging-mine",
                miningStep = 0, miningAccessStep = 0, miningPlanVersion = geometry.version)
            h.city = h.city.copy(buildings = h.city.buildings + mine,
                workers = h.city.workers.map { it.copy(assignment = mine.id, nextDeliveryAt = 1) },
                minePlans = mapOf(mine.id.toString() to CivMinePlanState(geometry.version, 0)))
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, outdoorStart.x + .5, outdoorStart.y.toDouble(), outdoorStart.z + .5)
            actor.teleport(origin)
            citizenStandable(h.world, CivPosition(origin.blockX, origin.blockY, origin.blockZ)) shouldBe true
            citizenMineWorkRouteAllowed(mine, CitizenPoint(origin.x, origin.y, origin.z), 0) shouldBe false
            val future = CivsWorksiteLayouts.point(mine,
                geometry.floors.first().cells.first().copy(y = geometry.floors.first().feetY))
            h.world.getBlockAt(future.x, future.y - 1, future.z).type = Material.STONE
            for (y in future.y..future.y + 1) h.world.getBlockAt(future.x, y, future.z).type = Material.AIR
            citizenStandable(h.world, future) shouldBe true
            citizenMineWorkRouteAllowed(mine, CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5), 0) shouldBe false
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            // dy(start, work)=1 must not bypass the entrance just because the job
            // is at the first rung rather than in an already excavated gallery.
            h.routeTarget() shouldBe Location(h.world, 20.5, 71.0, .5)
            requireNotNull(h.routeMotion(h.workerId)).capturePermission(
                CitizenPoint(origin.x, origin.y, origin.z), CitizenPoint(origin.x, origin.y, origin.z + 1.0)) shouldBe true
            var entered = false
            var observedInsideLeg = false
            var previousFall = 0.0
            val requireEntranceArrival = outdoorStart.z < 0
            for (tick in 1..2_400) {
                val before = actor.location
                h.navigate()
                val after = actor.location
                val walking = after.clone().also { if (it.y < before.y) it.y = before.y }
                withClue("tick=$tick entered=$entered at=$after ${h.navigationState()}") {
                    (before.distance(walking) <= .215001) shouldBe true
                    val fall = (before.y - after.y).coerceAtLeast(0.0)
                    (fall <= maxOf(.215, minOf(1.0, (previousFall + .08) * .98)) + .000001) shouldBe true
                    previousFall = fall
                    citizenPacketBodyClear(h.world, CitizenPoint(after.x, after.y, after.z), false) shouldBe true
                }
                if (after.distanceSquared(Location(h.world, 20.5, 71.0, .5)) <= .25) entered = true
                if (!entered && requireEntranceArrival) h.deliveries shouldBe 0
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (entered && h.routeTarget() == Location(h.world, bottom.x + .5, bottom.y.toDouble(), bottom.z + .5)) {
                    observedInsideLeg = true
                    val futurePoint = CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5)
                    citizenControlStepClear(h.city, futurePoint, futurePoint) shouldBe true
                    requireNotNull(h.routeMotion(h.workerId)).capturePermission(
                        futurePoint, futurePoint) shouldBe false
                }
                if (entered) citizenMineWorkRouteAllowed(mine, CitizenPoint(after.x, after.y, after.z), 0) shouldBe true
                if (h.deliveries == 1) {
                    // A nearby outdoor start may reach valid work ground before
                    // the entrance waypoint: the existing reach contract permits it.
                    citizenPacketSupported(h.world, CitizenPoint(after.x, after.y, after.z)) shouldBe true
                    citizenMineWorkRouteAllowed(mine, CitizenPoint(after.x, after.y, after.z), 0) shouldBe true
                    citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe true
                    break
                }
            }
            withClue("entered=$entered inside=$observedInsideLeg at=${actor.location} ${h.navigationState()}") {
                if (requireEntranceArrival) {
                    entered shouldBe true
                    observedInsideLeg shouldBe true
                }
                h.deliveries shouldBe 1
                h.failedWorkPositions shouldBe emptyList()
                h.pendingDelivery.isDone shouldBe false
                h.city.workers.single().completedCycles shouldBe 0L
            }
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            citizenMineWorkRouteAllowed(mine, CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5), 0) shouldBe false
        }
    }

    for (preparationFailure in listOf(false, true))
    test("a failed final mine approach selects another stance and delivers once without recovery, preparation=$preparationFailure") {
        residentRuntime { h ->
            val first = h.prepareMineWork(CivPosition(26, 64, 0))
            val alternative = first.copy(standing = CivPosition(22, 64, 0))
            val quarry = requireNotNull(h.city.building(first.buildingId))
            CivsMinePlan.workStandingAllowed(quarry, first.standing) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry, alternative.standing) shouldBe true
            citizenStandable(h.world, first.standing) shouldBe true
            citizenStandable(h.world, alternative.standing) shouldBe true
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            h.onWorkPositionFailed = { city, worker, failed ->
                city.id shouldBe h.city.id
                worker.id shouldBe h.workerId
                failed shouldBe first
                h.work = alternative
                true
            }
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-work"
            if (preparationFailure) {
                requireNotNull(h.routeMotion(h.workerId)).planningFailed("preparation-budget")
                h.routeFailure() shouldBe "preparation-budget"
                actor.location.toVector() shouldBe origin.toVector()
                h.failedWorkPositions shouldBe emptyList()
                h.owner.pulse(2_000)
                actor.location.toVector() shouldBe origin.toVector()
            } else {
                var observedPartial = false
                for (tick in 1..1_000) {
                    h.navigate()
                    if (h.routeMotion(h.workerId)?.continuationReason == "no-path") observedPartial = true
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (h.deliveries == 1) break
                }
                withClue(h.navigationState()) { h.deliveries shouldBe 1 }
                observedPartial shouldBe true
                h.failedWorkPositions shouldBe emptyList()
                citizenCanReachBlock(actor, requireNotNull(first.focus), CITIZEN_MINE_REACH) shouldBe true
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, first)
                h.city.workers.single().completedCycles shouldBe 0L
                h.commitWork()
                h.owner.pulse(requireNotNull(h.navigationNow) + 4_000)
                h.city.workers.single().completedCycles shouldBe 1L
                h.deliveries shouldBe 1
                return@residentRuntime
            }
            h.deliveries shouldBe 0
            h.failedWorkPositions shouldBe listOf(first)
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            h.routeIdentity() shouldBe null
            val selections = h.workPlans
            h.navigationNow = requireNotNull(h.navigationNow) + 1
            h.owner.pulse(requireNotNull(h.navigationNow))
            (h.workPlans > selections) shouldBe true
            h.work shouldBe alternative
            requireNotNull(h.routeIdentity())
            for (tick in 1..1_000) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .172001) shouldBe true
                h.failedWorkPositions shouldBe listOf(first)
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            (actor.location.x > origin.x && actor.location.x < requireNotNull(first.focus).x) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry,
                CivPosition(actor.location.blockX, actor.location.blockY, actor.location.blockZ)) shouldBe true
            citizenCanReachBlock(actor, requireNotNull(first.focus), CITIZEN_MINE_REACH) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, alternative)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.spawns shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            h.commitWork()
            h.owner.pulse(requireNotNull(h.navigationNow) + 4_000)
            h.city.workers.single().completedCycles shouldBe 1L
            h.deliveries shouldBe 1
            h.failedWorkPositions shouldBe listOf(first)
        }
    }

    test("a retained forestry action reselects a changed standing cell before route admission and commits once") {
        residentRuntime { h ->
            val previous = h.assignFarm()
            val forester = previous.copy(type = BuildingType.FORESTER, production = "OAK_LOG")
            val focus = CivPosition(23, 64, 0)
            for (x in -1..2) for (z in -2..1) h.world.loadChunk(x, z)
            val rootCode = 15 or (15 shl 5)
            val job = CivForestryJob(UUID.randomUUID(), focus, "OAK_SAPLING", listOf(focus),
                listOf("minecraft:oak_log[axis=y]"), listOf(rootCode or (1 shl 10), rootCode),
                next = 1, harvestedLogs = 1)
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null, stock = Stock(7, 8, 9),
                buildings = h.city.buildings.map { if (it.id == forester.id) forester else it },
                workers = h.city.workers.map { worker -> worker.copy(completedCycles = 1,
                    items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                        4 -> CivResidentItem("IRON_AXE", 1)
                        CivsEquipment.BAG -> CivResidentItem("COAL", 11)
                        else -> null
                    } }) }, forestryJobs = mapOf(forester.id.toString() to job)))
            h.world.getBlockAt(focus.x, focus.y, focus.z).blockData = Bukkit.createBlockData(job.states.single())
            val first = CivWorldWork(h.workerId, 1, forester.id, focus.copy(z = -1),
                listOf(CivPlanBlock(focus, job.states.single(), CivsForestry.saplingState(job.sapling))),
                durationMillis = 1_500, status = "felling-tree", focus = focus,
                forestry = CivForestryProgress(job, 2, harvestedLogs = 1))
            val alternative = first.copy(standing = focus.copy(x = 24))
            // Reconcile the physical-fixture → Auto storage change before observing
            // the work approach; that lifecycle transition may legitimately respawn.
            h.idleReason = "searching-work"
            h.navigationNow = 1_400L
            h.owner.pulse(1_400)
            h.owner.pulse(1_500)
            val initialSpawns = h.spawns
            h.work = first
            citizenStandable(h.world, first.standing) shouldBe true
            h.world.getBlockAt(first.standing.x, first.standing.y, first.standing.z).type = Material.STONE
            citizenStandable(h.world, first.standing) shouldBe false
            citizenStandable(h.world, alternative.standing) shouldBe true
            val before = h.city
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            h.onWorkPositionFailed = { city, worker, failed ->
                city shouldBe before
                worker shouldBe before.workers.single()
                failed shouldBe first
                h.routeIdentity() shouldBe null // The invalid endpoint fails before any native route exists.
                h.work = alternative
                true
            }
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            withClue(h.navigationState()) { h.failedWorkPositions shouldBe listOf(first) }
            h.phase() shouldBe CitizenPhase.IDLE
            h.routeIdentity() shouldBe null
            actor.location.toVector() shouldBe origin.toVector()
            h.city shouldBe before
            h.deliveries shouldBe 0
            val selections = h.workPlans
            h.navigationNow = 2_001L
            h.owner.pulse(2_001)
            (h.workPlans > selections) shouldBe true
            h.work shouldBe alternative
            requireNotNull(h.routeIdentity())
            for (tick in 1..1_000) {
                val from = actor.location
                h.navigate()
                (from.distance(actor.location) <= .172001) shouldBe true
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                h.failedWorkPositions shouldBe listOf(first)
                h.city shouldBe before
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            (actor.location.distanceSquared(origin) > 1.0) shouldBe true
            alternative.forestry shouldBe first.forestry
            alternative.changes shouldBe first.changes
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, alternative)
            h.pendingDelivery.isDone shouldBe false
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            h.city shouldBe before
            val after = CivsRules.finishWorldWork(before, alternative, requireNotNull(h.navigationNow) + 4_000)
            alternative.changes.forEach { change ->
                h.world.getBlockAt(change.position.x, change.position.y, change.position.z).blockData = Bukkit.createBlockData(change.after)
            }
            h.city = after
            h.work = null
            h.idleReason = "waiting-growth"
            h.pendingDelivery.complete(after) shouldBe true
            repeat(5) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 5) * 1_000) }
            h.deliveries shouldBe 1
            h.failedWorkPositions shouldBe listOf(first)
            after.workers.single().completedCycles shouldBe 2L
            after.workers.single().items shouldBe before.workers.single().items
            after.stock shouldBe before.stock.copy(wood = before.stock.wood + CivsRules.deliveryAmount(forester))
            after.forestryJobs shouldBe null
            h.pendingDelivery.complete(after) shouldBe false
            h.spawns shouldBe initialSpawns
        }
    }

    test("a transient mine planner rejection preserves the selected standing position") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(22, 64, 0))
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            h.onWorkPositionFailed = { _, _, _ -> true }
            h.navigationNow = 2_000L
            h.navigationRejections = 1
            h.owner.pulse(2_000)
            for (tick in 1..1_000) {
                if (h.routeFailure() != null) break
                h.navigate(advanceMillis = 0)
            }
            h.navigationRejections shouldBe 0
            withClue(h.navigationState()) { h.routeFailure() shouldBe "planner-busy" }
            h.owner.pulse(2_000)
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            actor.location.toVector() shouldBe origin.toVector()
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a player collision preserves the mine stance and work resumes after the player leaves") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(22, 64, 0))
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            var blockedSteps = 0
            h.owner.residentStepAllowed = { _, _, _ -> blockedSteps++; false }
            h.onWorkPositionFailed = { _, _, _ -> true }
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            for (tick in 1..1_000) {
                h.navigate(advanceMillis = 0)
                if (tick % 20 == 0) h.owner.pulse(2_000)
                if (blockedSteps >= 100) break
            }
            withClue(h.navigationState()) { (blockedSteps >= 100) shouldBe true }
            actor.location.toVector() shouldBe origin.toVector()
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            h.deliveries shouldBe 0
            h.owner.residentStepAllowed = { _, _, _ -> true }
            for (tick in 1..1_000) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .172001) shouldBe true
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            citizenCanReachBlock(actor, requireNotNull(work.focus), CITIZEN_MINE_REACH) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a mining receipt requires observed feet inside the authored gallery even when the block stays in reach") {
        residentRuntime { h ->
            val focus = CivPosition(27, 64, 3)
            val work = h.prepareMineWork(CivPosition(28, 64, 3), focus, CivPosition(20, 75, 8))
            val quarry = requireNotNull(h.city.building(work.buildingId))
            val actor = h.owner.collisionActors().single()
            val inside = Location(h.world, 28.5, 64.0, 3.5)
            val outside = Location(h.world, 29.5, 64.0, 3.5)
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(28, 64, 3)) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(29, 64, 3)) shouldBe false
            actor.teleport(inside)
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(outside)
            citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe true
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            val future = Location(h.world, 24.5, 64.0, 5.5)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(quarry))
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(24, 64, 5)) shouldBe true
            geometry.builtStanding(CivsMinePlan.localPoint(quarry, CivPosition(24, 64, 5)),
                requireNotNull(work.miningAccessStep)) shouldBe false
            actor.teleport(future)
            citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe true
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            actor.teleport(inside)
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a miner waits on its ladder while the next job is searched instead of climbing toward an idle surface walk") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            val actor = h.owner.collisionActors().single()
            for (y in 57..64) h.world.getBlockAt(18, y, 0).type = Material.LADDER
            val start = Location(h.world, 18.5, 61.4, .5)
            actor.teleport(start)
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 61, 0),
                listOf(CivPlanBlock(CivPosition(19, 62, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(19, 62, 0), status = "digging-mine")
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            // This is the real cache gap after a commit / invalidation. Its surface anchor
            // is valid and reachable, so a misplaced idle walk would visibly climb the shaft.
            h.work = null
            h.idleReason = "searching-work"
            h.owner.pulse(3_000)
            repeat(160) { h.navigate() }
            actor.location.distanceSquared(start) shouldBe 0.0
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 57, 0),
                listOf(CivPlanBlock(CivPosition(18, 56, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(18, 56, 0), status = "digging-mine")
            h.owner.pulse(4_000)
            repeat(1_000) {
                h.navigate()
                (actor.location.y <= start.y) shouldBe true
            }
            (actor.location.y < start.y - .1) shouldBe true
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.deliveries shouldBe 0
        }
    }

    test("a miner waiting for a tool walks from the hall to its quarry through an upward ingress step") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 66.0, .5))
            h.world.getBlockAt(0, 65, 0).type = Material.STONE
            h.world.getBlockAt(1, 66, 0).type = Material.STONE
            h.world.getBlockAt(2, 65, 0).type = Material.STONE
            h.world.getBlockAt(3, 64, 0).type = Material.STONE
            h.owner.pulse(2_000)
            val control = requireNotNull(quarry.control)
            val destination = Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)
            // Drain the real 1ms search budget without advancing the recovery timeout.
            // Cold mock block reads may need more calls, but every movement must stay continuous.
            var previousFall = 0.0
            for (index in 0 until 2_000) {
                val before = actor.location
                h.navigate()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L)
                val after = actor.location
                withClue("tick=$index from=$before next=$after ${h.navigationState()}") {
                    // Descending the ingress keeps the walk stride while gravity settles onto support.
                    val walking = after.clone().also { if (it.y < before.y) it.y = before.y }
                    (before.distance(walking) <= .215001) shouldBe true
                    val fall = (before.y - after.y).coerceAtLeast(0.0)
                    (fall <= maxOf(.215, minOf(1.0, (previousFall + .08) * .98)) + .000001) shouldBe true
                    for (sample in 0..16) {
                        val point = before.clone().add(after.toVector().subtract(before.toVector()).multiply(sample / 16.0))
                        citizenPacketBodyClear(h.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                    }
                    previousFall = fall
                }
                if (after.distanceSquared(destination) <= 8.0 * 8.0) break
            }
            val at = actor.location
            (at.distanceSquared(destination) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an unreachable waiting miner recovers beside its quarry without completing work or returning to the hall") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 64.0, .5))
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.owner.pulse(2_000)
            var recovered = false
            repeat(80) { index ->
                val before = actor.location
                h.navigate()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L + (index + 1) * 50)
                if (before.distance(actor.location) > 8.0) recovered = true
            }
            val control = requireNotNull(quarry.control)
            val at = actor.location
            recovered shouldBe true
            (at.distanceSquared(Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.pendingDelivery.isDone shouldBe false
        }
    }

    test("three residents reserve separate full sleeping bodies and release their places at dawn") {
        residentRuntime { h ->
            // Home positions in chunk -1 need the same loaded one-chunk search
            // margin as a real route from those positions.
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-4, 64, -4), CivPosition(4, 68, 4)))
            h.city = h.city.copy(buildings = listOf(home), workers = h.city.workers.map { it.copy(homeId = home.id) } +
                List(2) { CivWorker(UUID.randomUUID(), "Neighbour$it", null, homeId = home.id) })
            h.world.time = 13_000
            h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 3
            val actors = h.owner.collisionActors().toList()
            // Check the initial spawn frame before navigation can move a newcomer
            // out of a sleeper's body and conceal an invalid spawn admission.
            val alreadySleeping = actors.filter { it.pose == PacketNpcPose.SLEEPING }
            alreadySleeping.isNotEmpty() shouldBe true
            for (npc in actors.filter { it.pose != PacketNpcPose.SLEEPING }) {
                val at = npc.location
                val half = .3 * npc.scale
                val body = BoundingBox(at.x - half, at.y, at.z - half,
                    at.x + half, at.y + 1.8 * npc.scale, at.z + half)
                for (sleeper in alreadySleeping) withClue("spawn=${npc.location} sleeper=${sleeper.location}") {
                    body.overlaps(runtimeSleepingBody(sleeper)) shouldBe false
                }
            }
            var slept = false
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(2_000 + tick * 50L)
                if (actors.all { it.pose == PacketNpcPose.SLEEPING }) { slept = true; break }
            }
            withClue(actors.joinToString { "${it.uniqueId} ${it.location} ${h.owner.status(it.uniqueId)} ${h.navigationState(it.uniqueId)}" }) {
                slept shouldBe true
            }
            val bodies = actors.map { npc ->
                val feet = requireNotNull(h.owner.residentFeet(npc.uniqueId))
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
                citizenPacketBodyClear(h.world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
                val body = runtimeSleepingBody(npc)
                citizenSleepClear(h.world, body) shouldBe true
                h.owner.growthBlocked(h.world.uid, CivPosition(kotlin.math.floor(body.centerX).toInt(),
                    kotlin.math.floor(body.centerY).toInt(), kotlin.math.floor(body.centerZ).toInt())) shouldBe true
                home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                body
            }
            for (first in bodies.indices) for (second in first + 1 until bodies.size)
                bodies[first].overlaps(bodies[second]) shouldBe false
            actors.map { h.owner.residentFeet(it.uniqueId)?.toVector() }.toSet().size shouldBe 3
            val grounded = actors.associate { it.uniqueId to requireNotNull(h.owner.residentFeet(it.uniqueId)) }
            h.world.time = 1_000
            h.owner.pulse(30_000)
            for (npc in actors) {
                npc.pose shouldBe PacketNpcPose.STANDING
                npc.location.toVector() shouldBe grounded.getValue(npc.uniqueId).toVector()
            }
            h.deliveries shouldBe 0
        }
    }

    test("a sleeping place occupied during travel releases its reservation before contact and chooses another place") {
        residentRuntime { h ->
            for (x in -2..2) for (z in -2..1) h.world.loadChunk(x, z)
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-4, 64, -4), CivPosition(4, 68, 4)))
            val unavailable = home.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, control = null,
                entrance = CivPosition(30, 64, 0), bounds = CivBox(CivPosition(30, 64, 0), CivPosition(30, 64, 0)))
            val neighbour = CivWorker(UUID.randomUUID(), "Neighbour", null, homeId = unavailable.id)
            h.city = h.city.copy(buildings = listOf(home, unavailable), workers =
                h.city.workers.map { it.copy(homeId = home.id) } + neighbour)
            h.owner.pulse(2_000)
            val actors = h.owner.collisionActors()
            val resident = actors.single { it.uniqueId == h.workerId }
            val visitor = actors.single { it.uniqueId == neighbour.id }
            resident.teleport(Location(h.world, 12.5, 64.0, .5))
            visitor.teleport(Location(h.world, 20.5, 64.0, .5))
            h.world.time = 13_000
            h.owner.pulse(3_000)
            val reserved = Location(h.world, 1.5, 64.0, .5)
            // Another resident occupies the place after reservation. Revalidate before
            // arrival: the physical collision guard must never let this route finish inside it.
            visitor.teleport(reserved)
            h.owner.pulse(4_000)
            resident.pose shouldBe PacketNpcPose.STANDING
            var slept = false
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(4_000 + tick * 50L)
                val at = resident.location
                val feet = BoundingBox(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3)
                if (resident.pose != PacketNpcPose.SLEEPING)
                    feet.overlaps(BoundingBox(reserved.x - .3, reserved.y, reserved.z - .3,
                        reserved.x + .3, reserved.y + 1.8, reserved.z + .3)) shouldBe false
                if (resident.pose == PacketNpcPose.SLEEPING) { slept = true; break }
            }
            withClue("location=${resident.location} ${h.navigationState()}") { slept shouldBe true }
            val at = visitor.location
            val half = .3 * visitor.scale
            val occupied = BoundingBox(at.x - half, at.y, at.z - half,
                at.x + half, at.y + 1.8 * visitor.scale, at.z + half)
            runtimeSleepingBody(resident).overlaps(occupied) shouldBe false
            visitor.location.toVector() shouldBe reserved.toVector()
            h.deliveries shouldBe 0
        }
    }

    test("eight homes without a sleeping place cannot starve the next resident") {
        residentRuntime { h ->
            for (x in -2..3) for (z in -2..1) h.world.loadChunk(x, z)
            h.city = h.city.copy(workers = h.city.workers +
                List(8) { CivWorker(UUID.randomUUID(), "Neighbour$it", null) })
            repeat(4) { h.owner.pulse(2_000 + it * 1_000L) }
            val actors = h.owner.collisionActors().toList()
            actors.size shouldBe 9
            val unavailable = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                entrance = CivPosition(40, 64, 0), control = null,
                bounds = CivBox(CivPosition(40, 64, 0), CivPosition(40, 64, 0)))
            val home = unavailable.copy(id = UUID.randomUUID(), entrance = CivPosition(22, 64, 0),
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, 4)))
            val last = actors.last()
            h.city = h.city.copy(buildings = h.city.buildings + listOf(unavailable, home),
                workers = h.city.workers.map { it.copy(homeId = if (it.id == last.uniqueId) home.id else unavailable.id) })
            actors.dropLast(1).forEachIndexed { index, npc ->
                npc.teleport(Location(h.world, .5 + index * 2, 64.0, .5))
            }
            last.teleport(Location(h.world, 22.5, 64.0, .5))
            h.world.time = 13_000
            h.owner.pulse(7_000)
            last.pose shouldBe PacketNpcPose.STANDING
            h.owner.pulse(8_000)
            withClue("status=${h.owner.status(last.uniqueId)} location=${last.location}") {
                last.pose shouldBe PacketNpcPose.SLEEPING
            }
            actors.dropLast(1).all { it.pose == PacketNpcPose.STANDING } shouldBe true
            h.deliveries shouldBe 0
        }
    }

    test("one available sleeping place never stacks later residents and is reusable after retirement") {
        residentRuntime { h ->
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-3, 64, -3), CivPosition(3, 65, 3)))
            h.city = h.city.copy(buildings = listOf(home), workers = h.city.workers.map { it.copy(homeId = home.id) } +
                List(2) { CivWorker(UUID.randomUUID(), "Neighbour$it", null, homeId = home.id) })
            h.owner.pulse(2_000)
            val actors = h.owner.collisionActors().toList()
            actors.size shouldBe 3
            val only = Location(h.world, 1.5, 64.0, .5)
            // A low ceiling leaves one standing/wake cell while the lying body still
            // fits below it. This supplies exactly one physically valid sleep admission.
            for (x in -3..3) for (z in -3..3) if (x != 1 || z != 0)
                h.world.getBlockAt(x, 65, z).type = Material.STONE
            actors.first { it.uniqueId == h.workerId }.teleport(only)
            actors.filter { it.uniqueId != h.workerId }.forEachIndexed { index, npc ->
                npc.teleport(Location(h.world, 7.5 + index * 2, 64.0, .5))
            }
            h.world.time = 13_000
            repeat(8) { h.owner.pulse(3_000 + it * 1_000L) }
            actors.count { it.pose == PacketNpcPose.SLEEPING } shouldBe 1
            actors.single { it.pose == PacketNpcPose.SLEEPING }.uniqueId shouldBe h.workerId
            actors.filter { it.uniqueId != h.workerId }.forEach {
                it.pose shouldBe PacketNpcPose.STANDING
                (it.location.x >= 7.5) shouldBe true
                h.owner.status(it.uniqueId) shouldBe "workers.status.sheltering"
            }
            h.city = h.city.copy(workers = h.city.workers.filterNot { it.id == h.workerId })
            h.owner.pulse(12_000)
            val next = h.owner.collisionActors().first()
            next.teleport(only)
            repeat(8) { h.owner.pulse(13_000 + it * 1_000L) }
            next.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.collisionActors().count { it.pose == PacketNpcPose.SLEEPING } shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    test("removing a sleeping resident's floor wakes it at logical feet and normal gravity settles it") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.world.getBlockAt(1, 63, 0).type = Material.AIR
            h.owner.pulse(3_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location shouldBe origin
            repeat(30) { h.navigate() }
            actor.location.y shouldBe 63.0
            citizenPacketSupported(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z)) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("a new ceiling cannot force a sleeper to stand inside stone or work before a safe wake space opens") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            val lying = requireNotNull(citizenSleepPose(h.world, origin, 1.0)).bounds
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            val sleepingPosition = actor.location
            for (x in -2..2) for (z in -2..2) h.world.getBlockAt(x, 65, z).type = Material.STONE
            citizenSleepClear(h.world, lying) shouldBe true
            citizenPacketBodyClear(h.world, CitizenPoint(origin.x, origin.y, origin.z), false) shouldBe false
            h.world.time = 0
            for (now in listOf(3_000L, 4_000L)) {
                h.owner.pulse(now)
                repeat(40) { h.navigate() }
                actor.pose shouldBe PacketNpcPose.SLEEPING
                actor.location shouldBe sleepingPosition
                h.owner.workingResidentIds(h.city.id) shouldBe emptySet()
                h.hits shouldBe 0
                (h.owner.status(h.workerId) == "workers.status.injury-pending") shouldBe false
            }
            // There is now a supported spot in the same home with a clear low-body sweep.
            h.world.getBlockAt(2, 65, 0).type = Material.AIR
            h.owner.pulse(5_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            h.hits shouldBe 0
            actor.location.x shouldBe 2.5
            actor.location.y shouldBe origin.y
            citizenPacketBodyClear(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z), false) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("a valid thirty-block night walk continues beyond ten seconds and reaches home without recovery") {
        residentRuntime { h ->
            val home = h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                schematicId = "house1", anchor = CivPosition(29, 64, 0), entrance = CivPosition(29, 64, 0),
                bounds = CivBox(CivPosition(27, 64, -2), CivPosition(31, 68, 2)), control = null)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            for (x in -1..3) for (z in -1..1) h.world.loadChunk(x, z)
            h.world.time = 13_000
            val actor = h.owner.collisionActors().single()
            val origin = requireNotNull(h.owner.residentFeet(h.workerId))
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.owner.pulse(2_000)
                var walking = false
                // Drain bounded A* admission without spending any simulated travel time.
                for (frame in 1..512) {
                    val before = requireNotNull(h.owner.residentFeet(h.workerId))
                    h.navigate()
                    val after = requireNotNull(h.owner.residentFeet(h.workerId))
                    (before.distance(after) <= .118251) shouldBe true
                    if (after.distanceSquared(origin) > .000001) { walking = true; break }
                }
                withClue("planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") { walking shouldBe true }
                var atTenSeconds: Location? = null
                var slept = false
                // Thirty seconds is bounded and below this route's ordinary 55-second budget.
                for (tick in 1..600) {
                    val before = requireNotNull(h.owner.residentFeet(h.workerId))
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                    // Sleeping raises only the visible mesh; logical feet still prove a real arrival.
                    val after = requireNotNull(h.owner.residentFeet(h.workerId))
                    withClue("tick=$tick before=$before after=$after status=${h.owner.status(h.workerId)}") {
                        (before.distance(after) <= .118251) shouldBe true
                    }
                    if (tick == 200) atTenSeconds = after
                    if (tick == 220) {
                        h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
                        actor.pose shouldBe PacketNpcPose.STANDING
                        home.bounds.contains(after.blockX, after.blockY, after.blockZ) shouldBe false
                        (after.distance(requireNotNull(atTenSeconds)) > .5) shouldBe true
                    }
                    if (actor.pose == PacketNpcPose.SLEEPING) {
                        (tick > 220) shouldBe true
                        slept = true
                        break
                    }
                }
                withClue("status=${h.owner.status(h.workerId)} location=${actor.location} " +
                    "planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") { slept shouldBe true }
                val feet = requireNotNull(h.owner.residentFeet(h.workerId))
                home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
                h.owner.status(h.workerId) shouldBe "workers.status.sleeping"
                messages.none { "citizen_route_recovered" in it || "ARCCIVS_NAVIGATION_FAILED" in it } shouldBe true
                h.spawns shouldBe 1
                h.deliveries shouldBe 0
                h.city.workers.single().completedCycles shouldBe 0L
            } finally { h.logger.removeHandler(handler) }
        }
    }

    test("an enclosed builder waiting for materials recovers into its home at night without work credit") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val home = h.city.buildings.first()
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L)), workCredit = 0)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(home, hut, target), construction = order,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            h.world.time = 13_000
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 20.5, 68.0, .5))
            h.world.getBlockAt(20, 67, 0).type = Material.STONE
            for (x in 19..21) for (z in -1..1) if (x != 20 || z != 0)
                for (y in 68..70) h.world.getBlockAt(x, y, z).type = Material.STONE
            // A player occupying every recovery destination must keep the same failed route pending.
            h.owner.residentStepAllowed = { _, from, to -> from.distanceSquared(to) > .000001 }
            h.owner.pulse(2_000)
            repeat(200) { h.navigate() }
            h.owner.pulse(13_001)
            actor.location.x shouldBe 20.5
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentStepAllowed = { _, _, _ -> true }
            h.owner.pulse(14_001)
            val recovered = actor.location
            home.bounds.contains(recovered.blockX, recovered.blockY, recovered.blockZ) shouldBe true
            h.owner.pulse(15_001)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.status(h.workerId) shouldBe "workers.status.sleeping"
            verify { actor.sleepingBed = null }
            val lying = actor.location
            (lying.y > recovered.y) shouldBe true
            h.owner.residentFeet(h.workerId) shouldBe recovered
            h.owner.residentFeet(h.workerId)?.add(0.0, 10.0, 0.0) // Caller mutation cannot alter the saved feet.
            h.owner.residentFeet(h.workerId) shouldBe recovered
            repeat(20) { h.navigate() }
            actor.location.y shouldBe lying.y // Gravity must not undo the pose-only floor clearance.
            // The pose is rechecked against the current world, not trusted until morning.
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                h.world.getBlockAt(recovered.blockX + dx, recovered.blockY, recovered.blockZ + dz).type = Material.STONE
            h.owner.pulse(16_001)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location.y shouldBe recovered.y
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentFeet(h.workerId) shouldBe recovered
            h.city.construction shouldBe order
            h.city.workers.single().completedCycles shouldBe 0
            h.deliveries shouldBe 0
            h.pendingDelivery.isDone shouldBe false
            h.steps shouldBe emptyList()
            h.spawns shouldBe 1
        }
    }

    test("a committed resurrection prefers safe body-site admission and never moves an existing actor") {
        residentRuntime { h ->
            val saved = h.city
            h.city = saved.copy(workers = emptyList())
            h.owner.pulse(2_000)
            h.owner.collisionActors().size shouldBe 0
            h.city = saved
            val body = Location(h.world, 12.5, 64.0, .5)
            h.owner.requestAdmissionAt(saved.id, h.workerId, body)
            h.owner.pulse(3_000)
            val actor = h.owner.collisionActors().single()
            (actor.location.distance(body) <= 6.0) shouldBe true
            (actor.location.x > 6) shouldBe true
            citizenPacketSupported(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z)) shouldBe true
            val before = actor.location.clone()
            h.owner.requestAdmissionAt(saved.id, h.workerId, Location(h.world, 25.5, 64.0, .5))
            actor.location shouldBe before
            h.spawns shouldBe 2
            h.city shouldBe saved
            h.deliveries shouldBe 0
        }
    }

    for (headOn in listOf(false, true))
    test("autonomous depot traffic detours or uses short contact transit and completes both real trips headOn=$headOn") {
        residentRuntime(workerId = UUID.fromString("06254fa5-4795-4a8e-a7c7-783f9fc90cfd")) { h ->
            val (farm, quarry, forester) = h.autonomousTrafficTown(headOn)
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            // The returning farmer first earns a complete session at the real workplace.
            for (tick in 1..3_000) {
                h.navigate()
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (h.phase() == CitizenPhase.RETURNING) break
            }
            withClue(h.navigationState()) { h.phase() shouldBe CitizenPhase.RETURNING }
            h.deliveries shouldBe 0
            val minerId = UUID.fromString("679ad6aa-2a43-4c17-89ba-16589232fed3")
            val foresterId = UUID.fromString("da33dc2d-c343-48c7-9b8d-10cda594970e")
            val idleId = UUID.fromString("e79fb27a-c939-4cb2-9cd6-ec9542a3465d")
            h.city = h.city.copy(workers = h.city.workers + listOf(
                CivWorker(minerId, "Miner", quarry.id, completedCycles = 88, nextDeliveryAt = 1),
                CivWorker(foresterId, "Forester", forester.id, nextDeliveryAt = 1), CivWorker(idleId, "Idle", null)))
            h.idleReason = "searching-work"
            val farmer = h.owner.collisionActors().single()
            farmer.teleport(if (headOn) Location(h.world, -1866.8092239145421, 64.0, -2758.5)
                else Location(h.world, -1866.8092239145421, 64.0, -2757.809223914542))
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow)) }
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.size shouldBe 4
            // One fixture placement reproduces the 2026-10-25 16:02:06 UTC Auto2 snapshot.
            // All subsequent displacement, including the idle neighbours, belongs to the runtime.
            actors.getValue(minerId).teleport(Location(h.world, -1867.5, 64.0,
                if (headOn) -2758.5 else -2758.303946199169))
            actors.getValue(foresterId).teleport(Location(h.world, -1868.5, 64.0, -2758.5))
            actors.getValue(idleId).teleport(Location(h.world, -1867.5, 64.0,
                if (headOn) -2759.2 else -2758.9962500000006))
            repeat(2) { h.owner.pulse(requireNotNull(h.navigationNow)) }
            val before = CivsRules.validate(h.city)
            val received = mutableSetOf<UUID>()
            h.workerDelivery = { worker, token ->
                h.owner.requireAutonomousDelivery(h.city.id, worker, token)
                h.city = CivsRules.deliver(h.city, h.city.owner, worker, requireNotNull(token.assignmentId),
                    token.completedCycles, token.nextDeliveryAt, requireNotNull(h.navigationNow))
                received += worker
                CompletableFuture.completedFuture(h.city)
            }
            var yielded = false
            val afterYield = actors.keys.associateWith { 2.0 }.toMutableMap()
            val passingRoutes = mutableMapOf<UUID, Triple<CitizenPacketRoute, Double, CitizenPhase>>()
            var passingResumed = 0
            for (tick in 1..4_000) {
                val previous = actors.mapValues { it.value.location }
                val progress = actors.keys.associateWith { h.routeMotion(it) to h.routeProgress(it) }
                val yielding = actors.keys.filter(h::yielding).associateWith { h.routeProgress(it) }
                h.navigate()
                yielded = yielded || actors.keys.any(h::yielding)
                for ((id, npc) in actors) {
                    (previous.getValue(id).distance(npc.location) <= .215001) shouldBe true
                    if (id !in yielding && h.yielding(id)) {
                        withClue("Repeated yield without two real forward blocks: worker=$id progress=$afterYield") {
                            (afterYield.getValue(id) >= 2.0) shouldBe true
                        }
                        afterYield[id] = 0.0
                        val passing = requireNotNull(h.yieldPassing(id))
                        h.routeMotion(passing)?.takeIf { !it.searching && !it.finished }?.let { motion ->
                            passingRoutes[passing] = Triple(motion, motion.forwardProgress, h.phase(passing))
                        }
                    } else {
                        val prior = if (progress.getValue(id).first === h.routeMotion(id)) progress.getValue(id).second ?: 0.0 else 0.0
                        val walked = ((h.routeProgress(id) ?: 0.0) - prior).coerceAtLeast(0.0)
                        afterYield[id] = afterYield.getValue(id) + walked
                    }
                    if (id in yielding) {
                        (previous.getValue(id).distance(npc.location) <= .050001) shouldBe true
                        h.routeProgress(id) shouldBe yielding.getValue(id)
                    }
                    passingRoutes[id]?.let { (motion, startProgress, phase) ->
                        if (h.phase(id) != phase || motion.finished) passingRoutes.remove(id)
                        else if (motion.forwardProgress > startProgress + .000001) {
                            withClue("Passing worker replanned on its first clear step: $id ${h.navigationState(id)}") {
                                (h.routeMotion(id) === motion) shouldBe true
                            }
                            passingRoutes.remove(id)
                            passingResumed++
                        }
                    }
                }
                val bodies = actors.values.map { npc -> npc.location.let { at ->
                    BoundingBox(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3)
                } }
                withClue("tick=$tick positions=${actors.mapValues { it.value.location }}") {
                    val handles = actors.values.toList()
                    bodies.indices.all { a -> (a + 1 until bodies.size).all { b ->
                        !bodies[a].overlaps(bodies[b]) || h.owner.passingBodies(handles[a]) || h.owner.passingBodies(handles[b])
                    } } shouldBe true
                }
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (received.containsAll(listOf(h.workerId, minerId))) break
            }
            withClue("received=$received ${h.owner.debug(h.city.id, null)}") {
                // Both trips retain their real arrival/receipt contract. Stationary neighbours are never displaced.
                yielded shouldBe false
                received.containsAll(listOf(h.workerId, minerId)) shouldBe true
            }
            (h.city.workers.first { it.id == h.workerId }.completedCycles >= 114) shouldBe true
            (h.city.workers.first { it.id == minerId }.completedCycles >= 89) shouldBe true
            (h.city.stock.food >= before.stock.food + CivsRules.deliveryAmount(farm)) shouldBe true
            (h.city.stock.stone >= before.stock.stone + CivsRules.deliveryAmount(quarry)) shouldBe true
            h.city.inventory shouldBe null
            h.city.workers.all { it.items == null } shouldBe true
        }
    }

    for (supportHole in listOf(false, true))
    test("a fixed resident cannot authorize crossing a missing support or sealed native corridor supportHole=$supportHole") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(control = null, production = null,
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, -1)))
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            val hall = h.city.buildings.first().copy(bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -2)))
            val forester = quarry.copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
                anchor = CivPosition(10, 64, 10), entrance = CivPosition(10, 64, 10),
                bounds = CivBox(CivPosition(8, 64, 7), CivPosition(12, 68, 9)))
            val blocker = UUID(0, 99)
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(hall, quarry, forester),
                workers = h.city.workers + CivWorker(blocker, "Waiting forester", forester.id, nextDeliveryAt = 1)))
            h.idleReason = "searching-work"
            h.owner.collisionActors().single().teleport(Location(h.world, .5, 64.0, if (supportHole) .8 else .5))
            h.navigationNow = 2_000
            // Hold movement without rejecting the clear initial storage arrival probe.
            h.owner.residentStepAllowed = { _, from, to -> from.x == to.x && from.y == to.y && from.z == to.z }
            repeat(2) { h.owner.pulse(2_000) }
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.getValue(blocker).teleport(Location(h.world, 10.5, 64.0, 10.5))
            for (tick in 1..200) {
                h.navigate(0)
                if (h.routeMotion(h.workerId)?.let { !it.searching && it.waitingForCollision } == true) break
            }
            withClue(h.navigationState()) { h.routeMotion(h.workerId)?.waitingForCollision shouldBe true }
            // The geometry token is already accepted; only a new physical obstruction changes.
            actors.getValue(blocker).teleport(Location(h.world, 1.25, 64.0, if (supportHole) .8 else .5))
            if (supportHole) h.world.getBlockAt(1, 63, 1).type = Material.AIR // z=.8 and z=2.05 supported; z=1 is not.
            else {
                for (x in -2..3) for (z in listOf(-1, 1)) for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
                for (x in listOf(-1, 2)) for (z in -1..1) for (y in 64..66)
                    h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            h.owner.residentStepAllowed = { npc, _, _ -> npc.uniqueId != blocker }
            val fixed = actors.getValue(blocker).location
            var yielded = false
            val frames = if (supportHole) 200 else 1_400
            for (tick in 1..frames) {
                val previous = actors.mapValues { it.value.location }
                h.navigate()
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                yielded = yielded || h.yielding(blocker)
                actors.getValue(blocker).location shouldBe fixed
                actors.forEach { (id, npc) -> (previous.getValue(id).distance(npc.location) <= .172001) shouldBe true }
                val walker = actors.getValue(h.workerId).location
                citizenPacketSupported(h.world, walker.point()) shouldBe true
                citizenPacketBodyClear(h.world, walker.point(), false) shouldBe true
            }
            withClue(h.owner.debug(h.city.id, null)) {
                yielded shouldBe false
                if (supportHole) (actors.getValue(h.workerId).location.x > 3) shouldBe true
                else {
                    // The native standing envelope uses .299 half-width against the wall beginning at x=2.
                    (actors.getValue(h.workerId).location.x <= 1.701001) shouldBe true
                    (h.recoveryMillis > 60_000) shouldBe true
                }
            }
            h.deliveries shouldBe 0
            h.city.stock shouldBe Stock()
            h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 0L
        }
    }

    test("a working route resumes after resident yielding without spending its stall interval") {
        residentRuntime(workerId = UUID.fromString("06254fa5-4795-4a8e-a7c7-783f9fc90cfd")) { h ->
            h.autonomousTrafficTown(headOn = false)
            val requester = UUID(0, 98)
            h.navigationNow = 2_000
            repeat(2) { h.owner.pulse(2_000) }
            for (tick in 1..3_000) {
                h.navigate()
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if ((h.routeProgress(h.workerId) ?: 0.0) >= 3.0) break
            }
            withClue(h.navigationState()) { ((h.routeProgress(h.workerId) ?: 0.0) >= 3.0) shouldBe true }
            h.city = h.city.copy(workers = h.city.workers + CivWorker(requester, "Returning neighbour", null))
            h.owner.pulse(requireNotNull(h.navigationNow))
            val actor = h.owner.collisionActors().single { it.uniqueId == h.workerId }
            val motion = requireNotNull(h.routeMotion(h.workerId))
            val before = actor.location
            val progress = motion.forwardProgress
            h.requestYieldAsReturning(requester, h.workerId, before.clone().add(.8, 0.0, 0.0))
            withClue(h.owner.debug(h.city.id, null)) { h.yielding(h.workerId) shouldBe true }
            var ticks = 0
            while (h.yielding(h.workerId) && ticks++ < 100) {
                val previous = actor.location
                h.navigate()
                (previous.distance(actor.location) <= .050001) shouldBe true
                motion.forwardProgress shouldBe progress
            }
            h.yielding(h.workerId) shouldBe false
            (ticks >= 45) shouldBe true
            (before.distance(actor.location) > 1.2) shouldBe true
            // The next ready frame is a real rejoin opportunity, not a failed journey.
            h.navigate()
            withClue(h.navigationState()) { (h.routeMotion(h.workerId) === motion) shouldBe true }
            h.deliveries shouldBe 0
            h.city = h.city.copy(workers = h.city.workers.filter { it.id != requester })
            h.owner.pulse(requireNotNull(h.navigationNow))
            for (tick in 1..1_000) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (h.phase() == CitizenPhase.WORKING) break
            }
            withClue(h.navigationState()) { h.phase() shouldBe CitizenPhase.WORKING }
        }
    }

    test("an arrived work route cannot yield away before its coarse arrival pulse") {
        residentRuntime { h ->
            h.prepareMineWork(CivPosition(22, 64, 0))
            val requester = UUID(0, 98)
            h.city = h.city.copy(workers = h.city.workers + CivWorker(requester, "Returning neighbour", null))
            h.navigationNow = 2_000
            repeat(2) { h.owner.pulse(2_000) }
            for (tick in 1..500) {
                h.navigate()
                if (h.routeFinished(h.workerId)) break
            }
            withClue(h.navigationState()) {
                h.routeFinished(h.workerId) shouldBe true
                h.phase() shouldBe CitizenPhase.TO_WORK
            }
            val actor = h.owner.collisionActors().single { it.uniqueId == h.workerId }
            val arrived = actor.location
            // Seed only the other actor's scheduling state: this probes the exact main-thread
            // handoff between navigation arrival and the work pulse, without granting a receipt.
            h.requestYieldAsReturning(requester, h.workerId, arrived.clone().add(.8, 0.0, 0.0))
            h.yielding(h.workerId) shouldBe false
            repeat(30) {
                h.navigate()
                actor.location.toVector() shouldBe arrived.toVector()
            }
            h.owner.pulse(requireNotNull(h.navigationNow))
            actor.location.toVector() shouldBe arrived.toVector()
            h.phase() shouldBe CitizenPhase.WORKING
            h.deliveries shouldBe 0
        }
    }

    test("a real player permits bounded contact transit and departure preserves the original mine job receipt") {
        residentRuntime { h ->
            // This canonical landing cut lies over five blocks from the blocked feet;
            // the neighbouring x=25 cut can legitimately be worked from the approach.
            val work = h.prepareMineWork(CivPosition(23, 64, 0), focus = CivPosition(26, 64, 0))
            // Seal the only passage before route admission, including the rear cap.
            // A player in an open room may legitimately be walked around.
            for (x in 19..26) for (z in -1..1) for (y in 64..66)
                if (x == 19 || x == 26 || z != 0 || y == 66)
                    h.world.getBlockAt(x, y, z).type = Material.STONE
            (20..25).all { x ->
                citizenPacketBodyClear(h.world, CitizenPoint(x + .5, 64.0, .5), false)
            } shouldBe true
            val actor = h.owner.collisionActors().single()
            val focus = requireNotNull(work.focus)
            citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe false
            val player = h.playerAt(Location(h.world, 21.2, 64.0, .5))
            val velocity = player.velocity.clone()
            val originalCity = h.city
            h.nativeCollisions().use { collisions ->
                var nativeDenials = 0
                h.owner.residentStepAllowed = { npc, from, to ->
                    collisions.canResidentMove(npc, from, to).also { if (!it) nativeDenials++ }
                }
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                var blockedFrames = 0
                var heldSearchAt: Int? = null
                var heldSearchFrames = 0
                var contact = false
                repeat(360) { tick ->
                    if (nativeDenials > 0 && heldSearchAt == null) h.holdNavigation = true
                    val before = actor.location
                    h.navigate()
                    if (h.pendingNavigationWorkers > 0) {
                        val heldAt = heldSearchAt ?: tick.also { heldSearchAt = it }
                        if (tick - heldAt >= 80) h.releaseNavigation() else heldSearchFrames++
                    }
                    if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                    withClue("blocked tick=$tick from=$before feet=${actor.location} focus=$focus ${h.owner.debug(h.city.id, h.workerId)}") {
                        (before.distance(actor.location) <= .222001) shouldBe true
                        val at = actor.location
                        val half = .3 * actor.scale
                        val overlaps = BoundingBox(at.x - half, at.y, at.z - half,
                            at.x + half, at.y + actor.height, at.z + half)
                            .overlaps(player.boundingBox)
                        if (overlaps) {
                            contact = true
                            h.owner.passingBodies(actor) shouldBe true
                            h.observedWork() shouldBe 0
                            h.deliveries shouldBe 0
                        }
                        citizenPacketBodyClear(h.world, at.point(), false) shouldBe true
                        citizenPacketSupported(h.world, at.point()) shouldBe true
                        if (!citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH)) {
                            h.deliveries shouldBe 0
                            h.observedWork() shouldBe 0
                        }
                        if (h.deliveries > 0) h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                        player.location.x shouldBe 21.2
                        player.velocity shouldBe velocity
                    }
                    h.yielding(h.workerId) shouldBe false
                    val row = (h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>
                    val route = row["route"] as? Map<*, *>
                    if (route?.get("collision_wait") == true) {
                        blockedFrames++
                        route["blocking_resident"] shouldBe null
                    }
                }
                withClue(h.owner.debug(h.city.id, h.workerId)) {
                    (blockedFrames > 0) shouldBe true
                    (nativeDenials > 0) shouldBe true
                    (heldSearchFrames >= 80) shouldBe true
                }
                contact shouldBe true
                h.deliveries shouldBe 1
                h.failedWorkPositions shouldBe emptyList()
                h.city shouldBe originalCity
                h.pendingDelivery.isDone shouldBe false
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                player.location.x shouldBe 21.2
                // This separate, player-initiated overlap happens after the original work receipt was accepted.
                player.teleport(actor.location)
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                h.deliveries shouldBe 1
                h.pendingDelivery.isDone shouldBe false
                player.teleport(Location(h.world, 24.5, 68.0, 4.5))
                for (tick in 1..100) {
                    val before = actor.location
                    h.navigate()
                    (before.distance(actor.location) <= .172001) shouldBe true
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                }
                h.deliveries shouldBe 1
                h.work shouldBe work
                h.city shouldBe originalCity
                h.pendingDelivery.isDone shouldBe false
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            }
        }
    }

    test("five residents walk and pause independently and separate an exact overlap without work or teleport credit") {
        residentRuntime(workerId = UUID.fromString("9d0a2c2a-2abb-4d5b-9150-7659bfb1df84")) { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val ids = listOf(h.workerId, UUID.fromString("06254fa5-4795-4a8e-a7c7-783f9fc90cfd"),
                UUID(0, 101), UUID(0, 102), UUID(0, 103))
            h.city = h.city.copy(workers = ids.mapIndexed { index, id -> CivWorker(id, "Idle $index", null) })
            repeat(3) { h.owner.pulse(2_000L + it * 1_000) }
            h.navigationNow = 4_000
            val city = h.city
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.size shouldBe 5
            fun body(npc: PacketPlayerNpc): BoundingBox = npc.location.let {
                BoundingBox(it.x - .3, it.y, it.z - .3, it.x + .3, it.y + 1.8, it.z + .3)
            }
            fun separated() = ids.indices.all { first -> (first + 1 until ids.size).all { second ->
                !body(actors.getValue(ids[first])).overlaps(body(actors.getValue(ids[second])))
            } }
            separated() shouldBe true // Initial and fallback admission never share a body.
            val same = Location(h.world, 4.5, 64.0, 4.5)
            actors.getValue(ids[0]).teleport(same)
            actors.getValue(ids[1]).teleport(same)
            repeat(20) {
                val previous = actors.mapValues { it.value.location }
                h.navigate()
                actors.forEach { (id, npc) -> (previous.getValue(id).distance(npc.location) <= .118251) shouldBe true }
            }
            h.clearRecordedCalls()
            separated() shouldBe true
            val walked = ids.associateWith { 0.0 }.toMutableMap()
            val moving = ids.associateWith { mutableListOf<Boolean>() }
            val starts = ids.associateWith { linkedSetOf<Long>() }
            var mixedMotionTicks = 0
            for (tick in 1..1_200) {
                val previous = actors.mapValues { it.value.location }
                h.navigate()
                if (tick % 20 == 0) {
                    h.owner.pulse(requireNotNull(h.navigationNow))
                    val entries = h.owner.debug(city.id, null)["entries"] as List<*>
                    val targets = entries.mapNotNull { entry ->
                        val state = entry as Map<*, *>
                        val route = state["route"] as? Map<*, *> ?: return@mapNotNull null
                        starts.getValue(state["worker"] as UUID) += route["started_at"] as Long
                        route["target"] as CitizenPoint
                    }
                    targets.distinct().size shouldBe targets.size
                    h.clearRecordedCalls() // Keep the 60-second behavioral trace, not millions of MockK property-call records.
                }
                var movingNow = 0
                actors.forEach { (id, npc) ->
                    val distance = previous.getValue(id).distance(npc.location)
                    (distance <= .118251) shouldBe true
                    walked[id] = walked.getValue(id) + distance
                    moving.getValue(id) += distance > .000001
                    if (distance > .000001) movingNow++
                }
                if (movingNow in 1..4) mixedMotionTicks++
                withClue("tick=$tick positions=${actors.mapValues { it.value.location }}") {
                    ids.indices.all { a -> (a + 1 until ids.size).all { b ->
                        val first = actors.getValue(ids[a])
                        val second = actors.getValue(ids[b])
                        !body(first).overlaps(body(second)) || h.owner.passingBodies(first) || h.owner.passingBodies(second)
                    } } shouldBe true
                }
            }
            withClue("walked=$walked starts=$starts") {
                walked.values.all { it >= 4.0 } shouldBe true
                starts.values.all { it.size >= 2 } shouldBe true
                moving.values.all { ticks -> ticks.count { it } >= 40 && ticks.count { !it } >= 40 } shouldBe true
                (mixedMotionTicks >= 100) shouldBe true
                (moving.getValue(ids[0]) != moving.getValue(ids[1])) shouldBe true
                (starts.getValue(ids[0]) != starts.getValue(ids[1])) shouldBe true
            }
            h.spawns shouldBe 5
            h.deliveries shouldBe 0
            h.steps shouldBe emptyList()
            h.city shouldBe city
        }
    }

    test("idle neighbours meet without a campfire face each other gesture briefly then resume separate walks") {
        residentRuntime(workerId = UUID(0, 7)) { h ->
            val actors = h.startSocialPair()
            val city = h.city
            h.awaitConversation()
            val start = requireNotNull(h.navigationNow)
            val initial = actors.associate { it.uniqueId to it.location }
            val firstState = h.socialState(actors.first().uniqueId)
            val secondState = h.socialState(actors.last().uniqueId)
            firstState["partner"] shouldBe actors.last().uniqueId
            secondState["partner"] shouldBe actors.first().uniqueId
            (firstState["seat"] != secondState["seat"]) shouldBe true
            val beforeGestures = actors.associate { it.uniqueId to h.gestures.getOrDefault(it.uniqueId, 0) }
            var endedAt: Long? = null
            for (tick in 1..320) {
                h.navigate()
                if (tick % 20 == 0) {
                    h.owner.pulse(requireNotNull(h.navigationNow))
                    h.clearRecordedCalls()
                }
                if (actors.all { h.socialState(it.uniqueId)["visit"] == false }) { endedAt = h.navigationNow; break }
                if (tick >= 20) for ((npc, other) in listOf(actors.first() to actors.last(), actors.last() to actors.first())) {
                    val desired = citizenLookDirection(npc.location.add(0.0, npc.eyeHeight, 0.0),
                        other.location.add(0.0, other.eyeHeight, 0.0)).first
                    (kotlin.math.abs(citizenAngleChange(npc.bodyYaw, desired)) <= 20f) shouldBe true
                    npc.location.toVector() shouldBe initial.getValue(npc.uniqueId).toVector()
                    h.owner.status(npc.uniqueId) shouldBe "workers.status.talking"
                }
            }
            withClue("social=${actors.map { h.socialState(it.uniqueId) }}") {
                (endedAt != null) shouldBe true
                (requireNotNull(endedAt) - start in 7_000L..14_000L) shouldBe true
            }
            actors.forEach { npc -> (h.gestures.getOrDefault(npc.uniqueId, 0) - beforeGestures.getValue(npc.uniqueId) in 1..4) shouldBe true }
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (actors.all { it.location.distanceSquared(initial.getValue(it.uniqueId)) > 1.0 }) break
            }
            withClue("endedAt=$endedAt now=${h.navigationNow} initial=$initial ${h.owner.debug(city.id, null)}") {
                actors.all { it.location.distanceSquared(initial.getValue(it.uniqueId)) > 1.0 } shouldBe true
            }
            actors.all { h.socialState(it.uniqueId)["visit"] == false } shouldBe true
            h.city shouldBe city
            h.deliveries shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("street partners release both visits when work a threat night or departure takes priority") {
        for (interruption in listOf("work", "threat", "night", "departure")) {
            residentRuntime(workerId = UUID(0, 7)) { h ->
                val actors = h.startSocialPair()
                h.awaitConversation()
                when (interruption) {
                    "work" -> {
                        val farm = h.assignFarm(h.workerId)
                        h.city.workers.single { it.id != h.workerId }.assignment shouldBe null
                        h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0), emptyList(),
                            durationMillis = 1_000, status = "working-field")
                    }
                    "threat" -> actors.first().location.let { h.monsterAt(it.x + 1.0, it.z) }
                    "night" -> h.world.time = 13_000
                    "departure" -> h.city = h.city.copy(workers = h.city.workers.filter { it.id != actors.last().uniqueId })
                }
                h.owner.pulse(requireNotNull(h.navigationNow) + 1)
                h.navigate()
                h.owner.pulse(requireNotNull(h.navigationNow))
                val remaining = h.owner.collisionActors()
                withClue(interruption) {
                    remaining.size shouldBe if (interruption == "departure") 1 else 2
                    remaining.all { h.socialState(it.uniqueId)["visit"] == false } shouldBe true
                    remaining.all { h.owner.status(it.uniqueId) !in setOf("workers.status.meeting", "workers.status.talking") } shouldBe true
                    if (interruption == "work") {
                        (h.phase(h.workerId) in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING)) shouldBe true
                        h.phase(h.city.workers.single { it.id != h.workerId }.id) shouldBe CitizenPhase.IDLE
                    }
                    h.deliveries shouldBe 0
                }
            }
        }
    }

    test("an unassigned adult spends most daytime frames walking with short bounded handoffs and no work credit") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val original = h.city
            val actor = h.owner.collisionActors().single()
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            var moving = 0
            var samples = 0
            var gap = 0
            var longestGap = 0
            val waiting = mutableMapOf<String, Int>()
            for (tick in 1..1_400) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                val distance = before.distance(actor.location)
                (distance <= .118251) shouldBe true
                if (tick > 200) {
                    samples++
                    if (distance > .000001) { moving++; gap = 0 } else {
                        gap++
                        val motion = h.routeMotion(h.workerId)
                        val reason = when { motion == null -> "handoff"; motion.searching -> "search";
                            motion.turning -> "turn"; motion.finished -> "finished"; else -> "other" }
                        waiting[reason] = (waiting[reason] ?: 0) + 1
                    }
                    longestGap = maxOf(longestGap, gap)
                }
            }
            withClue("moving=$moving samples=$samples longestStillFrames=$longestGap waiting=$waiting ${h.navigationState()}") {
                (moving.toDouble() / samples >= .75) shouldBe true
                (longestGap <= 40) shouldBe true
            }
            h.city shouldBe original
            h.deliveries shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("unassigned autonomous residents wander from the prepared entrance below a floating hall control") {
        residentRuntime { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val hall = h.city.buildings.first().copy(phase = BuildingPhase.SITE,
                bounds = CivBox(CivPosition(-2, 64, -6), CivPosition(2, 70, -3)),
                control = CivPosition(0, 67, -4))
            h.world.getBlockAt(0, 67, -4).type = Material.BARREL
            h.city = h.city.copy(autonomy = CivAutonomy(), inventory = null, buildings = listOf(hall),
                workers = h.city.workers.map { it.copy(assignment = null, nextDeliveryAt = 2) })
            val city = h.city
            citizenIdleAnchor(city, hall) shouldBe hall.entrance
            citizenIdleAnchor(city.copy(autonomy = null), hall) shouldBe hall.entrance
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.owner.collisionActors().single()
            val origin = actor.location.clone()
            val spawns = h.spawns
            var walked = 0.0
            for (tick in 1..800) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val distance = before.distance(actor.location)
                (distance <= .118251) shouldBe true
                walked += distance
                if (actor.location.distance(origin) >= 2.0) break
            }
            withClue("origin=$origin position=${actor.location} walked=$walked displacement=${actor.location.distance(origin)} ${h.navigationState()}") {
                (walked >= 2.0 - 1e-6) shouldBe true
            }
            // PacketPlayerNpc.teleport also emits ordinary per-tick walking positions;
            // the bounded displacement above excludes a recovery jump.
            h.spawns shouldBe spawns
            h.deliveries shouldBe 0
            h.steps shouldBe emptyList()
            h.city shouldBe city
        }
    }

    test("an unreachable optional wander keeps safe actual feet then tries another destination without recovery teleport") {
        residentRuntime { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            for (x in -8..8) for (z in -8..8) h.world.getBlockAt(x, 65, z).type = Material.STONE
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                for (y in 66..67) h.world.getBlockAt(x, y, z).type = Material.STONE
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, .5, 66.0, .5)
            actor.teleport(origin)
            // A new token cancels the pre-fixture idle route without moving the resident.
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(nextDeliveryAt = 2) })
            val city = h.city
            h.navigationNow = 2_000
            val messages = mutableListOf<String>()
            val handler = object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.owner.pulse(2_000)
                val teleports = h.teleports
                var failedAt: Long? = null
                for (tick in 1..240) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (messages.any { "ARCCIVS_NAVIGATION_FAILED" in it && "reason=no-path" in it } &&
                        h.routeIdentity() == null) {
                        failedAt = h.navigationNow
                        break
                    }
                }
                withClue("position=${actor.location} diagnostics=${messages.takeLast(8)}") {
                    (failedAt != null) shouldBe true
                    actor.location.toVector() shouldBe origin.toVector()
                    h.teleports shouldBe teleports
                }
                // Make the next optional destination reachable; the bounded failure delay still applies.
                for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                    for (y in 66..67) h.world.getBlockAt(x, y, z).type = Material.AIR
                var walked = false
                for (tick in 1..600) {
                    val before = actor.location
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    (before.distance(actor.location) <= .118251) shouldBe true
                    if (requireNotNull(h.navigationNow) < requireNotNull(failedAt) + 2_500)
                        actor.location.toVector() shouldBe origin.toVector()
                    if (actor.location.distance(origin) >= .5) { walked = true; break }
                }
                withClue("position=${actor.location} diagnostics=${messages.takeLast(8)}") { walked shouldBe true }
                h.city shouldBe city
                h.deliveries shouldBe 0
                h.steps shouldBe emptyList()
            } finally { h.logger.removeHandler(handler) }
        }
    }

    test("optional wander safety uses exact lowered feet and retains recovery for hazards void and displacement") {
        residentRuntime { h ->
            val anchor = CivPosition(0, 64, 0)
            val at = CitizenPoint(2.5, 64.0, 2.5)
            citizenSafeIdlePosition(h.world, anchor, at) shouldBe true
            citizenSafeIdlePosition(h.world, anchor, at.copy(x = 9.5)) shouldBe false
            h.world.getBlockAt(2, 63, 2).type = Material.MAGMA_BLOCK
            citizenSafeIdlePosition(h.world, anchor, at) shouldBe false
            h.world.getBlockAt(2, 63, 2).type = Material.AIR
            citizenSafeIdlePosition(h.world, anchor, at) shouldBe false
            h.world.getBlockAt(2, 63, 2).type = Material.DIRT_PATH
            val lowered = at.copy(y = 63.9375)
            citizenStandable(h.world, CivPosition(2, 63, 2)) shouldBe false
            citizenSafeIdlePosition(h.world, anchor, lowered) shouldBe true
            h.world.getBlockAt(2, 64, 2).type = Material.STONE
            citizenSafeIdlePosition(h.world, anchor, lowered) shouldBe false
        }
    }

    test("idle wandering stays slow and never wears roads even on a faster surface") {
        residentRuntime { h ->
            // Negative wander offsets cross chunk zero and need the route's extra loaded margin.
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            h.surfaceMultiplier = 1.25
            val actor = h.owner.collisionActors().single()
            var walked = 0.0
            for (tick in 0 until 512) {
                // Coarse pulses retry a target rejected before this test loaded its margin.
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                val before = actor.location
                h.navigate()
                val distance = before.distance(actor.location)
                (distance <= .118251) shouldBe true
                walked += distance
                if (walked >= .5) break
            }
            (walked >= .5) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.walking"
            h.steps shouldBe emptyList()
            h.deliveries shouldBe 0
        }
    }

    test("a courier keeps its building approach while the producer moves and disappears") {
        residentRuntime { h ->
            val (farm, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "searching-work"
            val producer = h.owner.collisionActors().single()
            producer.teleport(Location(h.world, 30.5, 64.0, .5))
            h.owner.pulse(2_000)
            val accepted = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == farm.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            h.courierProvider = { _, worker -> accepted.takeIf { worker.id == courierId } }
            h.holdNavigation = true
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            courier.teleport(Location(h.world, 10.5, 64.0, .5))
            h.owner.pulse(3_000)
            val first = requireNotNull(h.routeIdentity(courierId))
            val target = h.routeTarget(courierId)
            producer.teleport(Location(h.world, 25.5, 64.0, .5))
            h.owner.pulse(4_000)
            h.routeIdentity(courierId) shouldBe first
            h.routeTarget(courierId) shouldBe target
            h.city = h.city.copy(workers = h.city.workers.filterNot { it.id == h.workerId })
            h.owner.pulse(5_000)
            h.routeIdentity(courierId) shouldBe first
            h.routeTarget(courierId) shouldBe target
            h.owner.courierSupplySources(h.city.id) shouldBe emptyMap()
            h.owner.courierSources(h.city.id, courierId) shouldBe emptyMap()
            h.pickups shouldBe 0
            h.city.workers.single().cargo shouldBe null
        }
    }

    test("a building courier retries storage-busy only after the five-second pickup delay") {
        residentRuntime { h ->
            val (farm, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "searching-work"
            h.owner.pulse(2_000)
            val accepted = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == farm.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            h.courierProvider = { _, worker -> accepted.takeIf { worker.id == courierId } }
            h.onPickup = { cityId, workerId, _ -> h.owner.requireStorageArrival(cityId, workerId, farm.id) }
            h.owner.pulse(3_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            courier.teleport(Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(4_000); h.owner.pulse(5_000); h.owner.pulse(6_000)
            h.pickups shouldBe 1
            h.pendingPickup.completeExceptionally(CivsRejection("storage-busy"))
            h.owner.pulse(6_100)
            h.phase(courierId) shouldBe CitizenPhase.PAUSED
            h.owner.pulse(11_099)
            h.pickups shouldBe 1
            h.owner.pulse(11_100)
            h.pickups shouldBe 2
            h.pickedCargo shouldBe accepted.cargo
            h.city.workers.last().cargo shouldBe null
        }
    }

    test("a working producer accumulates small output before returning a stack to its own building") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            fun bag(amount: Int) = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_HOE", 1)
                CivsEquipment.BAG -> CivResidentItem("COAL", amount)
                else -> null
            } }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(1)) })
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0),
                listOf(CivPlanBlock(CivPosition(23, 63, 0), "minecraft:stone", "minecraft:air")),
                outputs = mapOf("WHEAT" to 1L), durationMillis = 30_000, status = "working-field")
            var requests = 0
            h.owner.returnResidentOutputs = { _, _, buildingId ->
                buildingId shouldBe farm.id
                requests++
                CompletableFuture()
            }
            h.owner.pulse(2_000)
            requests shouldBe 0
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(64)) })
            h.owner.pulse(3_000)
            requests shouldBe 1
            h.owner.courierSupplySources(h.city.id) shouldBe emptyMap()
            h.deliveries shouldBe 0
            CivsEquipment.plainCount(h.city.workers.single(), "COAL") shouldBe 64
        }
    }

    test("a courier unloads once then walks back to its warehouse and waits beside the real barrel") {
        residentRuntime { h ->
            val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
            val receiver = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.SAWMILL, schematicId = "sawmill1",
                anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0),
                bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
            h.world.getBlockAt(8, 64, 0).type = Material.BARREL
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, receiver.id, warehouse.entrance, "OAK_LOG", 8)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, receiver),
                workers = h.city.workers.map { it.copy(cargo = cargo) }))
            h.courier = CivCourierPlan(cargo, receiver.id, receiver.entrance, true)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 9.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.owner.pulse(3_000)
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe cargo
            h.city = h.city.copy(revision = h.city.revision + 1,
                workers = h.city.workers.map { it.copy(cargo = null, completedCycles = 1) })
            h.courier = null
            h.pendingUnload.complete(h.city)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-storage"
            var normalSteps = 0
            var normalDistance = 0.0
            var fasterDistance = 0.0
            val messages = mutableListOf<String>()
            val frames = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            val storageAt = requireNotNull(warehouse.control).let { Location(h.world, it.x + .5, it.y.toDouble(), it.z + .5) }
            // Four times the direct walking time plus 128 planning/turning frames is
            // ample for this short open-floor trip and fails before runaway mock history.
            val frameLimit = 128 + 4 * kotlin.math.ceil(actor.location.distance(storageAt) / .172).toInt()
            h.logger.addHandler(handler)
            try {
                for (tick in 1..frameLimit) {
                    val before = actor.location
                    h.navigate()
                    if (tick % 20 == 0) {
                        h.owner.pulse(4_000)
                        frames += "$tick:${actor.location}:${h.owner.status(h.workerId)}"
                    }
                    val distance = before.distance(actor.location)
                    (distance <= .172 * h.surfaceMultiplier + .000001) shouldBe true
                    if (h.surfaceMultiplier == 1.0) {
                        normalDistance = maxOf(normalDistance, distance)
                        if (distance > .000001 && ++normalSteps == 5) h.surfaceMultiplier = 1.25
                    } else fasterDistance = maxOf(fasterDistance, distance)
                    if (h.owner.status(h.workerId) == "workers.status.waiting-orders") break
                }
            } finally { h.logger.removeHandler(handler) }
            withClue("limit=$frameLimit status=${h.owner.status(h.workerId)} location=${actor.location} " +
                "planner=${h.navigationState()} frames=${frames.takeLast(8)} diagnostics=${messages.takeLast(4)}") {
                h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            }
            (kotlin.math.abs(normalDistance - .172) < .000001) shouldBe true
            (kotlin.math.abs(fasterDistance - .215) < .000001) shouldBe true
            val atWarehouse = actor.location
            citizenAtStorage(h.world, CitizenPoint(atWarehouse.x, atWarehouse.y, atWarehouse.z), requireNotNull(warehouse.control)) shouldBe true
            citizenCanReachBlock(actor, requireNotNull(warehouse.control)) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            h.steps.isNotEmpty() shouldBe true
            val workSteps = h.steps.size
            repeat(80) { tick ->
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(5_000L + tick * 50)
                actor.location shouldBe atWarehouse
            }
            h.steps.size shouldBe workSteps
            h.unloads shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 1
            h.city.workers.single().cargo shouldBe null
            h.spawns shouldBe 1
        }
    }

    test("summoned farmer starts a supplied work plan at the barrel and keeps its delivery receipt fenced") {
        residentRuntime { h ->
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
                "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)),
                CivPosition(20, 64, 0), BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
            for (z in -1..1) h.world.loadChunk(2, z) // Loaded search margin east of the farm's chunk.
            h.world.getBlockAt(20, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + farm,
                workers = h.city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
                inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.owner.pulse(2_000) // A waiting farmer may already have an idle route from the former position.
            h.owner.activeCount() shouldBe 1
            val actor = h.owner.collisionActors().single()
            val previous = actor.location
            h.owner.summon(h.city, farm.id) shouldBe true
            val summoned = actor.location
            (previous.distance(summoned) > 8.0) shouldBe true
            h.deliveries shouldBe 0

            // Publication of supplied seeds makes a new field plan available on the next pulse.
            h.city = h.city.copy(revision = h.city.revision + 1,
                inventory = requireNotNull(h.city.inventory).copy(buildingMaterials = mapOf(farm.id.toString() to mapOf("WHEAT_SEEDS" to 1L))))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), focus = focus,
                inputs = mapOf("WHEAT_SEEDS" to 1L), durationMillis = 1_500, status = "working-field")
            h.owner.pulse(3_000)
            actor.location shouldBe summoned
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(3_000L + tick * 50)
                // A fresh plan walks from the summon, without a stale route or recovery jump.
                (before.distance(actor.location) <= .215001) shouldBe true
                (actor.location.x >= summoned.x) shouldBe true
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            (actor.location.x > summoned.x) shouldBe true
            h.spawns shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city.workers.single().completedCycles shouldBe 0
            h.city.inventory?.buildingStock(farm.id) shouldBe mapOf("WHEAT_SEEDS" to 1L)
            h.pendingDelivery.isDone shouldBe false
            h.owner.summon(h.city, farm.id) shouldBe false // Never abandon an admitted receipt.
            h.owner.pulse(12_000)
            h.deliveries shouldBe 1
        }
    }

    // Native Auto scenery and its separate observed economic session.
    test("courier loss sends a full saved queue to the actual depot without new work or reward even if a courier returns") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val actor = h.owner.collisionActors().single()
            val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                schematicId = "warehouse1", production = null, anchor = CivPosition(10, 64, 0),
                entrance = CivPosition(10, 64, -1), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(stock = Stock(), buildings = h.city.buildings + warehouse,
                workers = h.city.workers.map { it.copy(pendingStock = Stock(food = 384), nextDeliveryAt = 999_999) }))
            val original = h.city.workers.single()
            val first = h.autoCrop(farm, 0, duration = 5_000)
            actor.teleport(Location(h.world, first.standing.x + .5, first.standing.y.toDouble(), first.standing.z + .5))
            val origin = actor.location
            (origin.distance(Location(h.world, warehouse.entrance.x + .5, warehouse.entrance.y.toDouble(),
                warehouse.entrance.z + .5)) > 8.0) shouldBe true
            val token = CitizenToken(farm.id, original.completedCycles, original.nextDeliveryAt, null)
            var committed: Settlement? = null
            val pending = CompletableFuture<Settlement>()
            h.workerDelivery = { workerId, deliveredToken ->
                workerId shouldBe original.id
                deliveredToken shouldBe token
                h.owner.autonomousVisualWork(h.city.id, workerId) shouldBe null
                val delivered = h.owner.requirePendingStockDelivery(h.city.id, workerId, token)
                delivered shouldBe CivPendingStockDelivery(Stock(food = 384), warehouse.id)
                citizenNearEndpoint(CitizenPoint(actor.location.x, actor.location.y, actor.location.z), warehouse.entrance) shouldBe true
                committed = CivsAutonomousLogistics.unloadPending(h.city, workerId, farm.id, token.completedCycles,
                    token.nextDeliveryAt, delivered.stock, requireNotNull(h.navigationNow), delivered.destinationBuildingId)
                pending
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.RETURNING
            shouldThrow<CivsRejection> { h.owner.requirePendingStockDelivery(h.city.id, h.workerId, token) }
                .reason shouldBe "route-unreachable"
            var walked = 0.0
            for (tick in 1..3_000) {
                val before = actor.location
                h.navigate()
                val step = before.distance(actor.location)
                (step <= .500001) shouldBe true
                walked += step
                if (tick == 10) h.city = CivsRules.validate(h.city.copy(workers = h.city.workers +
                    CivWorker(UUID(0, 43), "Replacement courier", warehouse.id, nextDeliveryAt = 1)))
                val now = requireNotNull(h.navigationNow)
                if (tick % 20 == 0) { h.owner.pulse(now); h.clearRecordedCalls() }
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (h.deliveries == 1) break
            }
            withClue(h.navigationState()) { h.deliveries shouldBe 1 }
            (walked > 8.0) shouldBe true
            h.autonomousObservedMillis() shouldBe 0L
            h.owner.courierSupplySources(h.city.id).containsKey(h.workerId) shouldBe false
            h.city.stock shouldBe Stock()
            h.city.workers.first() shouldBe original
            val after = requireNotNull(committed)
            after.stock shouldBe Stock(food = 384)
            after.workers.first().pendingStock shouldBe null
            after.workers.first().completedCycles shouldBe original.completedCycles
            after.workers.first().skills shouldBe original.skills
            after.workers.first().items shouldBe original.items
            h.city = after
            h.work = null
            pending.complete(after) shouldBe true
            pending.complete(after) shouldBe false
            shouldThrow<CivsRejection> { CivsAutonomousLogistics.unloadPending(after, original.id, farm.id,
                token.completedCycles, token.nextDeliveryAt, original.pendingStock!!, requireNotNull(h.navigationNow)) }
                .reason shouldBe "stale-delivery"
        }
    }

    test("a staffed Auto courier lets real crop work queue two sessions at the field without a hall trip") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val producer = h.city.workers.single()
            val actor = h.owner.collisionActors().single()
            val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                schematicId = "warehouse1", production = null, anchor = CivPosition(10, 64, 0),
                entrance = CivPosition(10, 64, -1), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + warehouse,
                workers = h.city.workers + CivWorker(UUID(0, 43), "Courier", warehouse.id, nextDeliveryAt = 1)))
            val initial = h.city
            val first = h.autoCrop(farm, 0, duration = 5_000)
            actor.teleport(Location(h.world, first.standing.x + .5, first.standing.y.toDouble(), first.standing.z + .5))
            var native = 0
            var earned = 0
            var lastWorkedAt = first.standing
            h.workerDelivery = { workerId, token ->
                workerId shouldBe producer.id
                val visual = h.owner.autonomousVisualWork(h.city.id, workerId)
                if (visual != null) {
                    h.owner.requireWorldWorkArrival(h.city.id, workerId, visual)
                    h.city = CivsRules.finishAutonomousVisual(h.city, visual)
                    visual.changes.forEach { cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                        .blockData = Bukkit.createBlockData(cell.after) }
                    lastWorkedAt = visual.standing
                    native++
                    h.autoCrop(farm, native, duration = 5_000)
                } else {
                    h.owner.requireAutonomousObservedSession(h.city.id, workerId)
                    val observed = h.owner.requireAutonomousDelivery(h.city.id, workerId, token)
                    observed.completeAtWorkplace shouldBe true
                    observed.workplace shouldBe lastWorkedAt
                    citizenNearEndpoint(CitizenPoint(actor.location.x, actor.location.y, actor.location.z), lastWorkedAt) shouldBe true
                    h.city = CivsRules.deliver(h.city, h.city.owner, workerId, farm.id,
                        token.completedCycles, token.nextDeliveryAt, requireNotNull(h.navigationNow))
                    earned++
                    h.city.stock shouldBe initial.stock
                    h.city.workers.first().pendingStock shouldBe Stock(food = earned * 3L)
                    h.autoCrop(farm, native, duration = 5_000)
                }
                CompletableFuture.completedFuture(h.city)
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            for (tick in 1..6_000) {
                val before = actor.location
                h.navigate()
                (before.distance(actor.location) <= .500001) shouldBe true
                val now = requireNotNull(h.navigationNow)
                if (tick % 20 == 0) { h.owner.pulse(now); h.clearRecordedCalls() }
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (earned == 2) break
            }
            withClue(h.navigationState()) { earned shouldBe 2 }
            h.city.workers.first().completedCycles shouldBe producer.completedCycles + 2
            h.city.workers.first().skills!!["FARM"] shouldBe 5L
            h.city.workers.first().items shouldBe producer.items
            h.city.inventory shouldBe null
        }
    }

    test("canonical Auto crop receipts preserve the worker token then return sixty observed seconds to the real depot once") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val original = h.city
            val worker = original.workers.single()
            val actor = h.owner.collisionActors().single()
            val first = h.autoCrop(farm, 0, duration = 5_000)
            actor.teleport(Location(h.world, first.standing.x - 6.5, first.standing.y.toDouble(), first.standing.z + .5))
            citizenCanReachBlock(actor, requireNotNull(first.focus)) shouldBe false
            var previousPosition = actor.location
            var walked = 0.0
            fun observeMovement() {
                val actual = actor.location
                val distance = previousPosition.distance(actual)
                withClue("from=$previousPosition to=$actual ${h.navigationState()}") {
                    (distance <= .500001) shouldBe true
                }
                walked += distance
                previousPosition = actual
            }
            var nativeOffers = 0
            var nativeAcks = 0
            var economicOffers = 0
            var returnOrigin: Location? = null
            data class NativeAck(val before: Settlement, val work: CivWorldWork,
                val future: CompletableFuture<Settlement>, val at: Long, val observed: Long)
            var pendingNative: NativeAck? = null
            var pendingEconomic: CompletableFuture<Settlement>? = null
            var economicAfter: Settlement? = null
            var economicAt = 0L
            h.workerDelivery = { workerId, token ->
                val at = requireNotNull(h.navigationNow)
                val visual = h.owner.autonomousVisualWork(h.city.id, workerId)
                if (visual != null) {
                    nativeOffers++
                    token shouldBe CitizenToken(farm.id, worker.completedCycles, worker.nextDeliveryAt, null)
                    h.owner.requireWorldWorkArrival(h.city.id, workerId, visual)
                    CivsAutonomousWorksites.validate(h.city, visual)
                    h.autonomousObservedMillis() shouldBe nativeOffers * 5_000L
                    if (nativeOffers == 1) (at < worker.nextDeliveryAt) shouldBe true
                    CompletableFuture<Settlement>().also {
                        pendingNative = NativeAck(h.city, visual, it, at, h.autonomousObservedMillis())
                    }
                } else {
                    economicOffers++
                    h.owner.requireAutonomousObservedSession(h.city.id, workerId)
                    val observed = h.owner.requireAutonomousDelivery(h.city.id, workerId, token)
                    observed.storage shouldBe original.buildings.first { it.type == BuildingType.TOWN_HALL }.entrance
                    citizenNearEndpoint(CitizenPoint(actor.location.x, actor.location.y, actor.location.z), observed.storage) shouldBe true
                    (requireNotNull(returnOrigin).distance(actor.location) > 8.0) shouldBe true
                    h.autonomousObservedMillis() shouldBe 60_000L
                    economicAt = at
                    economicAfter = CivsRules.deliver(h.city, h.city.owner, workerId, farm.id,
                        token.completedCycles, token.nextDeliveryAt, at)
                    CompletableFuture<Settlement>().also { pendingEconomic = it }
                }
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            observeMovement()
            h.phase() shouldBe CitizenPhase.TO_WORK
            h.autonomousObservedMillis() shouldBe 0L
            for (tick in 1..4_000) {
                h.navigate()
                val now = requireNotNull(h.navigationNow)
                if (tick % 20 == 0) h.owner.pulse(now)
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                pendingNative?.let { ack ->
                    // Native work is complete, but pending durability cannot count as more observed work.
                    h.city shouldBe ack.before
                    h.autonomousObservedMillis() shouldBe ack.observed
                    val delay = if (nativeAcks == 0) 10_000L else 250L
                    if (now - ack.at >= delay) {
                        h.owner.requireWorldWorkArrival(h.city.id, h.workerId, ack.work)
                        val after = CivsRules.finishAutonomousVisual(h.city, ack.work)
                        ack.work.changes.forEach { cell ->
                            h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                            h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
                        }
                        h.city = after
                        h.city.workers shouldBe original.workers
                        h.city.stock shouldBe original.stock
                        h.city.autonomy shouldBe original.autonomy
                        nativeAcks++
                        h.city.revision shouldBe original.revision + nativeAcks
                        ack.future.complete(after) shouldBe true
                        ack.future.complete(after) shouldBe false
                        pendingNative = null
                        if (nativeAcks < 12) h.autoCrop(farm, nativeAcks, duration = 5_000)
                        else {
                            h.work = null
                            returnOrigin = actor.location
                            shouldThrow<CivsRejection> {
                                h.owner.requireAutonomousDelivery(h.city.id, h.workerId,
                                    CitizenToken(farm.id, worker.completedCycles, worker.nextDeliveryAt, null))
                            }
                        }
                    }
                }
                observeMovement()
                if (pendingEconomic != null) break
            }
            withClue("native=$nativeAcks economic=$economicOffers ${h.navigationState()}") {
                nativeAcks shouldBe 12
                nativeOffers shouldBe 12
                economicOffers shouldBe 1
            }
            h.city.workers shouldBe original.workers
            h.city.stock shouldBe original.stock
            (walked > 20.0) shouldBe true
            h.steps.isNotEmpty() shouldBe true
            repeat(80) { tick ->
                h.navigate()
                if ((tick + 1) % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if ((tick + 1) % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                observeMovement()
                economicOffers shouldBe 1
                h.city.workers shouldBe original.workers
            }
            val committed = requireNotNull(economicAfter)
            h.city = committed
            requireNotNull(pendingEconomic).complete(committed) shouldBe true
            h.owner.pulse(requireNotNull(h.navigationNow))
            observeMovement()
            h.city.workers.single().completedCycles shouldBe worker.completedCycles + 1
            h.city.workers.single().nextDeliveryAt shouldBe economicAt + CivsRules.deliveryInterval(farm.type)
            h.city.workers.single().skills shouldBe mapOf("FARM" to 4L)
            h.city.workers.single().items shouldBe worker.items
            h.city.stock shouldBe original.stock.copy(food = original.stock.food + CivsRules.deliveryAmount(farm))
            h.city.autonomy shouldBe original.autonomy
            h.city.revision shouldBe original.revision + nativeAcks + 1
            requireNotNull(pendingEconomic).complete(committed) shouldBe false
            repeat(20) {
                h.navigate()
                h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                observeMovement()
            }
            economicOffers shouldBe 1
            h.autonomousObservedMillis() shouldBe 0L
        }
    }

    test("a completed native Auto receipt drops a search placeholder once the field scan confirms growth") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val original = h.city
            val work = h.autoCrop(farm, 0, duration = 5_000)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            for (now in 3_000L..7_000L step 1_000) h.owner.pulse(now)
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.autonomousObservedMillis() shouldBe 5_000L
            h.deliveries shouldBe 1
            work.changes.forEach { cell ->
                h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
            }
            h.city = CivsRules.finishAutonomousVisual(h.city, work)
            h.work = null
            h.pendingDelivery.complete(h.city) shouldBe true
            h.owner.pulse(7_250)
            h.phase() shouldBe CitizenPhase.IDLE
            // The previous action is displayed for at most twenty native ticks after its receipt.
            h.navigationNow = 7_250
            repeat(21) { h.navigate() }
            h.owner.pulse(requireNotNull(h.navigationNow))
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"

            h.idleReason = "waiting-growth"
            h.owner.pulse(requireNotNull(h.navigationNow) + 250)
            (h.owner.status(h.workerId) == "workers.status.searching-work") shouldBe false
            h.autonomousObservedMillis() shouldBe 5_000L
            shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(h.city.id, h.workerId) }
            h.city.stock shouldBe original.stock
            h.city.workers shouldBe original.workers
            h.deliveries shouldBe 1
        }
    }

    test("a completed native Auto session waits for full storage without another native or economic receipt") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            h.city = h.city.copy(stock = h.city.stock.copy(food = CivsRules.capacity(h.city)))
            val original = h.city
            val work = h.autoCrop(farm, 0, duration = 60_000)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            for (now in 3_000L..62_000L step 1_000) h.owner.pulse(now)
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.autonomousObservedMillis() shouldBe 60_000L
            h.deliveries shouldBe 1
            work.changes.forEach { cell ->
                h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
            }
            h.city = CivsRules.finishAutonomousVisual(h.city, work)
            h.work = null
            h.pendingDelivery.complete(h.city) shouldBe true
            h.owner.pulse(62_250)
            h.phase() shouldBe CitizenPhase.RETURNING
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            // Both discovery states must yield to a completed session's conserved storage limit.
            h.navigationNow = 62_250
            for (reason in listOf("searching-work", "waiting-growth")) {
                h.idleReason = reason
                repeat(21) { h.navigate() }
                h.owner.pulse(requireNotNull(h.navigationNow))
                h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
                h.autonomousObservedMillis() shouldBe 60_000L
                h.city.stock shouldBe original.stock
                h.city.workers shouldBe original.workers
                h.deliveries shouldBe 1
            }
        }
    }

    test("Auto observation ignores wall-clock gaps pause night and unreachable work and fences changed residents") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val work = h.autoCrop(farm, 0, duration = 60_000)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.pulse(3_000)
            h.autonomousObservedMillis() shouldBe 1_000L
            h.owner.pulse(303_000)
            h.autonomousObservedMillis() shouldBe 1_000L
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(paused = true))
            for (now in 304_000L..313_000L step 1_000) h.owner.pulse(now)
            h.autonomousObservedMillis() shouldBe 1_000L
            shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(h.city.id, h.workerId) }
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(paused = false))
            h.world.time = 13_000
            for (now in 314_000L..323_000L step 1_000) h.owner.pulse(now)
            h.autonomousObservedMillis() shouldBe 1_000L
            h.world.time = 0
            actor.teleport(Location(h.world, work.standing.x - 8.5, work.standing.y.toDouble(), work.standing.z + .5))
            h.holdNavigation = true
            for (now in 324_000L..353_000L step 1_000) h.owner.pulse(now)
            h.autonomousObservedMillis() shouldBe 1_000L
            h.deliveries shouldBe 0
            shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(h.city.id, h.workerId) }
            actor.teleport(Location(h.world, work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
            var now = 354_000L
            while (h.autonomousObservedMillis() < 60_000L && now <= 420_000L) {
                h.owner.pulse(now)
                now += 1_000
            }
            withClue(h.navigationState()) { h.autonomousObservedMillis() shouldBe 60_000L }
            h.owner.requireAutonomousObservedSession(h.city.id, h.workerId)
            val valid = h.city
            val worker = valid.workers.single()
            val wrongBuilding = farm.copy(entrance = farm.entrance.copy(x = farm.entrance.x + 1))
            val changed = listOf(
                valid.copy(workers = listOf(worker.copy(completedCycles = worker.completedCycles + 1))),
                valid.copy(workers = listOf(worker.copy(nextDeliveryAt = worker.nextDeliveryAt + 1))),
                valid.copy(workers = listOf(worker.copy(assignment = null))),
                valid.copy(workers = emptyList()),
                valid.copy(buildings = valid.buildings.map { if (it.id == farm.id) wrongBuilding else it }),
            )
            for (city in changed) {
                h.city = city
                shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(city.id, h.workerId) }
            }
            h.city = valid
            h.owner.requireAutonomousObservedSession(valid.id, h.workerId)
            // Once the owner reconciles a changed token, restoring old saved data cannot resurrect its runtime proof.
            h.city = valid.copy(workers = listOf(worker.copy(nextDeliveryAt = worker.nextDeliveryAt + 1)))
            h.work = null
            h.owner.pulse(now)
            h.autonomousObservedMillis() shouldBe 0L
            h.city = valid
            h.owner.pulse(now + 1_000)
            h.autonomousObservedMillis() shouldBe 0L
            shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(valid.id, h.workerId) }
            h.city = valid.copy(workers = emptyList())
            h.owner.pulse(now + 2_000)
            h.city = valid
            h.owner.pulse(now + 3_000)
            h.autonomousObservedMillis() shouldBe 0L
            shouldThrow<CivsRejection> { h.owner.requireAutonomousObservedSession(valid.id, h.workerId) }
            h.viewing = false
            h.owner.pulse(now + 4_000)
            h.viewing = true
            h.owner.pulse(now + 5_000)
            h.autonomousObservedMillis() shouldBe 0L
            h.city.workers shouldBe valid.workers
            h.city.stock shouldBe valid.stock
        }
    }

    test("an identical freshly selected Auto crop action starts again after its native receipt") {
        residentRuntime { h ->
            val farm = h.assignCanonicalAutoFarm()
            val work = h.autoCrop(farm, 0, duration = 1_000)
            val original = h.city
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
            val journals = mutableListOf<CompletableFuture<Settlement>>()
            h.workerDelivery = { workerId, _ ->
                h.owner.autonomousVisualWork(h.city.id, workerId) shouldBe work
                h.owner.requireWorldWorkArrival(h.city.id, workerId, work)
                CompletableFuture<Settlement>().also(journals::add)
            }
            h.owner.pulse(2_000)
            h.owner.pulse(3_000)
            journals.size shouldBe 1
            work.changes.forEach { cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after) }
            h.city = CivsRules.finishAutonomousVisual(h.city, work)
            journals.first().complete(h.city) shouldBe true
            // A new native event makes the same action eligible; the planner returns fresh, equal data.
            work.changes.forEach { cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.before) }
            h.work = work.copy()
            h.owner.pulse(3_250)
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.pulse(4_250)
            journals.size shouldBe 2
            h.autonomousObservedMillis() shouldBe 2_000L
            h.city.workers shouldBe original.workers
            h.city.stock shouldBe original.stock
            h.city.revision shouldBe original.revision + 1
            h.deliveries shouldBe 2
        }
    }

    test("reachable work stays at the observed position faces the real block and rechecks LOS at receipt admission") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val observed = Location(h.world, 22.5, 64.0, .5)
            actor.teleport(observed)
            val focus = CivPosition(23, 63, 0)
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.work = work
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            repeat(20) { h.navigate() }
            actor.location.toVector() shouldBe observed.toVector()
            verify(atLeast = 1) { actor.swingMainHand() }
            verify(atLeast = 1) { actor.pitch = match { it > 45f } }
            h.owner.pulse(3_499)
            h.deliveries shouldBe 0
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            actor.location.toVector() shouldBe observed.toVector()
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)

            // A city wait must not let an already admitted action work through a newly placed wall.
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.world.getBlockAt(23, 64, 0).type = Material.AIR
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(Location(h.world, 27.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an unsupported worker cannot begin or admit work merely because its hand reaches the block") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.5, .5))
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            citizenCanReachBlock(actor, focus) shouldBe true
            actor.location.let { citizenPacketSupported(h.world, CitizenPoint(it.x, it.y, it.z)) } shouldBe false
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            withClue(h.navigationState()) { h.phase() shouldBe CitizenPhase.TO_WORK }
            h.deliveries shouldBe 0
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work)) }
            for (tick in 1..300) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (h.phase() == CitizenPhase.WORKING) break
            }
            withClue(h.navigationState()) { h.phase() shouldBe CitizenPhase.WORKING }
            actor.location.let { citizenPacketSupported(h.world, CitizenPoint(it.x, it.y, it.z)) } shouldBe true
            h.deliveries shouldBe 0
        }
    }

    test("field care stays at its crop and emits one contact cue only after its durable receipt") {
        for ((status, prop) in listOf("watering" to Material.WATER_BUCKET, "fertilizing" to Material.BONE_MEAL)) {
            residentRuntime { h ->
                val work = h.beginCare(status)
                val actor = h.owner.collisionActors().single()
                val observed = actor.location
                val items = h.city.workers.single().items
                h.displayedHand shouldBe prop
                h.owner.status(h.workerId) shouldBe "workers.status.$status"
                verify(atLeast = 1) { actor.swingMainHand() }
                repeat(20) {
                    val before = actor.location
                    h.navigate()
                    val after = actor.location
                    after.toVector() shouldBe observed.toVector()
                    (kotlin.math.abs(citizenAngleChange(before.yaw, after.yaw)) <= 8.0001f) shouldBe true
                    (kotlin.math.abs(after.pitch - before.pitch) <= 8.0001f) shouldBe true
                    h.deliveries shouldBe 0
                }
                verify(atLeast = 1) { actor.pitch = match { it > 0f } }
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                // The cycle counts observed work, never a single four-second gap between pulses.
                for (at in 3_000L..5_000L step 1_000L) {
                    h.owner.pulse(at)
                    h.deliveries shouldBe 0
                }
                h.owner.pulse(6_000)
                h.deliveries shouldBe 1
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                actor.location.toVector() shouldBe observed.toVector()
                h.owner.pulse(7_000) // A delayed receipt must not masquerade as a completed watering.
                h.city.workers.single().items shouldBe items
                h.displayedHand shouldBe prop
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                h.commitWork()
                h.owner.pulse(8_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles.single().let { (particle, count, at) ->
                    particle shouldBe if (status == "watering") Particle.SPLASH else Particle.HAPPY_VILLAGER
                    count shouldBe if (status == "watering") 8 else 6
                    at.x shouldBe requireNotNull(work.focus).x + .5
                    at.z shouldBe requireNotNull(work.focus).z + .5
                    (at.y > requireNotNull(work.focus).y && at.y < requireNotNull(work.focus).y + .5) shouldBe true
                }
                h.world.sounds.single() shouldBe Triple(
                    if (status == "watering") Sound.ITEM_BUCKET_EMPTY else Sound.ITEM_BONE_MEAL_USE, .35f, 1.1f)
                repeat(3) { h.owner.pulse(9_000L + it * 1_000) }
                h.world.particles.size shouldBe 1
                h.world.sounds.size shouldBe 1
            }
        }
    }

    test("failed injured and retired care restores the true hand without a success cue") {
        for (outcome in listOf("rejected", "injured", "retired")) {
            residentRuntime { h ->
                h.beginCare("watering")
                for (at in 3_000L..6_000L step 1_000L) h.owner.pulse(at)
                h.deliveries shouldBe 1
                h.displayedHand shouldBe Material.WATER_BUCKET
                when (outcome) {
                    "rejected" -> h.pendingDelivery.completeExceptionally(CivsRejection("stale-worksite"))
                    "injured" -> { h.hit(2.0); h.commitWork() }
                    else -> { h.viewing = false; h.owner.pulse(6_500); h.commitWork() }
                }
                h.owner.pulse(7_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
            }
        }
    }

    test("an out-of-reach worker approaches only until the actual block becomes reachable") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.navigate()
                (before.distance(actor.location) <= .215001) shouldBe true
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            citizenCanReachBlock(actor, focus) shouldBe true
            (actor.location.x > 21.5 && actor.location.x < 24.0) shouldBe true
            (actor.location.distance(Location(h.world, 24.5, 64.0, .5)) > .65) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
        }
    }

    test("a worker already at another side of its barrel does not route to the preferred side") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val otherSide = Location(h.world, 19.5, 64.0, .5)
            actor.teleport(otherSide)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            repeat(20) { h.navigate() }
            actor.location.toVector() shouldBe otherSide.toVector()
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location.toVector() shouldBe otherSide.toVector()
        }
    }

    test("storage approach stops at the first usable side between coarse work pulses") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            val control = requireNotNull(farm.control)
            // The shared 1 ms search slice has no fixed operations-per-callback guarantee.
            // Hold coarse time still and observe the first usable side, without timeout recovery.
            for (tick in 1..2_000) {
                val before = actor.location
                h.navigate()
                val at = actor.location
                (before.distance(at) <= .215001) shouldBe true
                if (citizenAtStorage(h.world, CitizenPoint(at.x, at.y, at.z), control) &&
                    citizenCanReachBlock(actor, control)) break
            }
            val arrival = actor.location
            withClue("arrival=$arrival status=${h.owner.status(h.workerId)}") {
                citizenAtStorage(h.world, CitizenPoint(arrival.x, arrival.y, arrival.z), control) shouldBe true
                citizenCanReachBlock(actor, control) shouldBe true
            }
            (arrival.x < 20.0) shouldBe true
            repeat(40) { h.navigate() }
            actor.location shouldBe arrival
            h.deliveries shouldBe 0
            h.owner.pulse(3_000)
            h.owner.pulse(4_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe arrival
        }
    }

    test("an idle Auto builder walks to road upkeep and waits for its exact native work receipt") {
        residentRuntime { h ->
            val work = h.assignRoadUpkeep()
            val floor = requireNotNull(work.upkeep).floor
            val actor = h.owner.collisionActors().single()
            val spawns = h.spawns
            var walked = 0.0
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.TO_WORK
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }.reason shouldBe "route-unreachable"
            for (tick in 1..300) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(2_000 + tick * 50L)
                val distance = before.distance(actor.location)
                withClue("upkeep movement tick=$tick from=$before to=${actor.location}") { (distance <= .172001) shouldBe true }
                walked += distance
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.owner.status(h.workerId) shouldBe "workers.status.repairing-road"
            // Packet teleport also carries normal walking frames; the stride bound excludes recovery jumps.
            (walked >= 1.0) shouldBe true
            h.spawns shouldBe spawns
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
            h.world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.STONE
        }
    }

    test("blocked road upkeep rejects its stance without teleport or credit then resumes a real walk") {
        residentRuntime { h ->
            val work = h.assignRoadUpkeep()
            val actor = h.owner.collisionActors().single()
            val spawns = h.spawns
            val walls = (-2..2).flatMap { x -> (-2..2).flatMap { z ->
                if (kotlin.math.abs(x) == 2 || kotlin.math.abs(z) == 2) (64..67).map { y -> CivPosition(x, y, z) }
                else emptyList()
            } }
            walls.forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.STONE }
            h.onWorkPositionFailed = { _, _, _ -> h.work = null; true }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            var ticks = 0
            while (ticks < 240 && h.failedWorkPositions.isEmpty()) {
                ticks++
                val before = actor.location
                h.navigate()
                if (ticks % 20 == 0) h.owner.pulse(2_000 + ticks * 50L)
                withClue("blocked upkeep movement tick=$ticks from=$before to=${actor.location}") {
                    (before.distance(actor.location) <= .172001) shouldBe true
                }
            }
            h.failedWorkPositions shouldBe listOf(work)
            h.deliveries shouldBe 0
            h.spawns shouldBe spawns
            h.city.workers.single().completedCycles shouldBe 0L
            walls.forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
            h.work = work
            h.onWorkPositionFailed = { _, _, _ -> false }
            for (step in 1..300) {
                ticks++
                val before = actor.location
                h.navigate()
                if (ticks % 20 == 0) h.owner.pulse(2_000 + ticks * 50L)
                withClue("resumed upkeep movement tick=$ticks from=$before to=${actor.location}") {
                    (before.distance(actor.location) <= .172001) shouldBe true
                }
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            h.spawns shouldBe spawns
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
        }
    }

    test("physical nudges preserve a pending receipt and stop at native walls") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.0, .5))
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            h.owner.pulse(4_000)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            actor.teleport(Location(h.world, 22.7, 64.0, .5))
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe false
            actor.location.x shouldBe 22.7
        }
    }
})

/** Native sleeping model envelope from actual handle fields, not the allocated target. */
private fun runtimeSleepingBody(npc: PacketPlayerNpc): BoundingBox {
    val at = npc.location
    val s = npc.scale
    return when (npc.bodyYaw) {
        0f -> BoundingBox(at.x - 1.96 * s, at.y - .31 * s, at.z - .62 * s,
            at.x + .07 * s, at.y + .31 * s, at.z + .62 * s)
        90f -> BoundingBox(at.x - .62 * s, at.y - .31 * s, at.z - .07 * s,
            at.x + .62 * s, at.y + .31 * s, at.z + 1.96 * s)
        180f -> BoundingBox(at.x - .07 * s, at.y - .31 * s, at.z - .62 * s,
            at.x + 1.96 * s, at.y + .31 * s, at.z + .62 * s)
        270f -> BoundingBox(at.x - .62 * s, at.y - .31 * s, at.z - 1.96 * s,
            at.x + .62 * s, at.y + .31 * s, at.z + .07 * s)
        else -> error("Unexpected sleeping yaw ${npc.bodyYaw}")
    }
}

internal fun residentRuntime(useOwnedNavigationExecutor: Boolean = false, workerId: UUID = UUID.randomUUID(),
    worldFactory: () -> ResidentRuntimeWorld = ::ResidentRuntimeWorld,
    server: org.mockbukkit.mockbukkit.ServerMock? = null,
    block: (ResidentRuntime) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        (if (server == null) MockBukkitTestRuntime.open() else MockBukkitTestRuntime.open(server)).use { paper ->
            val fixture = ResidentRuntime(paper, useOwnedNavigationExecutor, workerId, worldFactory)
            try { block(fixture) } finally { fixture.close() }
        }
    }
}

internal class ResidentRuntime(val paper: MockBukkitTestRuntime, private val useOwnedNavigationExecutor: Boolean,
    val workerId: UUID, worldFactory: () -> ResidentRuntimeWorld = ::ResidentRuntimeWorld) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("ResidentsTest")
    private var navigationTasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val navigationSettings = CivsNavigationSettings()
    private var navigationWork = BudgetedTaskQueue(navigationTasks, navigationSettings.nativeOperationsPerTick,
        (navigationSettings.nativeBudgetMillis * 1_000_000).toLong(), nanoTime = { 0L })
    private val navigationWorkers = ArrayDeque<Runnable>()
    val logger get() = plugin.logger
    val world = worldFactory().also(paper.server::addWorld)
    private val packets = mockk<PaperPacketNpcs>(relaxed = true)
    var viewing = true
    var background = false
    var spawns = 0
    var teleports = 0
        private set
    var navigationNow: Long? = null
    var recoveryMillis = 0L
    var holdNavigation = false
    var navigationRejections = 0
    var passageLocked = false
    val pendingNavigationWorkers: Int get() = navigationWorkers.size
    var hits = 0
    var deliveries = 0
    var onDelivery: ((CitizenToken) -> Unit)? = null
    var workerDelivery: ((UUID, CitizenToken) -> CompletableFuture<Settlement>)? = null
    var unloads = 0
    var supplyRequests = 0
    var collectionEnabled = false
    var collectionDestination: CivCollectedHome? = null
    val groundRequests = mutableListOf<List<UUID>>()
    val homeDeposits = mutableListOf<CivPosition?>()
    val pendingGroundCollection = CompletableFuture<Settlement>()
    val pendingHomeDeposit = CompletableFuture<Settlement>()
    var workReady = true
    var statusInputs = emptyList<String>()
    var testingAllowed = false
    var foodSource: UUID? = null
    var physicalFoodSource: CivResidentFoodSource? = null
    var tavernServiceSource: CivResidentFoodSource? = null
    val tavernServiceSources = mutableMapOf<UUID, CivResidentFoodSource>()
    var physicalFoodPickup: (CivResidentFoodSource) -> CompletableFuture<Settlement> = {
        CompletableFuture.failedFuture(CivsRejection("resident-food-missing"))
    }
    var workPlans = 0
    val failedWorkPositions = mutableListOf<CivWorldWork>()
    var onWorkPositionFailed: (Settlement, CivWorker, CivWorldWork) -> Boolean = { _, _, _ -> false }
    var surfaceMultiplier = 1.0
    val pendingSupplies = CompletableFuture<Settlement>()
    var courier: CivCourierPlan? = null
    var courierProvider: ((Settlement, CivWorker) -> CivCourierPlan?)? = null
    var pickups = 0
    var pickupProvider: ((UUID, UUID, CivCargo) -> CompletableFuture<Settlement>)? = null
    var dropoffProvider: ((UUID, UUID, CivCargo, UUID) -> CompletableFuture<Settlement>)? = null
    var pickedCargo: CivCargo? = null
    var onPickup: (UUID, UUID, CivCargo) -> Unit = { _, _, _ -> }
    val pendingPickup = CompletableFuture<Settlement>()
    val steps = mutableListOf<Pair<Location, Location>>()
    val gestures = mutableMapOf<UUID, Int>()
    private val displayedHands = mutableMapOf<UUID, Material>()
    val displayedHand: Material get() = displayedHands[workerId] ?: Material.AIR
    private val usingMainHands = mutableMapOf<UUID, Boolean>()
    val usingMainHand: Boolean get() = usingMainHands[workerId] ?: false
    var work: CivWorldWork? = null
    var workProvider: ((Settlement, CivWorker) -> CivWorldWork?)? = null
    var workstationProvider: ((Settlement, CivWorker) -> CivWorkstation?)? = null
    var workIdleProvider: ((Settlement, CivWorker) -> String)? = null
    var constructionColumns = emptySet<Long>()
    var idleReason = "waiting-inputs"
    val pendingDelivery = CompletableFuture<Settlement>()
    val pendingUnload = CompletableFuture<Settlement>()
    val pending = CompletableFuture<Settlement>()
    private var damage = 0.0
    var city: Settlement
    val otherCities = mutableListOf<Settlement>()
    var owner: CivsCitizens
        private set
    val visualHealth: Double get() = owner.hologramSnapshots().single().health

    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        val viewer = paper.addPlayer("ResidentViewer")
        viewer.teleport(Location(world, .5, 64.0, .5))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
            "townhall1", CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), viewer.uniqueId, "Town", world.uid, CivPosition(0, 64, 0),
            96, "nordic", Stock(), listOf(hall), listOf(CivWorker(workerId, "Resident", null))))
        every { packets.spawnPlayer(any(), any()) } answers {
            spawns++
            var position = firstArg<Location>().clone()
            var npcBodyYaw = position.yaw
            var npcHeadYaw = position.yaw
            var npcPitch = position.pitch
            val profile = secondArg<PacketNpcProfile>()
            var valid = true
            var npcScale = 1.0
            var npcPose = PacketNpcPose.STANDING
            var seated = false
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { uniqueId } returns profile.uuid
                every { isValid } answers { valid }
                every { isSeated } answers { seated }
                every { location } answers { position.clone().also { it.yaw = npcBodyYaw; it.pitch = npcPitch } }
                every { bodyYaw } answers { npcBodyYaw }
                every { bodyYaw = any() } answers { npcBodyYaw = firstArg() }
                every { headYaw } answers { npcHeadYaw }
                every { headYaw = any() } answers { npcHeadYaw = firstArg() }
                every { pitch } answers { npcPitch }
                every { pitch = any() } answers { npcPitch = firstArg() }
                every { world } returns this@ResidentRuntime.world
                every { height } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.8 * npcScale
                    PacketNpcPose.CROUCHING -> 1.5 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
                every { eyeHeight } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.62 * npcScale
                    PacketNpcPose.CROUCHING -> 1.27 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
                every { scale } answers { npcScale }
                every { scale = any() } answers { npcScale = firstArg() }
                every { pose } answers { npcPose }
                every { pose = any() } answers { npcPose = firstArg(); if (npcPose != PacketNpcPose.STANDING) seated = false }
                every { teleport(any()) } answers {
                    teleports++
                    seated = false
                    position = firstArg<Location>().clone()
                    npcBodyYaw = position.yaw; npcPitch = position.pitch
                    Unit
                }
                every { sit(any()) } answers {
                    position = firstArg<Location>().clone()
                    npcBodyYaw = position.yaw; npcPitch = position.pitch
                    npcPose = PacketNpcPose.STANDING
                    seated = true
                    Unit
                }
                every { stand() } answers { seated = false }
                every { setEquipment(EquipmentSlot.HAND, any()) } answers { displayedHands[profile.uuid] = secondArg<ItemStack>().type; Unit }
                every { isUsingMainHand } answers { usingMainHands[profile.uuid] ?: false }
                every { isUsingMainHand = any() } answers { usingMainHands[profile.uuid] = firstArg(); Unit }
                every { swingMainHand() } answers { gestures[profile.uuid] = gestures.getOrDefault(profile.uuid, 0) + 1; Unit }
                every { remove() } answers { valid = false; seated = false; Unit }
            }
        }
        every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
        owner = createOwner()
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    /** Fresh component generation; admission chooses its normal origin, never saved runtime feet. */
    fun recreateResidents() {
        owner.closeAsync().join()
        navigationWork.close()
        navigationTasks.close()
        navigationWorkers.clear()
        navigationTasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        navigationWork = BudgetedTaskQueue(navigationTasks, navigationSettings.nativeOperationsPerTick,
            (navigationSettings.nativeBudgetMillis * 1_000_000).toLong(), nanoTime = { 0L })
        owner = createOwner()
    }

    private fun createOwner(): CivsCitizens = CivsCitizens(plugin, { if (viewing) listOf(city) + otherCities else emptyList() },
            { _, deliveredWorker, assignment, cycle, nextAt, order, _ ->
                val token = CitizenToken(assignment, cycle, nextAt, order)
                val commit = workerDelivery
                if (commit != null) {
                    deliveries++
                    commit(deliveredWorker, token)
                } else {
                    if (onDelivery != null) onDelivery!!.invoke(token)
                    else check(work != null) { "idle resident cannot deliver" }
                    deliveries++
                    pendingDelivery
                }
            },
            { _, _, _ -> }, hurt = { _, _, hit -> hits++; damage = hit; pending },
            doorBlocked = { _, _ -> passageLocked },
            footstep = { _, _, _, from, to -> steps += from.clone() to to.clone() },
            surfaceSpeed = { surfaceMultiplier },
            courierPlan = { city, worker -> courierProvider?.invoke(city, worker) ?: courier },
            pickupCargo = { city, worker, cargo ->
                onPickup(city, worker, cargo)
                pickups++
                pickedCargo = cargo
                pickupProvider?.invoke(city, worker, cargo) ?: pendingPickup
            }, dropoffCargo = { city, worker, cargo, target ->
                val provider = dropoffProvider
                if (provider != null) {
                    unloads++
                    provider(city, worker, cargo, target)
                } else {
                    cargo shouldBe requireNotNull(courier).cargo
                    target shouldBe requireNotNull(courier).destinationBuildingId
                    unloads++
                    pendingUnload
                }
            },
            workPlan = { city, worker -> workPlans++; workProvider.let { if (it == null) work else it(city, worker) } },
            workIdleReason = { city, worker -> workIdleProvider?.invoke(city, worker) ?: idleReason }, packetOwner = packets,
            residentSupplySource = { _, worker -> foodSource.takeIf { worker.id == workerId } },
            residentFoodSource = { _, worker -> physicalFoodSource.takeIf { CivsResidents.hunger(worker) <= 14 &&
                CivsEquipment.usableCount(worker, "BREAD") == 0 } },
            tavernFoodSource = { _, worker -> tavernServiceSources[worker.id] ?: tavernServiceSource.takeIf { worker.id == workerId } },
            pickupResidentFood = { _, _, source -> physicalFoodPickup(source) },
            workInputsReady = { _, _ -> workReady }, workStatusInputs = { _, _ -> statusInputs },
            workstation = workstationProvider, prepareSupplies = { _, _, _ ->
                supplyRequests++
                pendingSupplies
            }, backgroundActive = { background }, navigationTasks = navigationTasks, navigationWork = navigationWork,
            navigationExecutor = if (useOwnedNavigationExecutor) null else Executor { task ->
                if (navigationRejections > 0) {
                    navigationRejections--
                    throw RejectedExecutionException("Transient navigation fixture admission failure")
                }
                if (holdNavigation) navigationWorkers += task else task.run()
            },
            navigationClock = { navigationNow ?: System.currentTimeMillis() },
            recoveryClock = { recoveryMillis },
            workPositionFailed = { city, worker, failed ->
                failedWorkPositions += failed
                onWorkPositionFailed(city, worker, failed)
            }, constructionSite = { _, order -> constructionSite()?.takeIf { it.orderId == order } },
            collectionAvailable = { collectionEnabled }, collectionHome = { _, _ -> collectionDestination },
            pickupGround = { _, _, ids -> groundRequests += ids; pendingGroundCollection },
            depositCollected = { _, _, point -> homeDeposits += point; pendingHomeDeposit },
            immortalResidents = { id -> id == city.id && city.settings?.immortalResidents == true && testingAllowed })
    fun prepareDiningSeat(guard: Boolean = false): PacketPlayerNpc {
        navigationNow = 3_000
        if (guard) for (x in -1..1) world.loadChunk(x, -2)
        val tower = if (guard) CivBuilding(UUID.randomUUID(), BuildingType.GUARD_TOWER, 1, CivPosition(10, 64, 0), 0,
            "guardtower1", CivBox(CivPosition(9, 63, -1), CivPosition(11, 68, 1)),
            CivPosition(10, 64, -2), BuildingPhase.ACTIVE) else null
        val tavern = CivBuilding(UUID.randomUUID(), BuildingType.TAVERN, 1, CivPosition(6, 64, 0), 0,
            "seated-meal-fixture", CivBox(CivPosition(4, 63, -2), CivPosition(8, 68, 4)),
            CivPosition(5, 64, -2), BuildingPhase.ACTIVE, control = CivPosition(8, 64, -1))
        world.getBlockAt(6, 64, 0).blockData = Bukkit.createBlockData("minecraft:stone_stairs[facing=north,half=bottom,shape=straight]")
        world.getBlockAt(6, 64, 2).blockData = Bukkit.createBlockData("minecraft:stone_stairs[facing=south,half=bottom,shape=straight]")
        world.getBlockAt(6, 65, 1).type = Material.GREEN_WOOL
        world.getBlockAt(8, 64, -1).type = Material.BARREL
        CivsWorksiteLayouts.installCatalog(mapOf(tavern.schematicId to com.google.gson.JsonParser.parseString(
            """{"tavern":{"dining":[[0,0,0]]}}""").asJsonObject))
        city = CivsRules.validate(city.copy(autonomy = CivAutonomy(), stock = Stock(food = 2), inventory = null,
            buildings = city.buildings + listOfNotNull(tavern, tower),
            workers = city.workers.map { it.copy(assignment = tower?.id ?: it.assignment,
                nextDeliveryAt = if (guard) 1 else it.nextDeliveryAt,
                hunger = 14, inventory = mapOf("BREAD" to 2L, "IRON_AXE" to 1L),
                life = CivResidentLife(awakeMinutes = 6)) } +
                CivWorker(UUID.randomUUID(), "Innkeeper", tavern.id, nextDeliveryAt = 1)))
        owner.pulse(2_000)
        val npc = owner.collisionActors().first { it.uniqueId == workerId }
        npc.teleport(Location(world, 5.5, 64.0, .5))
        owner.residentLifeEnabled = true
        return npc
    }

    fun standingTavernkeeper(): UUID {
        for (x in -1..1) world.loadChunk(x, -2)
        prepareDiningSeat()
        owner.residentLifeEnabled = false
        val keeper = city.workers.single { it.assignment?.let(city::building)?.type == BuildingType.TAVERN }.id
        owner.pulse(3_000)
        withClue(owner.debug(city.id, keeper)) { owner.collisionActors().any { it.uniqueId == keeper } shouldBe true }
        if (phase(keeper) != CitizenPhase.WORKING) {
            owner.collisionActors().first { it.uniqueId == keeper }.teleport(requireNotNull(routeTarget(keeper)))
            owner.pulse(4_000)
        }
        phase(keeper) shouldBe CitizenPhase.WORKING
        return keeper
    }

    fun acceptTavernConstruction(): CivBuilding {
        val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1, CivPosition(12, 64, 5), 0,
            "builder1", CivBox(CivPosition(10, 63, 3), CivPosition(14, 68, 7)),
            CivPosition(12, 64, 2), BuildingPhase.ACTIVE, control = CivPosition(12, 64, 5))
        val house = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(16, 64, 8), 0,
            "house1", CivBox(CivPosition(15, 63, 7), CivPosition(17, 68, 9)),
            CivPosition(16, 64, 6), BuildingPhase.SITE, control = CivPosition(16, 64, 8))
        city = CivsRules.validate(city.copy(buildings = city.buildings + hut + house, constructionQueue = listOf(house.id)))
        return hut
    }

    fun autonomousTrafficTown(headOn: Boolean): Triple<CivBuilding, CivBuilding, CivBuilding> {
        background = true
        for (x in -120..-112) for (z in -176..-167) world.loadChunk(x, z)
        fun building(type: BuildingType, x: Int, z: Int) = CivBuilding(UUID.randomUUID(), type, 1,
            CivPosition(x, 64, z), 0, "traffic-${type.name.lowercase()}",
            CivBox(CivPosition(x - 2, 64, z - 4), CivPosition(x + 2, 68, z - 1)),
            CivPosition(x, 64, z), BuildingPhase.ACTIVE)
        val hall = building(BuildingType.TOWN_HALL, -1868, -2759)
        val farm = building(BuildingType.FARM, -1813, -2735)
        // The regression isolates the depot contact on level ground; remote mine geometry has its own tests.
        val quarry = building(BuildingType.QUARRY, -1844, if (headOn) -2759 else -2696)
        val forester = building(BuildingType.FORESTER, -1868, -2696)
        city = CivsRules.validate(city.copy(center = hall.entrance, autonomy = CivAutonomy(), inventory = null,
            buildings = listOf(hall, farm, quarry, forester),
            workers = listOf(CivWorker(workerId, "Farmer", farm.id, completedCycles = 113, nextDeliveryAt = 1))))
        owner.collisionActors().single().teleport(Location(world, -1867.5, 64.0, -2758.5))
        return Triple(farm, quarry, forester)
    }
    fun yielding(worker: UUID): Boolean = (field(owner, "actors") as Map<*, *>)[worker]?.let { field(it, "residentYield") } != null
    fun yieldPassing(worker: UUID): UUID? = (field(owner, "actors") as Map<*, *>)[worker]
        ?.let { field(it, "residentYield") }?.let { field(it, "passing") as UUID }
    fun routeMotion(worker: UUID): CitizenPacketRoute? = routeIdentity(worker)?.let { field(it, "motion") as CitizenPacketRoute }
    fun routeProgress(worker: UUID): Double? = routeMotion(worker)?.forwardProgress
    fun routeFinished(worker: UUID): Boolean = routeIdentity(worker)?.let { (field(it, "motion") as CitizenPacketRoute).finished } == true
    fun requestYieldAsReturning(requester: UUID, blocker: UUID, at: Location) {
        val actor = requireNotNull((field(owner, "actors") as Map<*, *>)[requester])
        fun set(name: String, value: Any?) = actor.javaClass.getDeclaredField(name).run { isAccessible = true; set(actor, value) }
        (field(actor, "npc") as PacketPlayerNpc).teleport(at)
        val plan = (field(actor, "plan") as CitizenPlan).copy(idleReason = null)
        set("plan", plan)
        field(actor, "route")?.let { (field(it, "motion") as CitizenPacketRoute).cancel() }
        set("route", null)
        set("cycle", CitizenCycle(plan.token, 0, pickupAtWorkplace = true, carrying = true).also { it.start() })
        set("collisionResident", blocker)
        val collisions = field(owner, "collisions") as CivsCitizenCollisions
        collisions.beginResidentYield(actor as CitizenActor)
    }
    fun playerAt(at: Location): Player = paper.addPlayer("TrafficBlocker").also { it.teleport(at); world.threatEntities += it }
    fun nativeCollisions() = CivsResidentCollisions(plugin, owner::collisionActors, owner::nudgeResident, owner::passingBodies)

    fun monsterAt(x: Double, z: Double): Monster {
        world.difficulty = Difficulty.NORMAL
        val at = Location(world, x, 64.0, z)
        return mockk<Monster>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { isValid } returns true
            every { isDead } returns false
            every { hasAI() } returns true
            every { type } returns EntityType.ZOMBIE
            every { world } returns this@ResidentRuntime.world
            every { location } answers { at.clone() }
            every { eyeLocation } answers { at.clone().add(0.0, 1.62, 0.0) }
            every { target } returns null
        }.also(world.threatEntities::add)
    }

    fun groundAt(at: Location) = civsGroundFixture(paper, world, at, ItemStack(Material.COAL)).also(world.threatEntities::add)

    fun hit(amount: Double) = owner.damage(workerId, amount)
    fun constructionSite(): CivConstructionSite? = city.construction?.let {
        CivConstructionSite(it.id, requireNotNull(it.plannedBuilding), constructionColumns)
    }
    fun constructionTarget(): CivPosition = requireNotNull(citizenConstructionTarget(city, world, requireNotNull(constructionSite())))
    fun navigate(advanceMillis: Long = 50L) {
        navigationNow = navigationNow?.plus(advanceMillis)
        recoveryMillis += advanceMillis
        paper.performTicks(1)
        owner.activateNavigating()
    }
    fun releaseNavigation() {
        holdNavigation = false
        while (navigationWorkers.isNotEmpty()) navigationWorkers.removeFirst().run()
    }
    fun clearRecordedCalls() = clearMocks(packets, *owner.collisionActors().toTypedArray(),
        answers = false, recordedCalls = true, childMocks = false)
    fun socialState(worker: UUID): Map<*, *> =
        (((owner.debug(city.id, worker)["entries"] as List<*>).single() as Map<*, *>)["social"] as Map<*, *>)
    fun combatState(worker: UUID = workerId): Map<*, *> =
        (((owner.debug(city.id, worker)["entries"] as List<*>).single() as Map<*, *>)["combat"] as Map<*, *>)
    fun mealState(worker: UUID = workerId): Map<*, *> =
        (((owner.debug(city.id, worker)["entries"] as List<*>).single() as Map<*, *>)["meal"] as Map<*, *>)
    fun prepareHallMeals(hungry: Set<UUID>) {
        val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(0, 64, 0))
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        city = city.copy(buildings = city.buildings.map { if (it.id == hall.id) hall else it },
            autonomy = CivAutonomy(), inventory = null, stock = Stock(food = 306),
            workers = city.workers.map { it.copy(hunger = if (it.id in hungry) 14 else 20,
                inventory = if (it.id in hungry) mapOf("BREAD" to 4L) else it.inventory,
                life = CivResidentLife(awakeMinutes = 20)) })
        owner.residentLifeEnabled = true
    }
    fun startSocialPair(): List<PacketPlayerNpc> {
        for (x in -2..1) for (z in -2..1) world.loadChunk(x, z)
        city = city.copy(workers = city.workers + CivWorker(UUID(0, 29), "Neighbour", null))
        navigationNow = 2_000
        owner.pulse(2_000)
        return owner.collisionActors().toList().also { it.size shouldBe 2 }
    }
    fun awaitConversation() {
        for (tick in 1..2_400) {
            navigate()
            if (tick % 20 == 0) {
                owner.pulse(requireNotNull(navigationNow))
                clearRecordedCalls()
                val actors = owner.collisionActors()
                if (actors.size == 2 && actors.all { socialState(it.uniqueId)["conversation_at"] != null }) return
            }
        }
        error("No street conversation: ${owner.debug(city.id, null)}")
    }
    override fun close() {
        val actors = owner.collisionActors().toTypedArray()
        try { owner.closeAsync().join() } finally {
            navigationWork.close()
            navigationTasks.close()
            clearMocks(packets, *actors)
        }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
    fun observedWork(worker: UUID = workerId): Long {
        val actor = (field(owner, "actors") as Map<*, *>)[worker]!!
        return (field(actor, "cycle") as CitizenCycle).observedWorkMillis
    }
    fun phase(worker: UUID = workerId): CitizenPhase =
        ((owner.debug(city.id, worker)["entries"] as List<*>).single() as Map<*, *>)["phase"] as CitizenPhase
    /** Read-only identity distinguishes an accepted pending route from a silent replacement. */
    fun routeIdentity(worker: UUID = workerId): Any? =
        (field(owner, "actors") as Map<*, *>)[worker]?.let { field(it, "route") }
    fun routeTarget(worker: UUID = workerId): Location? =
        routeIdentity(worker)?.let { (field(it, "target") as Location).clone() }
    fun autonomousObservedMillis(worker: UUID = workerId): Long =
        ((field(owner, "actors") as Map<*, *>)[worker]?.let { field(it, "autonomousVisualSession") }
            as? CivAutonomousVisualSession)?.workedMillis ?: 0L
    fun routeFailure(worker: UUID = workerId): String? =
        routeIdentity(worker)?.let { (field(it, "motion") as CitizenPacketRoute).failure }
    fun ownedNavigationExecutor(): Executor =
        requireNotNull(field(requireNotNull(field(owner, "navigation")), "ownedExecutor") as? Executor)
    /** Read-only failure context; runtime behavior never depends on private test access. */
    fun navigationState(worker: UUID = workerId): String = runCatching {
        val actor = (field(owner, "actors") as Map<*, *>)[worker] ?: return@runCatching "no actor"
        val cycle = field(actor, "cycle") as CitizenCycle
        val route = field(actor, "route")
        val motion = route?.let { field(it, "motion") as CitizenPacketRoute }
        val queue = field(requireNotNull(field(owner, "navigation")), "jobs") as Map<*, *>
        val shelter = field(actor, "shelter") as? CivPosition
        val feet = owner.residentFeet(worker)
        val chunksReady = if (shelter == null || feet == null) null else
            CitizenChunks.between(feet.blockX, feet.blockZ, shelter.x, shelter.z).ready(world)
        "cycle=${cycle.phase}/${cycle.reason} resume=${field(cycle, "resumePhase")} workedMillis=${field(cycle, "workedMillis")} " +
            "shelter=$shelter shelterChunksReady=$chunksReady sleepPose=${field(actor, "shelterPose")} " +
            "shelterCursor=${field(actor, "shelterSearchIndex")} queue=${queue.size} nodes=${motion?.nodeCount} " +
            "searching=${motion?.searching} failure=${motion?.failure} remaining=${motion?.remainingNodes}"
    }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }

    fun beginCare(status: String): CivWorldWork {
        val farm = assignFarm()
        city = city.copy(workers = city.workers.map { worker -> worker.copy(items =
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { slots ->
                slots[4] = CivResidentItem("IRON_HOE", 1)
                if (status == "fertilizing") slots[6] = CivResidentItem("BONE_MEAL", 1)
            }) })
        owner.collisionActors().single().teleport(Location(world, 22.5, 64.0, .5))
        val focus = CivPosition(23, 64, 0)
        world.getBlockAt(focus.x, focus.y, focus.z).type = Material.WHEAT
        work = CivWorldWork(workerId, 0, farm.id, CivPosition(24, 64, 0),
            if (status == "fertilizing") listOf(CivPlanBlock(focus, "minecraft:wheat[age=5]", "minecraft:wheat[age=6]"))
                else emptyList(),
            inputs = if (status == "fertilizing") mapOf("BONE_MEAL" to 1L) else emptyMap(),
            durationMillis = 4_000, status = status, focus = focus)
        owner.pulse(2_000)
        return requireNotNull(work)
    }
    fun commitWork() {
        val completed = requireNotNull(work)
        city = city.copy(revision = city.revision + 1, workers = city.workers.map { worker ->
            CivsEquipment.consumeInputs(worker, completed.inputs).copy(completedCycles = worker.completedCycles + 1)
        })
        work = null
        idleReason = "waiting-growth"
        pendingDelivery.complete(city)
    }
    fun assignFarm(assignedWorker: UUID? = null): CivBuilding {
        val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
            "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)), CivPosition(20, 64, 0),
            BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
        for (z in -1..1) world.loadChunk(2, z)
        world.getBlockAt(20, 64, 0).type = Material.BARREL
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm,
            workers = city.workers.map {
                if (assignedWorker == null || it.id == assignedWorker) it.copy(assignment = farm.id, nextDeliveryAt = 1) else it
            },
            inventory = CivInventory(CivStorageMode.PHYSICAL)))
        return farm
    }
    fun courierSource(material: String = "COAL", amount: Int = 64, fullBag: Boolean = false): Pair<CivBuilding, UUID> {
        val farm = assignFarm()
        val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null,
            anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 0), control = CivPosition(10, 64, 0),
            bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
        world.getBlockAt(10, 64, 0).type = Material.BARREL
        val courierId = UUID(0, 43)
        city = CivsRules.validate(city.copy(buildings = city.buildings + warehouse,
            workers = city.workers.map { it.copy(items = List(CivsEquipment.SLOTS) { slot ->
                if (slot == CivsEquipment.BAG || fullBag && slot > CivsEquipment.BAG) CivResidentItem(material, amount) else null
            }) } + CivWorker(courierId, "Courier", warehouse.id, nextDeliveryAt = 1)))
        return farm to courierId
    }
    fun assignCanonicalAutoFarm(): CivBuilding {
        val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(20, 63, 0))
        for (x in -1..3) for (z in -3..1) world.loadChunk(x, z)
        city = CivsRules.validate(city.copy(revision = 17, inventory = null, stock = Stock(7, 8, 9),
            autonomy = CivAutonomy(products = mapOf(CivIndustryProduct.GLASS to 3L)),
            buildings = city.buildings + farm, workers = city.workers.map { worker ->
                worker.copy(assignment = farm.id, completedCycles = 7, nextDeliveryAt = 60_000,
                    skills = mapOf("FARM" to 3L), items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                        4 -> CivResidentItem("IRON_HOE", 1)
                        CivsEquipment.BAG -> CivResidentItem("COAL", 11)
                        else -> null
                    } })
            }))
        idleReason = "searching-work"
        return farm
    }
    fun autoCrop(farm: CivBuilding, index: Int, duration: Long): CivWorldWork {
        val fields = CivsWorksiteLayouts.field(farm)
        val row = fields.filter { it.z == fields.minOf(CivPosition::z) }.sortedBy(CivPosition::x)
        val soil = row[index % row.size]
        val crop = soil.copy(y = soil.y + 1)
        val watering = index >= row.size
        val before = if (watering) "minecraft:wheat[age=3]" else "minecraft:air"
        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
        // The second pass observes ordinary native growth; the receipt only applies the subsequent care action.
        world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData(before)
        return CivWorldWork(workerId, city.workers.first { it.id == workerId }.completedCycles, farm.id,
            soil.copy(z = soil.z - 1), listOf(CivPlanBlock(crop, before,
                if (watering) "minecraft:wheat[age=4]" else "minecraft:wheat[age=0]")), durationMillis = duration,
            status = if (watering) "watering" else "working-field", focus = crop, autonomousVisual = true).also { work = it }
    }
    fun assignQuarry(): CivBuilding = assignFarm().copy(type = BuildingType.QUARRY, schematicId = "miner1", production = "COBBLESTONE").also { quarry ->
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
    }
    fun assignRoadUpkeep(): CivWorldWork {
        val hut = assignFarm().copy(type = BuildingType.BUILDER_HUT, production = null)
        val floor = CivPosition(6, 63, 0)
        val road = CivBuilding(UUID.randomUUID(), BuildingType.ROAD, 1, floor, 0, "civs/infrastructure/test-road",
            CivBox(floor, floor.copy(y = 65)), floor.copy(y = 64), BuildingPhase.ACTIVE,
            transit = listOf(floor.copy(y = 64)))
        city = CivsRules.validate(city.copy(autonomy = CivAutonomy(), inventory = null, stock = Stock(96, 96, 24),
            buildings = city.buildings.map { if (it.id == hut.id) hut else it } + road))
        return CivWorldWork(workerId, 0, hut.id, floor.copy(x = 5, y = 64),
            listOf(CivPlanBlock(floor, "minecraft:stone", "minecraft:cobblestone")), status = "repairing-road", focus = floor,
            upkeep = CivRoadUpkeep(city.id, city.worldId, city.owner, road.id, road, floor,
                "minecraft:cobblestone", null, emptyList())).also { work = it }
    }
    fun prepareMineWork(standing: CivPosition, focus: CivPosition = CivPosition(25, 64, 0),
                        anchor: CivPosition = CivPosition(19, 75, 12)): CivWorldWork {
        val previous = assignQuarry()
        requireNotNull(previous.control).let { world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
        // This anchor puts the authored first gallery on the fixture's supported y=64 plane.
        val quarry = spatialBuilding("medievaloak", "miner", 1, anchor)
            .copy(id = previous.id, production = "COBBLESTONE")
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
        for (x in 18..27) for (z in -1..1) for (y in 64..66)
            if (x == 18 || x == 27 || z != 0 || y == 66) world.getBlockAt(x, y, z).type = Material.STONE
        for (y in focus.y..focus.y + 2) world.getBlockAt(focus.x, y, focus.z).type = Material.STONE
        owner.collisionActors().single().teleport(Location(world, 20.5, 64.0, .5))
        val geometry = requireNotNull(CivsMinePlan.forBuilding(quarry))
        val localFocus = CivsMinePlan.localPoint(quarry, focus)
        val step = geometry.steps.indexOfFirst { it.point == localFocus && it.after == null }
        check(step >= 0) { "Missing canonical mine step for $localFocus" }
        return CivWorldWork(workerId, 0, quarry.id, standing,
            listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), durationMillis = 1_500,
            status = "digging-mine", miningStep = step.toLong(), focus = focus, miningPlanVersion = geometry.version,
            // Controller-only fixture: the shaft/landings are already inspected;
            // the separate faithful-prefix traversal above proves their geometry.
            miningAccessStep = maxOf(step, geometry.shaftEnd).toLong())
            .also { work = it }
    }
    fun commitHit() {
        city = CivsRules.hurtResident(city, city.owner, workerId, damage)
        pending.complete(city)
    }
}

/** A stone plane with no tile entities. Only pinned MockBukkit's missing terrain queries are supplied. */
internal open class ResidentRuntimeWorld : WorldMock(Material.STONE, 63) {
    val threatEntities = mutableListOf<Entity>()
    val particles = mutableListOf<Triple<Particle, Int, Location>>()
    val sounds = mutableListOf<Triple<Sound, Float, Float>>()
    private val cells = mutableMapOf<Coordinate, BlockMock>()
    private val chunkFixtures = mutableMapOf<Pair<Int, Int>, ChunkMock>()
    init { setName("resident-runtime") }

    override fun getNearbyEntities(location: Location, x: Double, y: Double, z: Double,
        filter: Predicate<in Entity>?): Collection<Entity> = threatEntities.filter { entity ->
        val at = entity.location
        // Paper queries native bounding boxes, not only feet; an approaching player can overlap
        // a resident's swept body while their feet are still outside the query box.
        val nearby = if (entity is Player) entity.boundingBox.overlaps(BoundingBox(
            location.x - x, location.y - y, location.z - z, location.x + x, location.y + y, location.z + z))
        else kotlin.math.abs(at.x - location.x) <= x && kotlin.math.abs(at.y - location.y) <= y &&
            kotlin.math.abs(at.z - location.z) <= z
        nearby && filter?.test(entity) != false
    }

    override fun spawnParticle(particle: Particle, location: Location, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double) {
        particles += Triple(particle, count, location.clone())
    }

    override fun playSound(location: Location, sound: Sound, category: SoundCategory, volume: Float, pitch: Float) {
        sounds += Triple(sound, volume, pitch)
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))
    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            override fun isPassable(): Boolean = type.isAir
            override fun getDrops(tool: ItemStack?): Collection<ItemStack> = when {
                type.name.endsWith("_LOG") -> listOf(ItemStack(type))
                type.name.endsWith("_LEAVES") -> emptyList()
                else -> super.getDrops(tool)
            }
            override fun getBoundingBox(): BoundingBox = if (type == Material.LADDER)
                BoundingBox(x.toDouble(), y.toDouble(), z + 13.0 / 16.0, x + 1.0, y + 1.0, z + 1.0)
            else BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + when (type) {
                Material.AIR, Material.CAVE_AIR, Material.VOID_AIR -> 0.0
                Material.FARMLAND, Material.DIRT_PATH -> .9375
                else -> 1.0
            }, z + 1.0)
            override fun getCollisionShape(): VoxelShape = when {
                type.isAir -> EMPTY_SHAPE
                blockData is Gate -> (blockData as Gate).let { gate ->
                    if (gate.isOpen) EMPTY_SHAPE
                    else if (gate.facing in setOf(BlockFace.NORTH, BlockFace.SOUTH)) NORTH_GATE_SHAPE else EAST_GATE_SHAPE
                }
                type == Material.LADDER -> LADDER_SHAPE
                type == Material.FARMLAND || type == Material.DIRT_PATH -> LOWERED_SHAPE
                blockData is Stairs && (blockData as Stairs).half == Bisected.Half.BOTTOM &&
                    (blockData as Stairs).shape == Stairs.Shape.STRAIGHT -> {
                    val top = when ((blockData as Stairs).facing) {
                        BlockFace.SOUTH -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                        BlockFace.WEST -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                        BlockFace.EAST -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                        else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                    }
                    shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), top))
                }
                type == Material.STONE_STAIRS -> NORTH_STAIRS_SHAPE
                else -> FULL_SHAPE
            }
        }
    }

    /** Pinned WorldMock omits this native API. The fixture adds the default north-facing ladder shape. */
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        val end = start.clone().add(direction.clone().normalize().multiply(maxDistance))
        var nearest: RayTraceResult? = null
        for (x in minOf(start.blockX, end.blockX)..maxOf(start.blockX, end.blockX))
            for (y in minOf(start.blockY, end.blockY)..maxOf(start.blockY, end.blockY))
                for (z in minOf(start.blockZ, end.blockZ)..maxOf(start.blockZ, end.blockZ)) {
                    val block = getBlockAt(x, y, z)
                    if (block.type.isAir) continue
                    val hit = block.boundingBox.rayTrace(start.toVector(), direction, maxDistance) ?: continue
                    if (nearest == null || hit.hitPosition.distanceSquared(start.toVector()) < nearest.hitPosition.distanceSquared(start.toVector()))
                        nearest = RayTraceResult(hit.hitPosition, block, hit.hitBlockFace)
                }
        return nearest
    }

    override fun getChunkAt(x: Int, z: Int, generate: Boolean): ChunkMock {
        // Preserve native generation/loaded bookkeeping without recording every hot
        // load-level lookup in MockK during bounded snapshot capture.
        super.getChunkAt(x, z, generate)
        return chunkFixtures.getOrPut(x to z) {
            object : ChunkMock(this@ResidentRuntimeWorld, x, z) {
                override fun getTileEntities(filter: Predicate<in Block>, useSnapshot: Boolean): Collection<BlockState> = emptyList()
            }
        }
    }

    companion object {
        private fun shape(boxes: List<BoundingBox>) = object : VoxelShape {
            override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
            override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
        }
        private val EMPTY_SHAPE = shape(emptyList())
        private val FULL_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
        private val LOWERED_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .9375, 1.0)))
        private val NORTH_STAIRS_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
            BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)))
        private val LADDER_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0)))
        private val NORTH_GATE_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, .375, 1.0, 1.5, .625)))
        private val EAST_GATE_SHAPE = shape(listOf(BoundingBox(.375, 0.0, 0.0, .625, 1.5, 1.0)))
    }
}

package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsMineVisitTest : FunSpec({
    test("a paid native ladder visit walks down and back without work mining progress items or delivery") {
        mineVisitFixture { h ->
            val before = h.city
            var lowest = h.at.y
            var lowestPoint = h.at.point()
            var selectedTarget: CivPosition? = null
            var lastReason: String? = null
            val statuses = mutableSetOf<String>()
            var admitted = false
            for (tick in 1..1_000) {
                h.update()
                h.actor.mineVisit?.let { visit ->
                    admitted = true
                    if (visit.target != null) selectedTarget = visit.target
                    if (visit.failure != null) lastReason = visit.failure
                }
                h.walk()
                h.runtime.statuses[h.worker.id]?.let(statuses::add)
                if (h.at.y < lowest) { lowest = h.at.y; lowestPoint = h.at.point() }
                h.actor.cycle.phase shouldBe CitizenPhase.IDLE
                h.actor.cycle.observedWorkMillis shouldBe 0L
                h.actor.cycle.beginDelivery(h.now) shouldBe null
                h.city shouldBe before
                if (admitted && h.actor.mineVisit == null) break
            }
            withClue("actual=${h.at} lowest=$lowest target=$selectedTarget reason=$lastReason visit=${h.actor.mineVisit?.goal}") {
                admitted shouldBe true
                ("mine-descending" in statuses) shouldBe true
                ("mine-returning" in statuses) shouldBe true
                ("route-unreachable" in statuses) shouldBe false
                val target = requireNotNull(selectedTarget)
                target shouldBe h.standing.copy(y = 60)
                // Measured native arrival is y60.345: the existing endpoint contract permits half a block.
                kotlin.math.floor(lowest).toInt() shouldBe target.y
                (h.standing.y - lowest >= 3.5) shouldBe true
                citizenAtSupportedEndpoint(h.world, lowestPoint, target) shouldBe true
                citizenPacketBodyClear(h.world, lowestPoint, false) shouldBe true
                h.world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.LADDER
                h.actor.mineVisit shouldBe null
                citizenAtStorage(h.world, h.at.point(), requireNotNull(h.mine.control)) shouldBe true
            }
            h.actor.plan.worldWork shouldBe h.work
            CivsMineFallback.matches(h.work) shouldBe true
            h.work.standing shouldBe h.standing
            h.work.outputs shouldBe mapOf("COAL" to 1L)
            h.world.getBlockAt(h.denied.x, h.denied.y, h.denied.z).type shouldBe Material.WATER
            h.actor.cycle.start()
            h.actor.cycle.arrive(h.now)
            for (elapsed in 1_000L..5_000L step 1_000) h.actor.cycle.work(h.now + elapsed)
            h.actor.cycle.phase shouldBe CitizenPhase.WORKING
            h.actor.cycle.work(h.now + 6_000)
            h.actor.cycle.phase shouldBe CitizenPhase.READY
        }
    }

    test("actor recreation plan reevaluation and fallback output rolls cannot repeat an unchanged visit") {
        mineVisitFixture { h ->
            h.selectTarget()
            requireNotNull(h.actor.mineVisit).key.workerId shouldBe h.worker.id
            val recreated = CitizenActor(h.city.id, h.worker.id, h.world.uid, h.npc, h.actor.plan)
            h.manager.update(recreated, h.city, h.worker, h.world, h.now) shouldBe false
            recreated.mineVisit shouldBe null
            val later = h.work.copy(cycle = 1, outputs = mapOf("RAW_IRON" to 1L))
            CivsMineFallback.withCause(later, h.need.copy(cycle = 1))
            val nextWorker = h.worker.copy(completedCycles = 1)
            val next = recreated.plan.copy(token = recreated.plan.token.copy(completedCycles = 1), worldWork = later)
            recreated.plan = next
            recreated.cycle = next.cycle()
            h.manager.update(recreated, h.city.copy(workers = listOf(nextWorker)), nextWorker, h.world, h.now) shouldBe false
            h.manager.retainedAttempts shouldBe 1
            h.manager.retain(emptyList())
            h.manager.retainedAttempts shouldBe 0
            h.manager.retain(listOf(h.city))
            h.manager.update(recreated, h.city.copy(workers = listOf(nextWorker)), nextWorker, h.world, h.now) shouldBe true
        }
    }

    test("a changed native safety denial admits one new attempt without changing the paid proof") {
        mineVisitFixture { h ->
            h.selectTarget()
            val recreated = CitizenActor(h.city.id, h.worker.id, h.world.uid, h.npc, h.actor.plan)
            CivsMineFallback.withCause(h.work, h.need.copy(reason = "mine-foreign-block", blockMaterial = "SMOOTH_BASALT"))
            h.manager.update(recreated, h.city, h.worker, h.world, h.now) shouldBe true
            h.manager.retainedAttempts shouldBe 1
            recreated.mineVisit?.key?.cause?.reason shouldBe "mine-foreign-block"
        }
    }

    test("changed proof cancels stale native search and blocks an unpaid return without teleport or work") {
        mineVisitFixture { h ->
            val visit = h.selectTarget()
            val old = requireNotNull(h.actor.route).motion
            h.at = requireNotNull(visit.target).location(h.world)
            h.city = h.city.copy(minePlans = mapOf(h.mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, 0)))
            h.manager.reconcile(h.actor, h.city)
            visit.returning shouldBe true
            h.runtime.statuses[h.worker.id] shouldBe "mine-returning"
            h.actor.route shouldBe null
            old.searching shouldBe false
            old.preparationPhase shouldBe "cancelled"
            visit.passage.completed(h.at.point()) shouldBe false
            val actual = h.at.point()
            repeat(20) { h.update(); h.walk() }
            h.at.point() shouldBe actual
            (h.starts <= 2) shouldBe true // One outbound attempt, then one failed return; five-second cooldown remains.
            h.actor.mineVisit?.returning shouldBe true
            h.actor.cycle.observedWorkMillis shouldBe 0L
            h.worker.completedCycles shouldBe 0L
            h.runtime.statuses[h.worker.id] shouldBe "route-unreachable"
            h.now += 5_000
            h.update()
            h.runtime.statuses[h.worker.id] shouldBe "mine-returning"
            h.starts shouldBe 3
        }
    }

    test("visit standing rechecks real solids fluids support loaded chunks and completed authority") {
        mineVisitFixture { h ->
            val visit = h.selectTarget()
            val point = requireNotNull(visit.target)
            CivsMineVisits.safe(h.city, h.world, visit, point) shouldBe true
            for (hazard in listOf(Material.STONE, Material.WATER, Material.LAVA)) {
                val block = h.world.getBlockAt(point.x, point.y + 1, point.z)
                val before = block.blockData
                block.type = hazard
                CivsMineVisits.safe(h.city, h.world, visit, point) shouldBe false
                block.blockData = before
            }
            val support = (-1..1).map { dy -> h.world.getBlockAt(point.x, point.y + dy, point.z) }
            val beforeSupport = support.map { it.blockData }
            support.forEach { it.type = Material.AIR }
            CivsMineVisits.safe(h.city, h.world, visit, point) shouldBe false
            support.zip(beforeSupport).forEach { (block, data) -> block.blockData = data }
            h.world.unloadChunk(point.x shr 4, point.z shr 4)
            CivsMineVisits.safe(h.city, h.world, visit, point) shouldBe false
            h.world.loadChunk(point.x shr 4, point.z shr 4)
            visit.passage = CitizenMinePassage({ false }, { false })
            CivsMineVisits.safe(h.city, h.world, visit, point) shouldBe false
        }
    }

    test("losing native target safety retires descent and preserves the barrel action") {
        mineVisitFixture { h ->
            val visit = h.selectTarget()
            val old = requireNotNull(h.actor.route).motion
            val target = requireNotNull(visit.target)
            h.world.getBlockAt(target.x, target.y + 1, target.z).type = Material.WATER
            h.update()
            old.searching shouldBe false
            old.preparationPhase shouldBe "cancelled"
            h.at.point() shouldBe h.standing.point()
            h.actor.mineVisit shouldBe null
            h.actor.plan.worldWork shouldBe h.work
            h.actor.cycle.observedWorkMillis shouldBe 0L
        }
    }

    test("visit attempt memory saturates closed and is pruned by current residents rather than active actors") {
        mineVisitFixture(maximumAttempts = 1) { h ->
            h.selectTarget()
            val worker = h.worker.copy(id = UUID.randomUUID())
            val work = CivsMineFallback.withCause(h.work.copy(workerId = worker.id), h.need)
            val actor = CitizenActor(h.city.id, worker.id, h.world.uid, h.npc, h.actor.plan.copy(worldWork = work))
            val city = h.city.copy(workers = listOf(worker))
            h.manager.update(actor, city, worker, h.world, h.now) shouldBe false
            actor.mineVisit shouldBe null
            h.manager.retainedAttempts shouldBe 1
            h.manager.retain(listOf(city))
            h.manager.retainedAttempts shouldBe 0
            h.manager.update(actor, city, worker, h.world, h.now) shouldBe true
        }
    }

    test("visit separation stays in paid passages and both recovery paths refuse teleporting") {
        residentRuntime { h ->
            val actor = requireNotNull((mineVisitField(h.owner, "actors") as Map<*, *>)[h.workerId]) as CitizenActor
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 69, 0))
            val key = CivMineVisitKey(h.city.id, h.workerId, mine, CivMinePlanState(CivsMinePlan.VERSION, 0),
                CivMineVisitCause("worksite-liquid", "mine-thin-cover", mine.anchor, "WATER"))
            actor.mineVisit = CitizenMineVisit(key, actor.plan.storage, emptyList(), CitizenMinePassage({ false }, { false }), 0)
            val motion = CitizenPacketRoute(h.world, actor.npc.location, Location(h.world, 10.5, 64.0, .5),
                { true }, { _, _ -> true }, { 1.0 })
            motion.acceptSearch(CitizenSearchResult(emptyList(), 0, failure = "no-path"))
            actor.route = CitizenRoute(actor.npc.location.point(), Location(h.world, 10.5, 64.0, .5),
                CitizenChunks(-1, 1, -1, 1), 0, 1, emptySet(), motion, null, CivPosition(10, 64, 0))
            val runtime = mineVisitField(h.owner, "runtime") as CitizenRuntime
            runtime.recoveryBudget = 2
            h.recoveryMillis = 100_000
            val before = actor.npc.location.point()
            val teleports = h.teleports
            val routes = mineVisitField(h.owner, "routes") as CivsCitizenRoutes
            routes.recoverNavigation(actor, actor.route!!.target, 100_000) shouldBe false
            val collisions = mineVisitField(h.owner, "collisions") as CivsCitizenCollisions
            val from = actor.npc.location
            val to = from.clone().add(.1, 0.0, 0.0)
            citizenPacketStepClear(h.world, from.point(), to.point(), false) shouldBe true
            val step = collisions.javaClass.getDeclaredMethod("residentRouteStepAllowed", CitizenActor::class.java,
                Settlement::class.java, Location::class.java, Location::class.java).also { it.isAccessible = true }
            step.invoke(collisions, actor, h.city, from, to) shouldBe false
            val recover = collisions.javaClass.getDeclaredMethod("recoverTerrainOverlap", CitizenActor::class.java,
                Settlement::class.java, java.lang.Long.TYPE).also { it.isAccessible = true }
            recover.invoke(collisions, actor, h.city, 100_000L) shouldBe false
            actor.npc.location.point() shouldBe before
            h.teleports shouldBe teleports
            runtime.recoveryBudget shouldBe 2
            h.deliveries shouldBe 0
        }
    }
})

private fun mineVisitField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run {
    isAccessible = true
    get(owner)
}

private fun mineVisitFixture(maximumAttempts: Int = 4_096, block: (MineVisitFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper -> block(MineVisitFixture(paper, maximumAttempts)) }
}

private class MineVisitFixture(paper: MockBukkitTestRuntime, maximumAttempts: Int) {
    val world = CivsWorksiteWorldFixture("movement-only-mine-visit").also(paper.server::addWorld)
    private val original = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 69, 0))
    private val geometry = requireNotNull(CivsMinePlan.forBuilding(original))
    val standing = CivsWorksiteLayouts.point(original, geometry.bottom)
    val mine = original.copy(control = standing.copy(x = standing.x + 1), entrance = standing)
    val worker = spatialWorker(mine, "IRON_PICKAXE")
    var city = spatialCity(world.uid, listOf(mine), worker).copy(autonomy = CivAutonomy(),
        inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to CivMinePlanState(
            CivsMinePlan.VERSION, 0, sectionSteps = mapOf("spine" to geometry.sections.getValue("spine").size.toLong()))))
    val denied = standing.copy(y = 59, z = standing.z + 1)
    val need = CivWorkNeedState(mine, 0, "worksite-liquid", point = denied, reason = "mine-thin-cover", blockMaterial = "WATER")
    val work = CivsMineFallback.withCause(CivWorldWork(worker.id, 0, mine.id, standing, emptyList(),
        outputs = mapOf("COAL" to 1L), durationMillis = CivsMineFallback.DURATION_MILLIS, status = "digging-mine"), need)
    var at = standing.location(world)
    val npc = mockk<PacketPlayerNpc>(relaxed = true) {
        every { location } answers { at.clone() }
        every { world } returns this@MineVisitFixture.world
        every { isValid } returns true
        every { eyeHeight } returns 1.62
    }
    val actor = CitizenActor(city.id, worker.id, world.uid, npc, CitizenPlan(
        CitizenToken(mine.id, 0, 1, null), requireNotNull(mine.control), standing, BuildingType.QUARRY,
        CivsMineFallback.DURATION_MILLIS, null, completeAtWorkplace = true, worldWork = work))
    val runtime = CitizenRuntime(paper.createSimplePlugin("MineVisitTest")) { listOf(city) }
    val routes = mockk<CivsCitizenRoutes>(relaxed = true)
    val manager = CivsMineVisits(runtime, maximumAttempts) { routes }
    var now = 1_000L
    var starts = 0

    init {
        for (x in -2..1) for (z in -2..1) world.loadChunk(x, z)
        for (y in 60..65) world.getBlockAt(standing.x, y, standing.z).blockData =
            Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
        world.getBlockAt(mine.control!!.x, mine.control.y, mine.control.z).type = Material.BARREL
        world.getBlockAt(denied.x, denied.y, denied.z).type = Material.WATER
        every { routes.cancelRoute(any(), any()) } answers {
            val actor = firstArg<CitizenActor>()
            actor.route?.motion?.cancel()
            actor.route = null
        }
        every { routes.arrivedAt(any(), any(), any(), any()) } answers {
            val actor = firstArg<CitizenActor>()
            citizenAtSupportedEndpoint(world, actor.npc.location.point(), arg(3))
        }
        every { routes.startRoute(any(), any(), any(), any(), any()) } answers {
            val actor = firstArg<CitizenActor>()
            val target = arg<CivPosition>(2)
            val motion = CitizenPacketRoute(world, actor.npc.location, target.location(world),
                { citizenRoutePointAllowed(city, setOf(mine.id), it, mine) },
                { _, to -> actor.mineVisit?.passage?.allows?.invoke(to) != false }, { 1.0 })
            actor.route = CitizenRoute(actor.npc.location.point(), target.location(world),
                CitizenChunks.between(at.blockX, at.blockZ, target.x, target.z), now, 90_000,
                setOf(mine.id), motion, null, target)
            starts++
            true
        }
    }

    fun update() {
        now += 50
        runtime.residentObservedAt = now
        manager.retain(listOf(city))
        manager.update(actor, city, worker, world, now)
    }
    fun selectTarget(): CitizenMineVisit {
        repeat(200) {
            update()
            actor.mineVisit?.takeIf { it.target != null }?.let { return it }
        }
        error("No paid/native visit target; actual=$at attempts=${manager.retainedAttempts}")
    }
    fun walk() {
        val route = actor.route ?: return
        repeat(2_000) { if (route.motion.searching) route.motion.expand() }
        route.motion.move(at, 1.0) { true }?.let { next ->
            (at.distance(next) <= .215001) shouldBe true
            citizenPacketBodyClear(world, next.point(), false) shouldBe true
            at = next
        }
        if (!route.motion.continuation && route.motion.finished && route.motion.failure == null)
            route.completion = CitizenNavigationCompletion(null)
    }
}

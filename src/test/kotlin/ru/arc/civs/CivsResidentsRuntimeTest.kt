package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.api.trait.trait.Equipment
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class CivsResidentsRuntimeTest : FunSpec({
    // The pinned MockBukkit BlockMock.getBoundingBox is unimplemented: real actor spawn requires native collision.
    // Keep these native-entry scenarios explicitly skipped; domain/receipt tests run independently.
    test("native hit freezes the actor until durable injury completes and viewer retirement cannot heal it").config(enabled = false) {
        residentRuntime { h ->
            val hit = h.hit(7.0)
            hit.isCancelled shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.nativeHealth shouldBe 20.0
            h.hit(7.0).isCancelled shouldBe true
            h.hits shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.nativeHealth shouldBe 13.0
            h.viewing = false; h.owner.pulse(3_000)
            h.owner.activeCount() shouldBe 0
            h.viewing = true; h.owner.pulse(4_000)
            h.owner.activeCount() shouldBe 1
            h.nativeHealth shouldBe 13.0
        }
    }

    test("durable death removes the worker and never creates another transient actor").config(enabled = false) {
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

    test("unknown injury commit stays frozen until explicit canonical reconciliation").config(enabled = false) {
        residentRuntime { h ->
            h.hit(3.0)
            h.pending.completeExceptionally(IllegalStateException("unknown commit"))
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.nativeHealth shouldBe 20.0
            h.owner.reconcileResidents()
            h.owner.pulse(3_000)
            h.nativeHealth shouldBe 20.0
        }
    }
})

private fun residentRuntime(block: (ResidentRuntime) -> Unit) {
    mockkStatic(CitizensAPI::class)
    try {
        MockBukkitTestRuntime.open().use { paper ->
            val h = ResidentRuntime(paper)
            try { block(h) } finally { h.owner.closeAsync().join() }
        }
    } finally { unmockkStatic(CitizensAPI::class) }
}

private class ResidentRuntime(paper: MockBukkitTestRuntime) {
    private val plugin = paper.createSimplePlugin("ResidentsTest")
    private val provider = paper.createSimplePlugin("Citizens")
    private val world = paper.addSimpleWorld("vanilla")
    private val registry = mockk<NPCRegistry>(relaxed = true)
    private val npc = mockk<NPC>(relaxed = true)
    private val entity = mockk<Player>(relaxed = true)
    private val equipment = mockk<Equipment>(relaxed = true)
    private val skin = mockk<SkinTrait>(relaxed = true)
    val workerId = UUID.randomUUID()
    private var spawned = false
    var nativeHealth = 20.0
    var viewing = true
    var spawns = 0
    var hits = 0
    var pending = CompletableFuture<Settlement>()
    private var damage = 0.0
    var city: Settlement
    val owner: CivsCitizens

    init {
        world.loadChunk(0, 0)
        for (x in -2..2) for (z in -2..2) world.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
        val viewer = paper.addPlayer("ResidentViewer")
        viewer.teleport(Location(world, 0.5, 64.0, 0.5))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
            "townhall1", CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), viewer.uniqueId, "Town", world.uid, CivPosition(0, 64, 0),
            96, "nordic", Stock(), listOf(hall), listOf(CivWorker(workerId, "Resident", null))))
        every { CitizensAPI.getPlugin() } returns provider
        every { CitizensAPI.createAnonymousNPCRegistry(any()) } returns registry
        every { registry.createNPC(EntityType.PLAYER, any<String>()) } returns npc
        every { registry.iterator() } answers { (if (spawned) mutableListOf(npc) else mutableListOf()).iterator() }
        every { npc.entity } returns entity
        every { npc.isSpawned } answers { spawned }
        every { npc.getOrAddTrait(Equipment::class.java) } returns equipment
        every { npc.getOrAddTrait(SkinTrait::class.java) } returns skin
        every { npc.navigator.isNavigating } returns false
        every { npc.spawn(any<Location>(), any<Consumer<Entity>>()) } answers {
            spawned = true; spawns++; secondArg<Consumer<Entity>>().accept(entity); true
        }
        every { npc.destroy() } answers { spawned = false; Unit }
        every { entity.uniqueId } returns UUID.randomUUID()
        every { entity.world } returns world
        every { entity.location } returns Location(world, 0.5, 64.0, 0.5)
        every { entity.isValid } returns true
        every { entity.health } answers { nativeHealth }
        every { entity.health = any() } answers { nativeHealth = firstArg(); Unit }
        every { equipment.get(any<Equipment.EquipmentSlot>()) } returns null
        paper.server.servicesManager.register(ArcNativeCleanupService::class.java, object : ArcNativeCleanupService {
            override fun submit(owner: String, step: java.util.function.BooleanSupplier): java.util.concurrent.CompletionStage<Void> {
                while (!step.asBoolean) Unit
                return CompletableFuture.completedFuture(null)
            }
        }, plugin, ServicePriority.Normal)
        owner = CivsCitizens(plugin, { if (viewing) listOf(city) else emptyList() },
            { _, _, _, _, _, _, _ -> CompletableFuture.failedFuture(AssertionError("idle resident cannot deliver")) },
            { _, _, _ -> }, hurt = { _, _, hit -> hits++; damage = hit; pending })
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    @Suppress("DEPRECATION")
    fun hit(amount: Double): EntityDamageEvent = EntityDamageEvent(entity, EntityDamageEvent.DamageCause.CUSTOM, amount)
        .also(owner::onDamage)

    fun commitHit() {
        city = CivsRules.hurtResident(city, city.owner, workerId, damage)
        pending.complete(city)
    }
}

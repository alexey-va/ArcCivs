package ru.arc.civs

import com.google.common.collect.ImmutableMultimap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Trident
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsResidentThreatsTest : FunSpec({
    test("a cancelled native explosion and cancelled resident event never submit an injury") {
        threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("VirtualResidentDamage")
            val terrain = emptyThreatWorld()
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, .5, 64.0, .5) }
                every { height } returns 1.8
                every { eyeHeight } returns 1.62
            }
            val source = mockk<Entity> { every { uniqueId } returns UUID.randomUUID() }
            val id = UUID.randomUUID()
            var hits = 0
            var cancel = true
            paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun hit(event: CivsResidentDamageEvent) {
                    event.workerId shouldBe id
                    event.isCancelled = cancel
                }
            }, plugin)
            val threats = CivsResidentThreats(plugin) { worker, damage ->
                worker shouldBe id
                (damage > 0) shouldBe true
                hits++
            }
            try {
                threats.pulse(listOf(CivsThreatTarget(UUID.randomUUID(), id, npc)), 1_000)
                fun explosion(cancelled: Boolean = false) = EntityExplodeEvent(source,
                    Location(terrain, .5, 64.0, .5), mutableListOf(), 0f, ExplosionResult.DESTROY).also { it.isCancelled = cancelled }
                threats.onExplosion(explosion(true))
                hits shouldBe 0
                threats.onExplosion(explosion())
                hits shouldBe 0
                cancel = false
                threats.onExplosion(explosion())
                hits shouldBe 1
            } finally { threats.close() }
        }
    }

    test("a thrown trident keeps its item and native return lifecycle after one virtual hit") {
        threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("VirtualTrident")
            val terrain = emptyThreatWorld()
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, .5, 64.0, .5) }
                every { height } returns 1.8
                every { eyeHeight } returns 1.62
            }
            for (loyalty in listOf(0, 3)) {
                val original = ItemStack(Material.TRIDENT).also {
                    if (loyalty > 0) it.addUnsafeEnchantment(Enchantment.LOYALTY, loyalty)
                }
                val saved = original.clone()
                var position = Location(terrain, -1.5, 65.0, .5)
                var flightVelocity = Vector(2.0, 0.0, 0.0)
                var dealt = false
                val trident = mockk<Trident>(relaxed = true) {
                    every { uniqueId } returns UUID.randomUUID()
                    every { world } returns terrain
                    every { isValid } returns true
                    every { location } answers { position.clone() }
                    every { velocity } answers { flightVelocity.clone() }
                    every { velocity = any() } answers { flightVelocity = firstArg<Vector>().clone() }
                    every { item } returns original
                    every { loyaltyLevel } returns loyalty
                    every { shooter } returns null
                    every { fireTicks } returns 0
                    every { hasDealtDamage() } answers { dealt }
                    every { setHasDealtDamage(any()) } answers { dealt = firstArg() }
                }
                var hits = 0
                val threats = CivsResidentThreats(plugin) { _, damage -> damage shouldBe 8.0; hits++ }
                try {
                    threats.pulse(listOf(CivsThreatTarget(UUID.randomUUID(), UUID.randomUUID(), npc)), 1_000)
                    threats.onLaunch(ProjectileLaunchEvent(trident))
                    position = Location(terrain, 1.5, 65.0, .5)
                    threats.tick()
                    hits shouldBe 1
                    dealt shouldBe true
                    flightVelocity.x shouldBe -.02
                    trident.item shouldBe saved
                    trident.loyaltyLevel shouldBe loyalty
                    verify(exactly = 0) { trident.remove() }
                    verify(exactly = 0) { trident.item = any() }
                    position = Location(terrain, -1.5, 65.0, .5)
                    threats.tick()
                    hits shouldBe 1
                } finally { threats.close() }
            }
        }
    }

    test("only supplied armor reduces virtual injury and falling bypasses armor points") {
        threatRuntime {
            val none = List<ItemStack?>(6) { null }
            citizenArmoredDamage(10.0, none, DamageCause.ENTITY_ATTACK) shouldBe 10.0
            // Paper's default material modifiers are absent in MockBukkit. Supply real modifier
            // values through the same typed ItemMeta branch used by custom resident equipment.
            val modifiers = ImmutableMultimap.of(Attribute.ARMOR,
                AttributeModifier(NamespacedKey("arccivs-test", "armor"), 15.0,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD))
            val metadata = mockk<ItemMeta> {
                every { hasAttributeModifiers() } returns true
                every { getAttributeModifiers(EquipmentSlot.HEAD) } returns modifiers
            }
            val supplied = mockk<ItemStack> {
                every { itemMeta } returns metadata
                every { getEnchantmentLevel(any()) } returns 0
            }
            val armor = listOf(supplied, null, null, null, null, null)
            citizenArmoredDamage(10.0, armor, DamageCause.ENTITY_ATTACK) shouldBe 6.0
            citizenArmoredDamage(10.0, armor, DamageCause.FALL) shouldBe 10.0
        }
    }
})

private fun threatRuntime(block: (MockBukkitTestRuntime) -> Unit) {
    failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use(block) }
}

/** Empty loaded terrain isolates native event/protection behavior from unrelated collision simulation. */
private fun emptyThreatWorld(): World {
    val terrain = mockk<World>()
    val chunk = mockk<Chunk>()
    val air = mockk<Block>()
    every { terrain.uid } returns UUID.randomUUID()
    every { terrain.minHeight } returns 0
    every { terrain.maxHeight } returns 256
    every { terrain.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    every { terrain.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
    every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
    every { terrain.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
    every { terrain.getBlockAt(any<Location>()) } returns air
    every { air.type } returns Material.AIR
    every { air.blockData } returns Material.AIR.createBlockData()
    every { terrain.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>(), any()) } returns emptyList()
    every { terrain.rayTraceBlocks(any(), any(), any<Double>(), any(), any<Boolean>()) } returns null
    return terrain
}

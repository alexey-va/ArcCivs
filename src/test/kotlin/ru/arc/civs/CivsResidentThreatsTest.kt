package ru.arc.civs

import com.google.common.collect.ImmutableMultimap
import com.destroystokyo.paper.entity.Pathfinder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Difficulty
import org.bukkit.ExplosionResult
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Creeper
import org.bukkit.entity.Ghast
import org.bukkit.entity.LargeFireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.SmallFireball
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
    test("children and wounded adults retreat while healthy residents can defend") {
        val adult = CivWorker(UUID.randomUUID(), "Иван", null)
        citizenRetreats(adult) shouldBe false
        citizenRetreats(adult.copy(health = 7.0)) shouldBe true
        citizenRetreats(adult.copy(health = 8.0)) shouldBe false
        citizenRetreats(adult.copy(childMinutes = 1, parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()))) shouldBe true
    }

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

    test("mob protection releases an owned pursuit and creeper fuse then allows targeting again when disabled") {
        threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("ProtectedResidentPursuit")
            val terrain = emptyThreatWorld()
            every { terrain.difficulty } returns Difficulty.NORMAL
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, .5, 64.0, .5) }
                every { eyeHeight } returns 1.62
            }
            var ignited = false
            var nativeTarget: LivingEntity? = null
            val monsterPathfinder = mockk<Pathfinder>(relaxed = true)
            val monster = mockk<Creeper>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
                every { isValid } returns true
                every { isDead } returns false
                every { hasAI() } returns true
                every { type } returns EntityType.CREEPER
                every { world } returns terrain
                every { location } answers { Location(terrain, 2.5, 64.0, .5) }
                every { eyeLocation } answers { Location(terrain, 2.5, 65.62, .5) }
                every { target } answers { nativeTarget }
                every { pathfinder } returns monsterPathfinder
                every { igniter } returns null
                every { isIgnited } answers { ignited }
                every { ignite() } answers { ignited = true }
                every { isIgnited = any() } answers { ignited = firstArg() }
            }
            every { terrain.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>(), any()) } returns listOf(monster)
            val cityId = UUID.randomUUID()
            val workerId = UUID.randomUUID()
            val target = CivsThreatTarget(cityId, workerId, npc)
            var protectionEnabled = true
            var contacts = 0
            var injuries = 0
            val threats = CivsResidentThreats(plugin, attacked = { worker, source ->
                worker shouldBe workerId; source shouldBe monster; contacts++
            }, mobProtected = { it shouldBe cityId; protectionEnabled }) { _, _ -> injuries++ }
            try {
                threats.pulse(listOf(target), 1_000)
                threats.tick()
                contacts shouldBe 0
                ignited shouldBe false
                verify(exactly = 0) { monsterPathfinder.moveTo(any<Location>(), any<Double>()) }

                protectionEnabled = false
                threats.pulse(listOf(target), 2_000)
                threats.tick()
                contacts shouldBe 1
                ignited shouldBe true
                verify(exactly = 1) { monsterPathfinder.moveTo(any<Location>(), 1.0) }

                protectionEnabled = true
                // A pending injury can omit the actor from this pulse; the pursuit still owns its city.
                threats.pulse(emptyList(), 3_000)
                repeat(20) { threats.tick() }
                contacts shouldBe 1
                ignited shouldBe false
                verify(exactly = 1) { monsterPathfinder.stopPathfinding() }

                threats.pulse(listOf(target), 4_000)
                threats.onExplosion(EntityExplodeEvent(monster, npc.location, mutableListOf(), 0f, ExplosionResult.DESTROY))
                injuries shouldBe 0
                protectionEnabled = false
                threats.pulse(listOf(target), 5_000)
                threats.tick()
                contacts shouldBe 2
                ignited shouldBe true

                // The owner must leave a real player's native aggro and explosion intact.
                nativeTarget = mockk<Player>()
                protectionEnabled = true
                threats.tick()
                ignited shouldBe true
                verify(exactly = 1) { monsterPathfinder.stopPathfinding() }
                verify(exactly = 1) { monster.isIgnited = false }
            } finally { threats.close() }
        }
    }

    test("already launched mob projectiles skip protected residents and can hit an unprotected resident behind them") {
        for (residentBehind in listOf(false, true)) threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("ProtectedResidentProjectile")
            val terrain = emptyThreatWorld()
            fun npc(x: Double) = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, x, 64.0, .5) }
                every { height } returns 1.8
                every { eyeHeight } returns 1.62
            }
            val cityId = UUID.randomUUID()
            val front = CivsThreatTarget(cityId, UUID.randomUUID(), npc(.5))
            val back = CivsThreatTarget(UUID.randomUUID(), UUID.randomUUID(), npc(3.5))
            val mobShooter = mockk<Ghast>(relaxed = true)
            var position = Location(terrain, -2.5, 65.0, .5)
            var dealt = false
            val trident = mockk<Trident>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
                every { world } returns terrain
                every { isValid } returns true
                every { location } answers { position.clone() }
                every { velocity } returns Vector(2.0, 0.0, 0.0)
                every { shooter } returns mobShooter
                every { fireTicks } returns 0
                every { setHasDealtDamage(any()) } answers { dealt = firstArg() }
            }
            var protectionEnabled = false
            val hits = mutableListOf<UUID>()
            val threats = CivsResidentThreats(plugin, mobProtected = { it == cityId && protectionEnabled }) { worker, amount ->
                amount shouldBe 8.0
                hits += worker
            }
            try {
                threats.pulse(if (residentBehind) listOf(front, back) else listOf(front), 1_000)
                threats.onLaunch(ProjectileLaunchEvent(trident))
                protectionEnabled = true
                position = Location(terrain, 4.5, 65.0, .5)
                threats.tick()
                hits shouldBe if (residentBehind) listOf(back.workerId) else emptyList()
                dealt shouldBe residentBehind
                verify(exactly = 0) { trident.remove() }
                if (!residentBehind) {
                    protectionEnabled = false
                    position = Location(terrain, -2.5, 65.0, .5)
                    threats.tick()
                    hits shouldBe listOf(front.workerId)
                    dealt shouldBe true
                }
            } finally { threats.close() }
        }
    }

    test("a protected resident ignores a ghast fireball explosion while an unprotected resident still takes damage") {
        threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("ProtectedResidentGhast")
            val terrain = emptyThreatWorld()
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, .5, 64.0, .5) }
                every { eyeHeight } returns 1.62
            }
            val ghast = mockk<Ghast>()
            val fireball = mockk<LargeFireball> {
                every { uniqueId } returns UUID.randomUUID()
                every { shooter } returns ghast
            }
            val cityId = UUID.randomUUID()
            val workerId = UUID.randomUUID()
            var protectionEnabled = true
            var events = 0
            var injuries = 0
            var defences = 0
            paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun hit(event: CivsResidentDamageEvent) {
                    event.source shouldBe fireball
                    event.cause shouldBe DamageCause.ENTITY_EXPLOSION
                    events++
                }
            }, plugin)
            val threats = CivsResidentThreats(plugin, attacked = { _, _ -> defences++ },
                mobProtected = { it == cityId && protectionEnabled }) { worker, amount ->
                worker shouldBe workerId
                (amount > 0) shouldBe true
                injuries++
            }
            try {
                threats.pulse(listOf(CivsThreatTarget(cityId, workerId, npc)), 1_000)
                fun explode() = threats.onExplosion(EntityExplodeEvent(fireball, npc.location,
                    mutableListOf(), 0f, ExplosionResult.DESTROY))
                explode()
                events shouldBe 0
                injuries shouldBe 0
                protectionEnabled = false
                explode()
                events shouldBe 1
                injuries shouldBe 1
                defences shouldBe 0
                protectionEnabled = true
                explode()
                events shouldBe 1
                injuries shouldBe 1
            } finally { threats.close() }
        }
    }

    test("enabling mob protection clears only mob-origin fire and preserves lava fire and player Fire Aspect") {
        for (origin in listOf("mob", "lava", "fire", "player"))
        for (mixed in if (origin == "mob") listOf(false) else listOf(false, true)) threatRuntime { paper ->
            val plugin = paper.createSimplePlugin("ProtectedResidentFire")
            val terrain = emptyThreatWorld()
            every { terrain.pvp } returns true
            var feet = Material.AIR
            val block = mockk<Block> {
                every { type } answers { feet }
                every { blockData } answers { feet.createBlockData() }
            }
            every { terrain.getBlockAt(any<Location>()) } returns block
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain
                every { location } answers { Location(terrain, .5, 64.0, .5) }
                every { height } returns 1.8
                every { eyeHeight } returns 1.62
            }
            val cityId = UUID.randomUUID()
            val workerId = UUID.randomUUID()
            val target = CivsThreatTarget(cityId, workerId, npc)
            val causes = mutableListOf<DamageCause>()
            paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun hit(event: CivsResidentDamageEvent) { causes += event.cause }
            }, plugin)
            var protectionEnabled = false
            val threats = CivsResidentThreats(plugin, mobProtected = { it == cityId && protectionEnabled }) { _, _ -> }
            fun mobFire() {
                val blaze = mockk<Monster>()
                var position = Location(terrain, -1.5, 65.0, .5)
                val fireball = mockk<SmallFireball>(relaxed = true) {
                    every { uniqueId } returns UUID.randomUUID()
                    every { world } returns terrain
                    every { isValid } returns true
                    every { location } answers { position.clone() }
                    every { shooter } returns blaze
                    every { fireTicks } returns 0
                }
                threats.onLaunch(ProjectileLaunchEvent(fireball))
                position = Location(terrain, 1.5, 65.0, .5)
                threats.tick()
            }
            try {
                threats.pulse(listOf(target), System.currentTimeMillis())
                when (origin) {
                    "mob" -> {
                        mobFire()
                        causes shouldBe listOf(DamageCause.PROJECTILE)
                    }
                    "player" -> {
                        val weapon = ItemStack(Material.IRON_SWORD).also { it.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1) }
                        val player = mockk<Player>(relaxed = true) {
                            every { isOnline } returns true
                            every { isDead } returns false
                            every { gameMode } returns GameMode.CREATIVE
                            every { world } returns terrain
                            every { getCooledAttackStrength(.5f) } returns 1f
                            every { getAttribute(Attribute.ATTACK_DAMAGE) } returns null
                            every { inventory.itemInMainHand } returns weapon
                        }
                        threats.playerAttack(player, workerId, npc)
                        causes shouldBe listOf(DamageCause.ENTITY_ATTACK)
                    }
                    else -> {
                        feet = if (origin == "lava") Material.LAVA else Material.FIRE
                        threats.pulse(listOf(target), System.currentTimeMillis())
                        causes shouldBe listOf(if (origin == "lava") DamageCause.LAVA else DamageCause.FIRE)
                        feet = Material.AIR
                    }
                }
                val nonMobAt = System.currentTimeMillis()
                if (mixed) {
                    mobFire()
                    causes.last() shouldBe DamageCause.PROJECTILE
                }
                val now = System.currentTimeMillis()
                threats.pulse(listOf(target), now + 1)
                causes.count { it == DamageCause.FIRE_TICK } shouldBe 1
                protectionEnabled = true
                threats.pulse(listOf(target), now + 2)
                causes.count { it == DamageCause.FIRE_TICK } shouldBe if (origin == "mob") 1 else 2
                protectionEnabled = false
                threats.pulse(listOf(target), now + 3)
                causes.count { it == DamageCause.FIRE_TICK } shouldBe if (origin == "mob") 1 else 3
                if (mixed) {
                    // Fire Aspect expires before the discarded mob fire; lava/fire retain their own longer expiry.
                    threats.pulse(listOf(target), nonMobAt + if (origin == "player") 4_001 else 6_000)
                    causes.count { it == DamageCause.FIRE_TICK } shouldBe if (origin == "player") 3 else 4
                }
            } finally { threats.close() }
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

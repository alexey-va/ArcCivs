package ru.arc.civs

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.Flags
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.SmallFireball
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.block.data.Lightable
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class CivsThreatTarget(val cityId: UUID, val workerId: UUID, val npc: ru.arc.paper.npc.PacketPlayerNpc)

/**
 * Virtual resident targets never enter a world's entity list. Real monsters keep their own native
 * movement, projectiles and explosions; this owner supplies target positions and authoritative hits.
 */
internal class CivsResidentThreats(private val plugin: Plugin,
    private val attacked: (UUID, Monster) -> Unit = { _, _ -> },
    private val mobProtected: (UUID) -> Boolean = { false }, private val injury: (UUID, Double) -> Unit) : Listener, AutoCloseable {
    private data class Pursuit(val monster: Monster, val worker: UUID, val cityId: UUID, var attackAfter: Long = 0,
        var routeAfter: Long = 0, var ignited: Boolean = false)
    private data class Flight(val projectile: Projectile, var previous: Location)
    private data class Falling(val top: Double, var speed: Double = 0.0)
    private data class Burning(val until: Long, val fromMob: Boolean = false,
        val nonMobUntil: Long = if (fromMob) 0 else until)
    private var targets = emptyMap<UUID, CivsThreatTarget>()
    private val pursuits = linkedMapOf<UUID, Pursuit>()
    private val projectiles = linkedMapOf<UUID, Flight>()
    private val equipment = mutableMapOf<UUID, List<ItemStack?>>()
    private val burning = mutableMapOf<UUID, Burning>()
    private val submergedSince = mutableMapOf<UUID, Long>()
    private val falling = mutableMapOf<UUID, Falling>()
    private val explosions = mutableMapOf<UUID, Float>()
    private var cursor = 0
    private var lands: CivsResidentLands? = null
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun equipment(workerId: UUID, items: List<ItemStack?>) { equipment[workerId] = items.map { it?.clone() } }

    fun pulse(current: List<CivsThreatTarget>, now: Long) {
        if (closed) return
        targets = current.associateBy { it.workerId }
        equipment.keys.retainAll(targets.keys)
        burning.keys.retainAll(targets.keys)
        submergedSince.keys.retainAll(targets.keys)
        falling.keys.retainAll(targets.keys)
        releaseProtectedPursuits()
        val available = targets.values.filterNot { mobProtected(it.cityId) }
        if (available.isNotEmpty()) repeat(min(2, available.size)) {
            val target = available[Math.floorMod(cursor++, available.size)]
            val npc = target.npc
            // The native spatial query is indivisible, bounded to two calls and 16 candidates per pulse.
            for (entity in npc.world.getNearbyEntities(npc.location, 8.0, 4.0, 8.0) { it is Monster }.take(16)) {
                val monster = entity as Monster
                if (pursuits.size >= 32) break
                if (monster.isValid && !monster.isDead && monster.hasAI() && monster.type !in NEUTRAL && monster.target == null &&
                    monster.world.difficulty != Difficulty.PEACEFUL && visible(monster.eyeLocation, eye(target))) {
                    pursuits.putIfAbsent(monster.uniqueId, Pursuit(monster, target.workerId, target.cityId))
                }
            }
        }
        for (target in targets.values) environment(target, now)
    }


    /** A native path request is indivisible; admit at most two pursuit updates in a server tick. */
    private fun pursue(pursuit: Pursuit, now: Long): Boolean {
        val monster = pursuit.monster
        val target = targets[pursuit.worker]
        if (target == null || !monster.isValid || monster.isDead || !monster.hasAI() || monster.target != null ||
            monster.world.difficulty == Difficulty.PEACEFUL || monster.world != target.npc.world ||
            monster.location.distanceSquared(target.npc.location) > 16.0 * 16.0) {
            if (monster.isValid && monster.target == null) monster.pathfinder.stopPathfinding()
            return false
        }
        val at = target.npc.location
        if (!visible(monster.eyeLocation, eye(target))) return true
        attacked(target.workerId, monster)
        monster.lookAt(eye(target), 30f, 30f)
        val distance = monster.location.distanceSquared(at)
        if (distance > 1.5 * 1.5 && now >= pursuit.routeAfter) {
            monster.pathfinder.moveTo(at, 1.0)
            pursuit.routeAfter = now + 1_000
        }
        if (monster is Creeper) {
            if (distance < 3.0 * 3.0 && !monster.isIgnited) {
                monster.ignite()
                pursuit.ignited = monster.isIgnited
            }
            return true
        }
        if (now < pursuit.attackAfter) return true
        if (monster.type in ARCHERS && distance <= 12.0 * 12.0) {
            val aim = eye(target).toVector().subtract(monster.eyeLocation.toVector())
            aim.y += aim.length() * .16
            monster.launchProjectile(Arrow::class.java, aim.normalize().multiply(1.6))
            pursuit.attackAfter = now + 2_000
        } else if (monster.type == EntityType.BLAZE && distance <= 12.0 * 12.0) {
            monster.launchProjectile(SmallFireball::class.java,
                eye(target).toVector().subtract(monster.eyeLocation.toVector()).normalize().multiply(.8))
            pursuit.attackAfter = now + 2_000
        } else if (distance <= 1.8 * 1.8) {
            monster.swingMainHand()
            val base = monster.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 2.0
            val amount = when (monster.world.difficulty) {
                Difficulty.PEACEFUL -> 0.0
                Difficulty.EASY -> min(base / 2 + 1, base)
                Difficulty.HARD -> base * 1.5
                else -> base
            }
            hit(target, monster, DamageCause.ENTITY_ATTACK, amount)
            pursuit.attackAfter = now + 1_000
        }
        return true
    }

    fun playerAttack(player: Player, workerId: UUID, npc: ru.arc.paper.npc.PacketPlayerNpc) {
        if (closed || !player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR ||
            !player.world.pvp || player.world != npc.world) return
        val target = targets[workerId]?.takeIf { it.npc === npc } ?: return
        if (!playerAllowed(player, npc.location)) return
        val strength = player.getCooledAttackStrength(.5f).toDouble().coerceIn(0.0, 1.0)
        var amount = (player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0) * (.2 + .8 * strength * strength)
        if (strength > .9 && player.fallDistance > 0 && !player.isOnGround && !player.isSprinting &&
            !player.isInWater && !player.isClimbing) amount *= 1.5
        val weapon = player.inventory.itemInMainHand
        val sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS)
        if (sharpness > 0) amount += (.5 * sharpness + .5) * strength
        if (hit(target, player, DamageCause.ENTITY_ATTACK, amount)) {
            val fire = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT)
            if (fire > 0) burning[workerId] = Burning(System.currentTimeMillis() + 4_000L * fire)
            if (player.gameMode != GameMode.CREATIVE && !weapon.type.isAir) player.inventory.setItemInMainHand(weapon.damage(1, player))
        }
        player.resetCooldown()
    }

    /** Bounded projectile sweep. A stale or blocked segment cannot hit through terrain. */
    fun tick() {
        if (closed) return
        releaseProtectedPursuits()
        for (id in pursuits.keys.take(2)) {
            val pursuit = pursuits.remove(id) ?: continue
            try {
                if (pursue(pursuit, System.currentTimeMillis())) pursuits[id] = pursuit
            } catch (error: Exception) {
                stop(pursuit)
                plugin.logger.log(java.util.logging.Level.WARNING,
                    "ArcCivs resident pursuit failed worker=${pursuit.worker}", error)
            }
        }
        if (projectiles.isEmpty()) return
        val deadline = System.nanoTime() + 1_000_000L
        var budget = 32
        val pending = projectiles.keys.toList()
        for (id in pending) {
            if (budget-- == 0) break
            val flight = projectiles.remove(id) ?: continue
            if (!flight.projectile.isValid) continue
            if (!sweep(flight, flight.projectile.location)) {
                flight.previous = flight.projectile.location
                projectiles[id] = flight
            }
            if (System.nanoTime() >= deadline) break
        }
    }

    /** Native gravity no longer exists for packet bodies. Move only down loaded, clear space. */
    fun gravity(moving: Set<UUID>) {
        for (target in targets.values) {
            val npc = target.npc
            if (!npc.isValid || target.workerId in moving) { falling.remove(target.workerId); continue }
            val at = npc.location
            if (!citizenChunkReady(npc.world, at.blockX shr 4, at.blockZ shr 4)) continue
            if (at.y < npc.world.minHeight - 16) { hit(target, null, DamageCause.VOID, 4.0); continue }
            if (at.blockY >= npc.world.maxHeight) continue
            val inWorld = at.blockY >= npc.world.minHeight
            if (inWorld && at.block.isLiquid) { falling.remove(target.workerId); continue }
            if (inWorld && (Tag.CLIMBABLE.isTagged(at.block.type) ||
                    citizenPacketSupported(npc.world, CitizenPoint(at.x, at.y, at.z)))) {
                val drop = falling.remove(target.workerId)
                if (drop != null) hit(target, null, DamageCause.FALL, (drop.top - at.y - 3).coerceAtLeast(0.0))
                continue
            }
            val fall = falling.getOrPut(target.workerId) { Falling(at.y) }
            fall.speed = ((fall.speed + .08) * .98).coerceAtMost(3.9)
            val trace = if (!inWorld) null else npc.world.rayTraceBlocks(at.clone().add(0.0, .05, 0.0),
                org.bukkit.util.Vector(0.0, -1.0, 0.0), fall.speed + .05, FluidCollisionMode.NEVER, true)
            val destination = at.clone().also { it.y = trace?.hitPosition?.y ?: at.y - fall.speed }
            if (citizenPacketBodyClear(npc.world, CitizenPoint(destination.x, destination.y, destination.z),
                    false, allowLiquids = true, allowBelowWorld = true))
                npc.teleport(destination)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        if (closed || targets.isEmpty() || projectiles.size >= 256) return
        val projectile = event.entity
        if (projectile !is AbstractArrow && projectile !is SmallFireball && projectile !is Trident) return
        if (targets.values.none { it.npc.world == projectile.world &&
                it.npc.location.distanceSquared(projectile.location) <= 48.0 * 48.0 }) return
        projectiles[projectile.uniqueId] = Flight(projectile, projectile.location)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val flight = projectiles.remove(event.entity.uniqueId) ?: return
        if (!event.isCancelled) sweep(flight, event.entity.location)
    }

    private fun sweep(flight: Flight, at: Location): Boolean {
        val projectile = flight.projectile
        if (flight.previous.world != at.world) return true
        val delta = at.toVector().subtract(flight.previous.toVector())
        val distance = delta.length()
        if (distance < .0001 || distance > 128) return false
        val direction = delta.normalize()
        val source = projectile.shooter as? Entity
        val nearest = targets.values.asSequence().filter { it.npc.world == at.world &&
            (source !is Mob || !mobProtected(it.cityId)) }.mapNotNull { target ->
            val hit = box(target).expand(.15).rayTrace(flight.previous.toVector(), direction, distance) ?: return@mapNotNull null
            target to hit.hitPosition.distance(flight.previous.toVector())
        }.minByOrNull { it.second } ?: return false
        val obstacle = at.world!!.rayTraceBlocks(flight.previous, direction, nearest.second, FluidCollisionMode.NEVER, true)
        if (obstacle != null) return false
        val target = nearest.first
        if (source is Player && !playerAllowed(source, target.npc.location)) return false
        val amount = when (projectile) {
            is Trident -> 8.0
            is AbstractArrow -> ceil(projectile.damage * projectile.velocity.length()).coerceAtLeast(1.0)
            is SmallFireball -> 5.0
            else -> return false
        }
        if (!hit(target, source ?: projectile, DamageCause.PROJECTILE, amount)) return false
        if (projectile.fireTicks > 0 || projectile is SmallFireball) {
            val until = System.currentTimeMillis() + 5_000
            burning[target.workerId] = if (source is Mob)
                Burning(until, fromMob = true, nonMobUntil = burning[target.workerId]?.nonMobUntil ?: 0)
            else Burning(until)
        }
        if (projectile is Trident) {
            // Keep the original thrown item and native Loyalty/pickup lifecycle. A virtual
            // contact consumes this flight once, just as a native entity hit does.
            projectile.setHasDealtDamage(true)
            projectile.velocity = projectile.velocity.multiply(org.bukkit.util.Vector(-.01, -.1, -.01))
        } else projectile.remove()
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onPrime(event: ExplosionPrimeEvent) {
        if (event.isCancelled) explosions.remove(event.entity.uniqueId)
        else if (explosions.size < 256 && targets.values.any { it.npc.world == event.entity.world })
            explosions[event.entity.uniqueId] = event.radius.coerceIn(0f, 32f)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onExplosion(event: EntityExplodeEvent) {
        val captured = explosions.remove(event.entity.uniqueId)
        if (event.isCancelled) return
        val radius = captured ?: when (val entity = event.entity) {
            is Creeper -> entity.explosionRadius.toFloat() * if (entity.isPowered) 2 else 1
            else -> 4f
        }
        explode(event.location, radius.toDouble(), event.entity, DamageCause.ENTITY_EXPLOSION)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        explode(event.block.location.add(.5, .5, .5), 5.0, null, DamageCause.BLOCK_EXPLOSION)
    }

    private fun explode(at: Location, power: Double, source: Entity?, cause: DamageCause) {
        val radius = power * 2
        if (radius <= 0) return
        for (target in targets.values) {
            if (target.npc.world != at.world) continue
            val distance = target.npc.location.distance(at)
            if (distance > radius || !visible(at, eye(target))) continue
            val impact = 1 - distance / radius
            hit(target, source, cause, ((impact * impact + impact) / 2 * 7 * radius + 1))
        }
    }

    private fun environment(target: CivsThreatTarget, now: Long) {
        val burn = burning[target.workerId]
        if (burn != null && burn.fromMob && mobProtected(target.cityId)) {
            if (burn.nonMobUntil > now) burning[target.workerId] = Burning(burn.nonMobUntil)
            else burning.remove(target.workerId)
        }
        val at = target.npc.location
        if (!citizenChunkReady(target.npc.world, at.blockX shr 4, at.blockZ shr 4)) return
        if (at.blockY !in target.npc.world.minHeight + 1 until target.npc.world.maxHeight - 2) return
        val feet = at.block.type
        val beneath = at.clone().subtract(0.0, .1, 0.0).block
        val floor = beneath.type
        val head = eye(target).block.type
        if (feet == Material.LAVA) { burning[target.workerId] = Burning(now + 15_000); hit(target, null, DamageCause.LAVA, 4.0) }
        else if (feet in FIRE && (at.block.blockData as? Lightable)?.isLit != false ||
            floor in FIRE && (beneath.blockData as? Lightable)?.isLit != false) {
            burning[target.workerId] = Burning(now + 8_000)
            hit(target, null, DamageCause.FIRE, 1.0)
        } else if (feet == Material.WATER) burning.remove(target.workerId)
        else if ((burning[target.workerId]?.until ?: 0) > now) hit(target, null, DamageCause.FIRE_TICK, 1.0)
        if (head == Material.WATER) {
            val since = submergedSince.getOrPut(target.workerId) { now }
            if (now - since >= 15_000) hit(target, null, DamageCause.DROWNING, 2.0)
        } else submergedSince.remove(target.workerId)
        if (feet == Material.CACTUS || feet == Material.SWEET_BERRY_BUSH || floor == Material.CACTUS)
            hit(target, null, DamageCause.CONTACT, 1.0)
        if (head.isSolid && !citizenPacketBodyClear(target.npc.world, CitizenPoint(at.x, at.y, at.z), false))
            hit(target, null, DamageCause.SUFFOCATION, 1.0)
    }

    private fun hit(target: CivsThreatTarget, source: Entity?, cause: DamageCause, amount: Double): Boolean {
        if (!amount.isFinite() || amount <= 0 || !target.npc.isValid) return false
        val mob = source as? Mob ?: ((source as? Projectile)?.shooter as? Mob)
        if (mob != null && mobProtected(target.cityId)) return false
        if (source is Player && !playerAllowed(source, target.npc.location)) return false
        val damage = citizenArmoredDamage(amount, equipment[target.workerId].orEmpty(), cause)
        val event = CivsResidentDamageEvent(target.cityId, target.workerId, target.npc.location, source, cause, damage)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled || !event.damage.isFinite() || event.damage <= 0) return false
        if (mob is Monster) attacked(target.workerId, mob)
        injury(target.workerId, event.damage)
        return true
    }

    private fun playerAllowed(player: Player, at: Location): Boolean {
        if (!player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR ||
            player.world != at.world || !player.world.pvp) return false
        if (plugin.server.pluginManager.getPlugin("Lands")?.isEnabled != true) return true
        return (lands ?: CivsResidentLands(plugin).also { lands = it }).allows(player, at)
    }

    private fun eye(target: CivsThreatTarget) = target.npc.location.add(0.0, target.npc.eyeHeight, 0.0)
    private fun box(target: CivsThreatTarget): BoundingBox = target.npc.location.let {
        BoundingBox(it.x - .3, it.y, it.z - .3, it.x + .3, it.y + target.npc.height, it.z + .3)
    }

    fun visible(from: Location, to: Location): Boolean {
        if (from.world != to.world) return false
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        return distance < .001 || from.world!!.rayTraceBlocks(from, delta.normalize(), distance,
            FluidCollisionMode.NEVER, true) == null
    }

    fun remove(worker: UUID) {
        targets = targets - worker
        equipment.remove(worker); burning.remove(worker); submergedSince.remove(worker); falling.remove(worker)
        val removed = pursuits.values.filter { it.worker == worker }
        removed.forEach { pursuits.remove(it.monster.uniqueId) }
        removed.forEach { stop(it) }
    }

    private fun releaseProtectedPursuits() {
        val iterator = pursuits.values.iterator()
        while (iterator.hasNext()) {
            val pursuit = iterator.next()
            if (!mobProtected(pursuit.cityId)) continue
            iterator.remove()
            stop(pursuit, disarm = true)
        }
    }

    private fun stop(pursuit: Pursuit, disarm: Boolean = false) {
        runCatching {
            val monster = pursuit.monster
            if (monster.isValid && monster.target == null) {
                monster.pathfinder.stopPathfinding()
                if (disarm && pursuit.ignited && monster is Creeper && monster.igniter == null)
                    monster.isIgnited = false
            }
        }.onFailure { plugin.logger.log(java.util.logging.Level.WARNING,
            "ArcCivs failed to release resident pursuit worker=${pursuit.worker}", it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val retiring = pursuits.values.toList()
        targets = emptyMap()
        pursuits.clear()
        equipment.clear(); burning.clear(); submergedSince.clear(); falling.clear()
        projectiles.clear(); explosions.clear()
        HandlerList.unregisterAll(this)
        retiring.forEach { stop(it) }
    }

    companion object {
        private val NEUTRAL = setOf(EntityType.ENDERMAN, EntityType.PIGLIN, EntityType.ZOMBIFIED_PIGLIN, EntityType.SPIDER)
        private val ARCHERS = setOf(EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED)
        private val FIRE = setOf(Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK)
    }
}

internal fun citizenRetreats(worker: CivWorker): Boolean = CivsResidents.isChild(worker) ||
    CivsResidents.health(worker) <= maxOf(6.0, CivsResidents.maxHealth(worker) * .35)

/** Use the actual held item's native base attack attributes; an empty hand remains an empty hand. */
internal fun citizenAttackDamage(hand: ItemStack?): Double {
    if (hand == null || hand.type.isAir) return 1.0
    val modifiers = hand.itemMeta?.takeIf { it.hasAttributeModifiers() }?.getAttributeModifiers(EquipmentSlot.HAND)
        ?: hand.type.getDefaultAttributeModifiers(EquipmentSlot.HAND)
    val base = 1.0 + modifiers?.get(Attribute.ATTACK_DAMAGE).orEmpty()
        .filter { it.operation == AttributeModifier.Operation.ADD_NUMBER }.sumOf { it.amount }
    val sharpness = hand.getEnchantmentLevel(Enchantment.SHARPNESS)
    return (base + if (sharpness > 0) .5 * sharpness + .5 else 0.0).coerceAtLeast(1.0)
}

private class CivsResidentLands(plugin: Plugin) {
    private val integration = LandsIntegration.of(plugin)
    fun allows(player: Player, at: Location): Boolean =
        integration.getWorld(player.world)?.hasRoleFlag(player.uniqueId, at, Flags.ATTACK_PLAYER) ?: true
}

internal fun citizenArmoredDamage(amount: Double, items: List<ItemStack?>, cause: DamageCause): Double {
    var armor = 0.0
    var toughness = 0.0
    var protection = 0
    val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    for (index in 0 until min(4, items.size)) {
        val item = items[index] ?: continue
        val modifiers = item.itemMeta?.takeIf { it.hasAttributeModifiers() }?.getAttributeModifiers(slots[index])
            ?: item.type.getDefaultAttributeModifiers(slots[index])
        armor += modifiers?.get(Attribute.ARMOR).orEmpty().filter { it.operation == AttributeModifier.Operation.ADD_NUMBER }.sumOf { it.amount }
        toughness += modifiers?.get(Attribute.ARMOR_TOUGHNESS).orEmpty().filter { it.operation == AttributeModifier.Operation.ADD_NUMBER }.sumOf { it.amount }
        protection += item.getEnchantmentLevel(Enchantment.PROTECTION)
        protection += when (cause) {
            DamageCause.PROJECTILE -> item.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION) * 2
            DamageCause.ENTITY_EXPLOSION, DamageCause.BLOCK_EXPLOSION -> item.getEnchantmentLevel(Enchantment.BLAST_PROTECTION) * 2
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA -> item.getEnchantmentLevel(Enchantment.FIRE_PROTECTION) * 2
            DamageCause.FALL -> item.getEnchantmentLevel(Enchantment.FEATHER_FALLING) * 3
            else -> 0
        }
    }
    val bypass = cause in setOf(DamageCause.FALL, DamageCause.DROWNING, DamageCause.FIRE_TICK,
        DamageCause.SUFFOCATION, DamageCause.STARVATION, DamageCause.VOID, DamageCause.POISON, DamageCause.WITHER, DamageCause.MAGIC)
    val reduced = if (bypass) amount else amount * (1 - min(20.0, max(armor / 5, armor - amount / (2 + toughness / 4))) / 25)
    return reduced * (1 - protection.coerceIn(0, 20) / 25.0)
}

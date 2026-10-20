package ru.arc.civs

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import java.util.UUID

/** Packet residents have no Bukkit Entity: integrations cancel this typed event before a durable injury. */
class CivsResidentDamageEvent(
    val cityId: UUID,
    val workerId: UUID,
    location: Location,
    val source: Entity?,
    val cause: DamageCause,
    var damage: Double,
) : Event(), Cancellable {
    private val at = location.clone()
    val location: Location get() = at.clone()
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Replaces the Citizens-only door event; receipt locks and footprint checks run again after dispatch. */
class CivsResidentOpenDoorEvent(val cityId: UUID, val door: Block) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

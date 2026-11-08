package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import java.util.UUID

/** Notify neighbours after the existing durable commit, including unsupported vines beside cleared logs. */
internal class CivsPhysics(private val plugin: Plugin, tasks: LifecycleTaskScope) : AutoCloseable {
    private data class Update(val world: UUID, val cell: CivPlanBlock)
    private val pending = ArrayDeque<Update>()
    private val binding by lazy { Binding() }
    private val timer = tasks.runTimer(1, 1) {
        val started = System.nanoTime()
        var count = 0
        while (pending.isNotEmpty() && count++ < 128 && System.nanoTime() - started < 1_000_000) {
            val update = pending.removeFirst()
            val world = plugin.server.getWorld(update.world) ?: continue
            val p = update.cell.position
            if (!world.isChunkLoaded(p.x shr 4, p.z shr 4)) continue
            val block = world.getBlockAt(p.x, p.y, p.z)
            try { binding.update(block, Bukkit.createBlockData(update.cell.before)) }
            catch (failure: ReflectiveOperationException) {
                pending.clear()
                plugin.logger.log(java.util.logging.Level.WARNING, "ArcCivs neighbour physics is unavailable", failure)
                break
            }
        }
    }

    fun enqueue(world: UUID, cells: List<CivPlanBlock>) {
        check(Bukkit.isPrimaryThread())
        cells.asSequence().filter { it.before != it.after }.forEach { pending.addLast(Update(world, it)) }
    }

    override fun close() { timer?.cancel(); pending.clear() }

    private class Binding {
        private val craftBlock = Class.forName("org.bukkit.craftbukkit.block.CraftBlock")
        private val craftData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData")
        private val level = Class.forName("net.minecraft.server.level.ServerLevel")
        private val levelAccess = Class.forName("net.minecraft.world.level.LevelAccessor")
        private val position = Class.forName("net.minecraft.core.BlockPos")
        private val state = Class.forName("net.minecraft.world.level.block.state.BlockState")
        private val nativeBlock = Class.forName("net.minecraft.world.level.block.Block")
        private val getLevel = craftBlock.getMethod("getHandle")
        private val getPosition = craftBlock.getMethod("getPosition")
        private val getState = craftData.getMethod("getState")
        private val getBlock = state.getMethod("getBlock")
        private val analog = state.getMethod("hasAnalogOutputSignal")
        private val neighbours = level.getMethod("updateNeighborsAt", position, nativeBlock)
        private val comparator = level.getMethod("updateNeighbourForOutputSignal", position, nativeBlock)
        private val indirectShapes = state.getMethod("updateIndirectNeighbourShapes", levelAccess, position,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val directShapes = state.getMethod("updateNeighbourShapes", levelAccess, position,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val hasPhysicsEvent = level.getField("hasPhysicsEvent")

        fun update(block: Block, before: BlockData) {
            val world = getLevel.invoke(block)
            val pos = getPosition.invoke(block)
            val currentData = block.blockData
            val current = getState.invoke(currentData)
            val previous = getState.invoke(before)
            // The original write already published the block and its POI transition. Replay
            // only the neighbor and shape portion of Paper's post-write processing.
            neighbours.invoke(world, pos, getBlock.invoke(previous))
            if (analog.invoke(current) == true) comparator.invoke(world, pos, getBlock.invoke(current))
            indirectShapes.invoke(previous, world, pos, UPDATE_CLIENTS, SHAPE_DEPTH)
            if (hasPhysicsEvent.getBoolean(world) && !BlockPhysicsEvent(block, currentData).callEvent()) return
            directShapes.invoke(current, world, pos, UPDATE_CLIENTS, SHAPE_DEPTH)
            indirectShapes.invoke(current, world, pos, UPDATE_CLIENTS, SHAPE_DEPTH)
        }

        private companion object {
            const val UPDATE_CLIENTS = 2
            const val SHAPE_DEPTH = 511
        }
    }
}

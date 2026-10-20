package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
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
        private val level = Class.forName("net.minecraft.world.level.Level")
        private val position = Class.forName("net.minecraft.core.BlockPos")
        private val state = Class.forName("net.minecraft.world.level.block.state.BlockState")
        private val chunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk")
        private val getLevel = craftBlock.getMethod("getHandle")
        private val getPosition = craftBlock.getMethod("getPosition")
        private val getState = craftData.getMethod("getState")
        private val getChunk = level.getMethod("getChunkAt", position)
        private val notify = level.getMethod(
            "notifyAndUpdatePhysics", position, chunk, state, state, state,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )

        fun update(block: Block, before: BlockData) {
            val world = getLevel.invoke(block)
            val pos = getPosition.invoke(block)
            val current = getState.invoke(block.blockData)
            // UPDATE_NEIGHBORS | UPDATE_CLIENTS: Paper propagates the client flag to
            // shape updates too, so removed unsupported plants must reach viewers.
            notify.invoke(world, pos, getChunk.invoke(world, pos), getState.invoke(before), current, current, 3, 512)
        }
    }
}

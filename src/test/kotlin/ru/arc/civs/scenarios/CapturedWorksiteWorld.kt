package ru.arc.civs.scenarios

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Material
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.civs.CivPosition
import ru.arc.civs.CivsWorksiteWorldFixture

/** Strict native/public-geometry replay for worksite regressions. Public opaque-fill cells
 * retain unknown identity; an explicit constraint must name its separate runtime evidence. */
internal class CapturedWorksiteWorld(name: String, val capture: RecordedNativeMap) :
    CivsWorksiteWorldFixture(name, Material.AIR, { data -> capture.collision(data)
        ?: error("Missing pinned native collision facts for ${data.asString}") }) {
    private var bootstrapping = true
    val declaredConstraints = linkedMapOf<CivPosition, String>()

    init {
        capture.loadedChunks.forEach { (x, z) -> loadChunk(x, z) }
        capture.seed(this)
        bootstrapping = false
    }

    override fun getBlockAt(point: Coordinate): BlockMock {
        val position = CivPosition(point.x, point.y, point.z)
        check(bootstrapping || position in capture.states) { "Missing captured worksite cell x=${point.x} y=${point.y} z=${point.z}" }
        return super.getBlockAt(point)
    }

    override fun getHighestBlockYAt(x: Int, z: Int): Int = getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
    override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int = capture.column(x, z).firstOrNull { p ->
        val block = getBlockAt(p.x, p.y, p.z)
        when (heightMap) {
            HeightMap.WORLD_SURFACE -> !block.type.isAir
            HeightMap.MOTION_BLOCKING -> block.type.isSolid || block.isLiquid
            HeightMap.MOTION_BLOCKING_NO_LEAVES -> (block.type.isSolid || block.isLiquid) && !block.type.name.endsWith("_LEAVES")
            else -> error("Unspecified captured worksite height map $heightMap")
        }
    }?.y ?: error("No captured $heightMap surface at x=$x z=$z")

    fun constrainOpaqueIdentity(position: CivPosition, state: String, evidence: String) {
        require(position in capture.unknownOpaqueIdentity && evidence.isNotBlank())
        require(declaredConstraints.putIfAbsent(position, evidence) == null)
        getBlockAt(position.x, position.y, position.z).blockData = Bukkit.createBlockData(state)
    }
}

package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material

/** A real natural bank above these tests' old, deliberately shallow mine coordinates. */
internal fun seedMineNaturalCover(world: CivsWorksiteWorldFixture, mine: CivBuilding, top: Int = mine.anchor.y + 16) {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val columns = plan.positions.flatMap { local ->
        val point = CivsWorksiteLayouts.point(mine, local)
        (-1..1).flatMap { dx -> (-1..1).map { dz -> (point.x + dx) to (point.z + dz) } }
    }.toSet()
    for ((x, z) in columns) for (y in 64..top) world.getBlockAt(x, y, z).type = Material.STONE
}

/** Later-section fixtures must supply both the durable paid spine and its actual native blocks. */
internal fun seedMinePaidSpine(world: CivsWorksiteWorldFixture, mine: CivBuilding,
    state: CivMinePlanState): CivMinePlanState {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    require(state.version == CivsMinePlan.VERSION)
    for (index in plan.sections.getValue("spine")) {
        val point = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
    }
    val (next, sections) = plan.normalizedProgress(state.nextStep,
        state.sectionSteps.orEmpty() + ("spine" to plan.sections.getValue("spine").size.toLong()))
    return state.copy(nextStep = next, sectionSteps = sections,
        v5Step = state.v5Step ?: state.nextStep.takeIf { state.sectionSteps == null })
}

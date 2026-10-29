package ru.arc.civs

import java.util.UUID

/** Live owning-thread views; city saves, publication and reservation changes remain with CivsService. */
internal class CivsCityRecords(
    private val settlements: () -> Map<UUID, Settlement>,
    val busy: Set<UUID>,
    val unhealthy: Set<UUID>,
    private val isClosed: () -> Boolean,
    private val isLoading: () -> Boolean,
    private val failedLoad: () -> String?,
    private val generation: () -> Long,
    private val operations: () -> Int,
) {
    val cities get() = settlements()
    val closed get() = isClosed()
    val loading get() = isLoading()
    val loadFailure get() = failedLoad()
    val geometryGeneration get() = generation()
    val pendingOperations get() = operations()
}

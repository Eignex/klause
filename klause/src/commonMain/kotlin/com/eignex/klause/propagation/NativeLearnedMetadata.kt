package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedMetadata(state: PropagationState? = null) {
    private val rows = LearnedPolicyRows(columns = 9, retiredFlag = 4, objectColumns = false, state = state)
    val size: Int get() = rows.size
    val primitiveBytes: Long get() = rows.primitiveBytes
    val referenceCapacity: Long get() = rows.referenceCapacity + 1L
    val preparationWork: Long get() = rows.preparationWork
    val hasEmptyReservation: Boolean get() = rows.hasEmptyReservation
    val hasRetiredRows: Boolean get() = rows.hasRetiredRows
    val admissionWork: Long get() = rows.admissionWork

    operator fun get(index: Int, column: Int): Int = rows[index, column]
    fun watchPositions(index: Int): Long = rows.pair(index, WATCH_FIRST, WATCH_SECOND)
    operator fun set(index: Int, column: Int, value: Int) { rows[index, column] = value }
    fun admission(index: Int): Long = rows.admission(index)
    fun add(lbd: Int, permanent: Boolean, origin: Int, admission: Long) =
        rows.add(0, lbd, permanent, origin, admission, null)
    fun releaseEmptyReservation() = rows.releaseEmptyReservation()
    fun release(index: Int) = rows.release(index)
    fun enqueueRetired(index: Int) = rows.enqueueRetired(index)
    fun releaseRetired() = rows.releaseRetired()
    fun resetRetirements(): Long = rows.resetRetirements()
    fun sweepDirectory(budget: ReclamationPreparation) = rows.sweepDirectory(budget)
    fun move(source: Int, target: Int): Long = rows.move(source, target)
    fun truncateTo(count: Int): Long = rows.truncateTo(count)
    fun prepareRemap(budget: ReclamationPreparation) = rows.prepareRemap(budget)
    fun <T> usingBudget(budget: ReclamationPreparation, operation: () -> T): T = rows.usingBudget(budget, operation)
    fun <T> usingObservation(sample: SharingMemoryObservation, operation: () -> T): T =
        rows.usingObservation(sample, operation)

    fun sharingMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) {
        sample.ownedReferences(this, 1L, protectedRows)
        rows.sharingMemory(sample, protectedRows, protected)
    }

    companion object {
        const val LBD = 0
        const val PERMANENT = 1
        const val TIER = 2
        const val BOOL_REASONS = 3
        const val STAGES = 4
        const val ORIGIN = 5
        const val SCAN = 6
        const val WATCH_FIRST = 7
        const val WATCH_SECOND = 8
    }
}

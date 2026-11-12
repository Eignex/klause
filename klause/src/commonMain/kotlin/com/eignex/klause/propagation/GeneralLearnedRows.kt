package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class GeneralLearnedRows(state: PropagationState? = null) : AbstractList<LearnedPropagator>() {
    private val rows = LearnedPolicyRows(columns = 6, retiredFlag = 8, objectColumns = true)
    init { state?.let { it.preparationIndexWork += rows.preparationWork } }
    override val size: Int get() = rows.size
    val primitiveBytes: Long get() = rows.primitiveBytes
    val referenceCapacity: Long get() = rows.referenceCapacity + 1L
    val hasEmptyReservation: Boolean get() = rows.hasEmptyReservation
    val hasRetiredRows: Boolean get() = rows.hasRetiredRows
    val retiredHeadIndex: Int get() = rows.retiredHeadIndex
    val admissionWork: Long get() = rows.admissionWork

    private fun checkIndex(index: Int) {
        if (index !in 0 until size) throw IndexOutOfBoundsException("learned row $index outside $size")
    }

    override operator fun get(index: Int): LearnedPropagator {
        checkIndex(index)
        return rows.propagator(index) ?: RetiredLearnedPropagator
    }

    operator fun set(index: Int, propagator: LearnedPropagator) = rows.setPropagator(index, propagator)
    operator fun get(index: Int, column: Int): Int = rows[index, column]
    operator fun set(index: Int, column: Int, value: Int) { rows[index, column] = value }
    fun payload(index: Int): Any? { checkIndex(index); return rows.payload(index) }
    fun setPayload(index: Int, value: Any?): Any? { checkIndex(index); return rows.setPayload(index, value) }
    fun admission(index: Int): Long = rows.admission(index)
    fun add(propagator: LearnedPropagator, stage: Int, lbd: Int, permanent: Boolean, origin: Int, admission: Long) =
        rows.add(stage, lbd, permanent, origin, admission, propagator)
    fun releaseEmptyReservation() = rows.releaseEmptyReservation()
    fun release(index: Int) = rows.release(index)
    fun enqueueRetired(index: Int) = rows.enqueueRetired(index)
    fun retiredValue(column: Int): Int = rows.retiredValue(column)
    fun releaseRetired() = rows.releaseRetired()
    fun resetRetirements(): Long = rows.resetRetirements()
    fun sweepDirectory(budget: ReclamationPreparation) = rows.sweepDirectory(budget)
    fun move(source: Int, target: Int): Long = rows.move(source, target)
    fun truncateTo(count: Int): Long = rows.truncateTo(count)
    fun prepareRemap(budget: ReclamationPreparation) = rows.prepareRemap(budget)

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
    }
}

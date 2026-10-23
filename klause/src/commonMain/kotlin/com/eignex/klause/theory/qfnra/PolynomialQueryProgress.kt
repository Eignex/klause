package com.eignex.klause.theory.qfnra

internal data class PolynomialQueryProgress(
    var snapshots: Long = 0,
    var snapshotAtoms: Int = 0,
    var eliminatedCoordinates: Int = 0,
    var rootWork: Long = 0,
    var searchWork: Long = 0,
    var searchRestarts: Long = 0,
    var searchSnapshot: Long = 0,
    var searchAtoms: Int = 0,
    var searchEliminatedCoordinates: Int = 0,
    var regions: Long = 0,
    var firstSnapshotWork: Long? = null,
    var firstRegionWork: Long? = null,
    var rootFinishedWork: Long? = null,
) {
    fun committed(reduction: PolynomialFormulaReduction, work: Long) {
        snapshots++
        snapshotAtoms = reduction.formula.atoms.size
        eliminatedCoordinates = reduction.eliminatedCount
        if (firstSnapshotWork == null) firstSnapshotWork = work
        if (reduction.finished) rootFinishedWork = work
    }

    fun enteredRegion(work: Long) {
        regions++
        if (firstRegionWork == null) firstRegionWork = work
    }

    fun started(reduction: PolynomialFormulaReduction) {
        searchSnapshot = snapshots
        searchAtoms = reduction.formula.atoms.size
        searchEliminatedCoordinates = reduction.eliminatedCount
    }
}

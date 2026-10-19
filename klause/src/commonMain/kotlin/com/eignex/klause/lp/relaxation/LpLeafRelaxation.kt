package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.util.Cancellation

internal class LpLeafRelaxation(
    problem: Problem,
    relaxer: CpToLpRelaxation,
    private val onBuild: (Long) -> Unit = {},
    private val onEdit: (Long) -> Unit = {},
) {
    private val sources = LpRetainedSources(problem, relaxer)
    private var trail = LpBoundTrail(LpRetainedSources.emptyModel())
    private var nextWitness = 0L

    fun build(domains: RelaxationDomains, cancellation: Cancellation): LpRelaxation? {
        val staged = LpBoundTrail(trail.state)
        var edit = sources.prepare(staged.state, domains, cancellation) ?: return null
        onBuild(edit.emittedExtent)
        val weakens = (0 until staged.state.model.n).any { column ->
            val previous = staged.state.model.column(column).bounds
            val requested = edit.bounds[column]
            previous.lower?.let { requested.lower == null || it.strongerThan(requested.lower, false) } == true ||
                previous.upper?.let { requested.upper == null || it.strongerThan(requested.upper, true) } == true
        }
        if (weakens) {
            onEdit(staged.state.model.numVars.toLong())
            if (!staged.resetRoot(cancellation)) return null
            edit = sources.prepare(staged.state, domains, cancellation) ?: return null
            onBuild(edit.emittedExtent)
        }
        val assertions = edit.boundAssertions(nextWitness, cancellation) ?: return null
        val before = staged.state.assertions.size
        onEdit((staged.state.model.numVars + edit.columns.size + edit.rows.size).toLong())
        if (!staged.replaceRows(edit.retired, edit.columns, edit.rows, false, cancellation,
                edit.permanentRows, edit.objective, assertions) || !edit.isCurrent() || cancellation()
        ) return null
        val count = staged.state.assertions.size - before
        edit.commit()
        trail = staged
        nextWitness += count
        sources.prepareCompaction(trail.state, cancellation)?.let { compaction ->
            if (compaction.isCurrent()) {
                onBuild(compaction.extent)
                if (trail.compact(compaction.remap, cancellation)) compaction.commit()
            }
        }
        return sources.relaxation(trail.state, domains)
    }
}

package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremise
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.finiteExactInput
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.columnBounds
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision

internal class CpLpAdapter(private val engine: LpEngine) : LpSearchPolicy {
    private var native: PropagationSession? = null
    private var sharedNative: PropagationSession? = null
    private var shared: SearchContext? = null
    private var feasibility = false
    private var persistentState = false
    var branching: ((SearchContext) -> List<SearchDecision>?)? = null
    var fractional: ((SearchContext) -> LpFractionalBranch?)? = null
    var currentModel: LpModel? = null
        private set

    fun attach(session: PropagationSession, feasibility: Boolean) {
        sharedNative = session
        this.feasibility = feasibility
    }

    override fun initialize(context: SearchContext) {
        if (native === sharedNative && (shared == null || shared === context)) resetRoot() else reset()
        shared = context
    }

    override fun propagate(context: SearchContext): ComponentResult {
        val session = sharedNative ?: return ComponentResult.Consistent
        if (!feasibility) return ComponentResult.Consistent
        val prune = engine.pruneNode(session, Double.POSITIVE_INFINITY, -1, true)
        return if (prune) ComponentResult.Conflict() else ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible

    override fun nextBranch(context: SearchContext): List<SearchDecision>? = branching?.invoke(context)
    override fun fractionalBranch(context: SearchContext): LpFractionalBranch? = fractional?.invoke(context)

    override fun retract(decisionLevel: Int) {
        currentModel = null
        if (!persistentState) engine.propagator.reset()
    }

    fun localModel() {
        persistentState = false
        currentModel = null
    }

    fun resetRoot() {
        currentModel = null
        if (!persistentState || !engine.propagator.resetRoot()) reset()
    }

    fun reset() {
        native = null
        persistentState = false
        currentModel = null
        engine.propagator.reset()
    }

    fun relaxation(base: LpRelaxation, session: PropagationSession): LpRelaxation? {
        currentModel = null
        if (base.model.hasContinuous || base.model.doubleView != null || base.model.exactState != null) return null
        if (native !== session) {
            engine.propagator.reset()
            native = session
        }
        val core = engine.propagator
        if (!persistentState) core.reset()
        if (core.state == null && !core.install(base, base.model.trailModel() ?: return null)) return null
        val depth = if (session ===
            sharedNative
        ) {
            shared?.decisionLevel ?: session.decisionLevel
        } else {
            session.decisionLevel
        }
        if (!core.atLevel(depth)) return null
        val (lower, upper) = base.columnBounds(session)
        val current = requireNotNull(core.state).model
        val weakens = lower.indices.any { column ->
            val bounds = current.column(column).bounds
            val origin = current.column(column).origin.value
            val lo = BigFraction.ofLong(lower[column]) - origin
            val hi = BigFraction.ofLong(upper[column]) - origin
            bounds.lower?.let { lo < it.number.value } == true || bounds.upper?.let { hi > it.number.value } == true
        }
        // Standalone callers may replace a root or sibling without delivering shared retract events.
        if (weakens) {
            if (!core.resetRoot() && !core.install(base, base.model.trailModel() ?: return null)) return null
            if (!core.atLevel(depth)) return null
        }
        for (column in lower.indices) {
            val origin = requireNotNull(core.state).model.column(column).origin.value
            if (!core.assertBound(
                    column,
                    false,
                    ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(lower[column]) - origin)),
                ) ||
                !core.assertBound(
                    column,
                    true,
                    ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(upper[column]) - origin)),
                )
            ) {
                return null
            }
        }
        // CP certifiers read shifted Long arrays. Translation preserves row duals and source primals.
        val authority = requireNotNull(core.state).model
        val proof = authority.recentered(lower.map(ExactLpNumber::of)).toLegacy() ?: return null
        currentModel = proof
        persistentState = true
        return base.withModel(proof)
    }
}

internal fun LpModel.trailModel(): ExactLpModel? {
    exactState?.let { return it.model }
    if (!finiteExactInput() || rowStrict.any { it }) return null
    val dv = doubleView
    return ExactLpModel(
        List(n) { column ->
            buildList {
                if (dv == null) {
                    forEachInColumn(column) { row, value -> add(ExactLpEntry(row, ExactLpNumber.of(value))) }
                } else {
                    forEachInColumnD(column) { row, value -> add(ExactLpEntry(row, ExactLpNumber.ofIeee(value))) }
                }
            }
        },
        dv?.rhs?.map(ExactLpNumber::ofIeee) ?: rhs.map(ExactLpNumber::of),
        List(numVars) { column ->
            ExactLpColumn(
                exactBounds(column),
                origin = if (column >= n) {
                    ExactLpNumber.of(0L)
                } else {
                    dv?.let { ExactLpNumber.ofIeee(it.loShift[column]) } ?: ExactLpNumber.of(loShift[column])
                },
                integral = column >= n || !colContinuous[column],
                tag = if (column < n) tag[column] else -1,
            )
        },
        List(m) { row ->
            ExactLpRow(
                rowGlobal[row],
                rowStrict[row],
                rowPremises[row]?.let { premise ->
                    ExactLpPremises(
                        premise.vars.indices.map {
                            ExactLpPremise(
                                premise.vars[it],
                                premise.isUpper[it],
                                ExactLpNumber.of(premise.thresholds[it]),
                            )
                        },
                        premise.boolLits.toList(),
                    )
                },
            )
        },
        ExactLpObjective(
            dv?.cost?.map(ExactLpNumber::ofIeee) ?: cost.map(ExactLpNumber::of),
            dv?.let { ExactLpNumber.ofIeee(it.objConstant) } ?: ExactLpNumber.of(objConstant),
            sense = sense,
        ),
    )
}

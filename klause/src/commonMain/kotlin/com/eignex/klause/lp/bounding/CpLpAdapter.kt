package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremise
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.finiteExactInput
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.columnBounds
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource.Monotonic

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

    fun installEpoch(
        base: LpRelaxation,
        session: PropagationSession,
        warm: Basis?,
        token: Cancellation,
        validatePublication: () -> Boolean,
        publish: () -> Unit,
    ): Boolean {
        if (session.decisionLevel != 0 || (shared?.decisionLevel ?: 0) != 0) return false
        val model = base.model.trailModel() ?: return false
        val premises = buildMap<Pair<Int, Boolean>, SearchAtomPremise> {
            for (column in 0 until model.n) {
                for (upper in listOf(false, true)) {
                    val side = if (upper) model.column(column).bounds.upper else model.column(column).bounds.lower
                    if (side == null) continue
                    val source = base.sourceMap?.column(column)
                    val fact = source?.let {
                        CutPremise.Bound(
                            it.expression(),
                            upper,
                            side.number.value + model.column(column).origin.value,
                        )
                    }
                    val premise = when {
                        fact != null && base.sourceMap.isGlobal(fact) -> SearchAtomPremise.All(emptyList())

                        base.colIsBool[column] -> SearchAtomPremise.Asserted(
                            SearchDecision.Bool(Lit.make(base.colVarId[column], !upper)),
                        )

                        else -> SearchAtomPremise.Unavailable
                    }
                    put(column to upper, premise)
                }
            }
        }
        return engine.propagator.replaceEpoch(base, model, warm, token, validatePublication, premises) {
            native = session
            persistentState = true
            currentModel = null
            publish()
        }
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
        val initial = requireNotNull(core.state).model
        val result = core.assertBounds(
            lower.indices.map { column ->
                ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(lower[column]) - initial.column(column).origin.value))
            },
            upper.indices.map { column ->
                ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(upper[column]) - initial.column(column).origin.value))
            },
        )
        if (result !is LpBoundBatchResult.Applied) return null
        // CP certifiers read shifted Long arrays. Translation preserves row duals and source primals.
        val authority = requireNotNull(core.state).model
        val proof = authority.recentered(lower.map(ExactLpNumber::of)).toLegacy() ?: return null
        val sources = base.sourceMap?.withCpBounds(proof, session)
        val bindingStarted = if (base.tidyProof != null) Monotonic.markNow() else null
        val rebound = try {
            base.withModel(proof, sources)
        } catch (_: IllegalArgumentException) {
            engine.observeEpoch("map_declines")
            reset()
            return null
        } finally {
            bindingStarted?.let { engine.observeEpoch("map_binding_ns", it.elapsedNow().inWholeNanoseconds) }
        }
        if (base.tidyProof != null) engine.observeEpoch("map_retained")
        currentModel = proof
        persistentState = true
        return rebound
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

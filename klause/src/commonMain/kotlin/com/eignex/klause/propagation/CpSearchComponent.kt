package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchConflictResolver
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelBlocker
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.IntArrayList

/**
 * The finite-domain participant in a shared search session.
 *
 * [PropagationSession] remains entirely CP-owned. This adapter only aligns its decision levels with
 * the shared trail; it does not expose domains to theory components. Its existing conflict analyzer
 * continues to supply CP's specialised learned constraints while the generic engine migration is in
 * progress.
 */
class CpSearchComponent(
    /** Native finite-domain propagation state. */
    val session: PropagationSession,
    /** Source id for each CP-local integer column; `null` keeps the ordinary identity mapping. */
    sourceIntIds: IntArray? = null,
    /** Typed CP split policy; the shared runner owns applying and retracting its returned decisions. */
    private val branching: CpBranching = CpBranching.Middle,
) : SearchBrancher,
    SearchModelBlocker,
    SearchConflictResolver {
    private val sourceIntIds = sourceIntIds?.copyOf()
    private val cpIntBySource = this.sourceIntIds?.let { ids ->
        IntArray((ids.maxOrNull() ?: -1) + 1) { -1 }.also { map ->
            for (local in ids.indices) map[ids[local]] = local
        }
    }
    private var sharedRootLevel = 0
    private val nativeLevelBySharedLevel = IntArrayList().apply { add(0) }
    private var lastResult: PropagationResult? = null

    override val resolvesAfterModelBlock: Boolean
        get() = session.problem.numIntVars == 0 && session.problem.factors.isEmpty()

    override val prefersNativeConflictAnalysis: Boolean get() = session.problem.numIntVars == 0

    /** Align shared decision level zero with CP's post-seed root. */
    fun rebase() {
        sharedRootLevel = session.decisionLevel
        nativeLevelBySharedLevel.clear()
        nativeLevelBySharedLevel.add(sharedRootLevel)
    }

    override fun initialize(context: com.eignex.klause.solver.search.SearchContext): ComponentResult {
        if (session.isUnsatAtRoot) return ComponentResult.Conflict()
        var result: ComponentResult = ComponentResult.Consistent
        for (variable in 0 until session.problem.numBoolVars) {
            val value = session.boolValue(variable) ?: continue
            val publication = context.publish(Lit.make(variable, value))
            if (publication !is ComponentResult.Consistent) result = publication
        }
        for (variable in 0 until session.problem.numIntVars) {
            val domain = session.intDomain(variable)
            val lower = context.publish(SearchDecision.IntAtLeast(sourceIntId(variable), domain.min))
            if (lower !is ComponentResult.Consistent) result = lower
            val upper = context.publish(SearchDecision.IntAtMost(sourceIntId(variable), domain.max))
            if (upper !is ComponentResult.Consistent) result = upper
        }
        return result
    }

    override fun assert(
        decision: SearchDecision,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult {
        val result = when (decision) {
            is SearchDecision.Bool -> when (
                val result = session.pinBool(
                    Lit.variable(decision.literal),
                    Lit.isPositive(decision.literal),
                )
            ) {
                is PropagationResult.Implied -> {
                    lastResult = result
                    publish(result, context, Lit.variable(decision.literal))
                }

                is PropagationResult.Unsat -> {
                    lastResult = result
                    conflict(result)
                }
            }

            is SearchDecision.IntAtMost -> cpIntId(decision.variable)?.let {
                result(session.pinIntAtMost(it, decision.upper), context)
            } ?: ComponentResult.Consistent

            is SearchDecision.IntAtLeast -> cpIntId(decision.variable)?.let {
                result(session.pinIntAtLeast(it, decision.lower), context)
            } ?: ComponentResult.Consistent

            is SearchDecision.IntEqual -> cpIntId(decision.variable)?.let {
                result(session.pinInt(it, decision.value), context)
            } ?: ComponentResult.Consistent

            is SearchDecision.Theory -> ComponentResult.Consistent
        }
        recordNativeLevel(context.decisionLevel)
        return result
    }

    private fun result(
        result: PropagationResult,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult = when (result) {
        is PropagationResult.Implied -> {
            lastResult = result
            publish(result, context)
        }

        is PropagationResult.Unsat -> {
            lastResult = result
            conflict(result)
        }
    }

    private fun conflict(result: PropagationResult.Unsat): ComponentResult.Conflict = ComponentResult.Conflict(
        (result.learnedClause as? ConflictAnalyzer.AnalysisResult.Learned)
            ?.literals
            ?.takeIf { literals -> literals.all { Lit.variable(it) < session.problem.numBoolVars } }
            ?.let(::SearchExplanation),
    )

    /** Publish Boolean facts from a CP fixpoint without exposing finite-domain deductions. */
    fun publish(
        result: PropagationResult.Implied,
        context: com.eignex.klause.solver.search.SearchContext,
        skippedVariable: Int = -1,
    ): ComponentResult = publish(result, skippedVariable) { context.publish(it) }

    /** Import facts already applied by the native CP session at its current shared level. */
    fun import(result: PropagationResult.Implied, shared: SearchSession): ComponentResult =
        publish(result, skippedVariable = -1) { shared.publishFrom(this, it) }

    private fun publish(
        result: PropagationResult.Implied,
        skippedVariable: Int,
        publish: (SearchDecision) -> ComponentResult,
    ): ComponentResult {
        var published: ComponentResult = ComponentResult.Consistent
        result.forEachBool { variable, value ->
            if (variable != skippedVariable) {
                val publication = publish(SearchDecision.Bool(Lit.make(variable, value)))
                if (publication !is ComponentResult.Consistent) published = publication
            }
        }
        result.forEachInt { variable, value ->
            val publication = publish(SearchDecision.IntEqual(sourceIntId(variable), value))
            if (publication !is ComponentResult.Consistent) published = publication
        }
        result.forEachIntMin { variable, value ->
            val publication = publish(SearchDecision.IntAtLeast(sourceIntId(variable), value))
            if (publication !is ComponentResult.Consistent) published = publication
        }
        result.forEachIntMax { variable, value ->
            val publication = publish(SearchDecision.IntAtMost(sourceIntId(variable), value))
            if (publication !is ComponentResult.Consistent) published = publication
        }
        return published
    }

    override val retainsOwnExplanations: Boolean get() = true

    override fun reasonFor(literal: Int): SearchExplanation? =
        session.boolReasonClause(Lit.variable(literal))?.let(::SearchExplanation)

    override fun retract(decisionLevel: Int) {
        val target = if (decisionLevel < nativeLevelBySharedLevel.size) {
            nativeLevelBySharedLevel[decisionLevel]
        } else {
            sharedRootLevel
        }
        session.popToLevel(target)
        nativeLevelBySharedLevel.truncateTo(decisionLevel + 1)
    }

    override fun contributeModel(model: SearchModel, context: com.eignex.klause.solver.search.SearchContext) {
        for (variable in 0 until session.problem.numIntVars) {
            model.put(SearchIntValue(sourceIntId(variable)), session.intDomain(variable).min)
        }
        model.put(
            this,
            Sample(
                BooleanArray(session.problem.numBoolVars) { variable -> session.boolValue(variable) ?: false },
                LongArray(session.problem.numIntVars) { variable -> session.intDomain(variable).min },
            ),
        )
    }

    override fun nextBranch(context: com.eignex.klause.solver.search.SearchContext): List<SearchDecision>? =
        branching.alternatives(session, ::sourceIntId)

    override fun blockModel(
        model: com.eignex.klause.solver.search.AssembledSearchModel,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult {
        val sample = checkNotNull(model.valueOf<Sample>(this))
        val nogood = session.assignmentNogood(
            sample.bools,
            sample.ints,
        )
        if (nogood.isEmpty()) return ComponentResult.Conflict()
        return when (val result = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)) {
            is PropagationResult.Implied -> publish(result, context)
            is PropagationResult.Unsat -> ComponentResult.Conflict()
        }
    }

    override fun resolveConflict(context: com.eignex.klause.solver.search.SearchContext): SearchConflictResolution {
        if (session.problem.numIntVars != 0) return SearchConflictResolution.Chronological
        val learned = (lastResult as? PropagationResult.Unsat)?.learnedClause
            as? ConflictAnalyzer.AnalysisResult.LearnedConstraint
            ?: return SearchConflictResolution.Chronological
        if (!learned.asserting) return SearchConflictResolution.Chronological
        if (learned.guardLiterals.isEmpty() && learned.backjumpLevel == 0) {
            return SearchConflictResolution.Exhausted
        }
        return SearchConflictResolution.Backjump(CpLearnedConflict(learned))
    }

    /** Shared level corresponding to the latest native level not above [nativeLevel]. */
    fun sharedLevelForNative(nativeLevel: Int): Int {
        for (level in nativeLevelBySharedLevel.size - 1 downTo 0) {
            if (nativeLevelBySharedLevel[level] <= nativeLevel) return level
        }
        return 0
    }

    private inner class CpLearnedConflict(private val learned: ConflictAnalyzer.AnalysisResult.LearnedConstraint) :
        SearchLearnedConflict {
        override val decisionLevel: Int get() = sharedLevelForNative(learned.backjumpLevel)
        override val lbd: Int get() = learned.lbd
        override val guardLiterals: IntArray get() = learned.guardLiterals
        override val decisionLevels: IntArray get() = learned.decisionLevels

        override fun apply(session: SearchSession): SearchLearnedConflictResult {
            val result = when (learned) {
                is ConflictAnalyzer.AnalysisResult.Learned -> this@CpSearchComponent.session.addLearnedClause(
                    Clause(learned.literals),
                    learned.lbd,
                )

                is ConflictAnalyzer.AnalysisResult.LearnedPb -> this@CpSearchComponent.session.addLearnedPb(
                    learned.weights,
                    learned.literals,
                    learned.degree,
                    learned.lbd,
                )
            }
            return when (result) {
                is PropagationResult.Implied -> {
                    // The clause stays in this component's own database. Copying it into the shared one
                    // would give the same clause two watch indexes and two reduction policies, and the
                    // shared analyzer reaches this component's reasoning through [reasonFor] instead.
                    if (import(result, session) !is ComponentResult.Consistent) {
                        SearchLearnedConflictResult.Chronological
                    } else {
                        when (session.propagate()) {
                            ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                            is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                            ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                        }
                    }
                }

                is PropagationResult.Unsat -> {
                    val next = result.learnedClause as? ConflictAnalyzer.AnalysisResult.LearnedConstraint
                    when {
                        next == null -> SearchLearnedConflictResult.Chronological
                        next.backjumpLevel == 0 && next.guardLiterals.isEmpty() -> SearchLearnedConflictResult.Exhausted
                        else -> SearchLearnedConflictResult.Backjump(CpLearnedConflict(next))
                    }
                }
            }
        }
    }

    private fun cpIntId(sourceIntId: Int): Int? {
        val map = cpIntBySource ?: return sourceIntId
        return map.getOrNull(sourceIntId)?.takeIf { it >= 0 }
    }

    private fun sourceIntId(cpIntId: Int): Int = sourceIntIds?.get(cpIntId) ?: cpIntId

    private fun recordNativeLevel(sharedLevel: Int) {
        while (nativeLevelBySharedLevel.size <= sharedLevel) nativeLevelBySharedLevel.add(session.decisionLevel)
        nativeLevelBySharedLevel[sharedLevel] = session.decisionLevel
    }
}

/** Supplies finite-domain splits without owning a search trail. */
fun interface CpBranching {
    /** Exhaustive alternatives for the current CP state, or null once all CP columns are fixed. */
    fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>?

    /** First unfixed column, split at its inclusive midpoint. */
    data object Middle : CpBranching {
        override fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>? {
            for (variable in 0 until session.problem.numIntVars) {
                val domain = session.intDomain(variable)
                if (domain.min == domain.max) continue
                // Unsigned halving preserves the inclusive midpoint even for the full signed Long range.
                val middle = domain.min + ((domain.max - domain.min) ushr 1)
                return listOf(
                    SearchDecision.IntAtMost(sourceIntId(variable), middle),
                    SearchDecision.IntAtLeast(sourceIntId(variable), middle + 1),
                )
            }
            return null
        }
    }

    /** Leave residual selection to another shared [SearchBrancher]. */
    data object None : CpBranching {
        override fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>? = null
    }
}

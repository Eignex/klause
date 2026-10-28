package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.problemComplexity
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeFactor
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeIncremental
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeMemo
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.isClausal
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

/**
 * Cost tier of a presolve pass. A [PresolveEmphasis] enables a set of tiers, so the level dial is "how
 * expensive a pass may be".
 */
enum class PresolveTiming {
    /** Cheap, run every round (bound/coefficient reductions, substitution). */
    FAST,

    /** Moderate cost (symmetry detection); run from the default level up. */
    MEDIUM,

    /** Expensive (SAC probing); run only at the aggressive level. */
    EXHAUSTIVE,
}

/** Round cap for the iterating emphasis levels; the fixpoint is almost always reached well before. */
internal const val MAX_PRESOLVE_ROUNDS = 16

/** Integer-variables-to-factors ratio above which a model is treated as *underdetermined*, so affine
 *  elimination caps its wide folds. Well below every model where affine folds
 *  productively (all observed ≤ ~2.4) and well below the pathological dense case (~23). */
private const val UNDERDETERMINED_RATIO = 8

/** Bake-time SAC probe budgets (the only EXHAUSTIVE-tier work). The unit is a `propagate` call.
 *  The capped tier bounds an EXHAUSTIVE pass turned on by an explicit override under a non-aggressive
 *  level so it can't dominate presolve time; the aggressive tier is larger but still bounded so a big
 *  instance with wide domains terminates. */
internal const val CAPPED_PROBE_BUDGET_PER_VAR = 256
internal const val CAPPED_PROBE_TOTAL_BUDGET = 20_000
internal const val AGGRESSIVE_PROBE_BUDGET_PER_VAR = 4_096
internal const val AGGRESSIVE_PROBE_TOTAL_BUDGET = 250_000

/**
 * Entry analysis beyond a pass's explicit charges, including fruitless full-model analysis and delta
 * construction. One unit per factor and variable occurrence, weighted by [baseWorkWeight]. Hosts cache
 * this aggregate per factor revision and apply sound eligibility checks before charging analysis.
 */
internal fun passBaseUnits(factors: Array<Factor>, booleanPins: IntHashSet? = null): Long {
    var units = factors.size.toLong()
    for (f in factors) {
        units += f.structuralKeyWeight
        if (booleanPins != null && f is Clause && f.literals.size == 1) {
            booleanPins.add(Lit.variable(f.literals[0]))
        }
    }
    return units
}

/**
 * Base work charge per [passBaseUnits] unit, relative to one simplex operation.
 * Added to the pass's own charges; unlisted passes use [DEFAULT_PASS_BASE_WORK_WEIGHT].
 */
internal val PresolvePass.baseWorkWeight: Long
    get() = when (this) {
        // Relation admission and scans are metered before reading payloads, including declined diagrams.
        PresolvePass.COMPOSE_EXTENSIONAL, PresolvePass.SUBSTITUTE_TUPLE_PACKING,
        PresolvePass.SUBSTITUTE_TABLE_VALUES -> 0L
        PresolvePass.REMOVE_REDUNDANT -> 63L
        PresolvePass.FOLD_COMPARISON_CLAUSES -> 18L
        PresolvePass.FUSE_LINEAR_BOUNDS -> 16L
        PresolvePass.BREAK_SYMMETRIES -> 16L
        PresolvePass.AGGREGATE_SUB_SUMS -> 11L
        PresolvePass.REDUCE_DIOPHANTINE, PresolvePass.DUAL_FIX -> 10L
        PresolvePass.REDUCE_STRUCTURAL -> 7L
        PresolvePass.ELIMINATE_AFFINE_SINGLETONS, PresolvePass.MERGE_DUPLICATE_COLUMNS -> 5L
        PresolvePass.PROJECT_SINGLETON_INEQUALITIES -> 3L
        PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.MERGE_AMO_CLIQUES, PresolvePass.DERIVE_XOR_UNITS -> 1L
        else -> DEFAULT_PASS_BASE_WORK_WEIGHT
    }

/** Default base work weight for passes without a specific weight. */
private const val DEFAULT_PASS_BASE_WORK_WEIGHT = 10L

/** [liveFactors] plus every column of [problem], the scale a pass's change is weighed against. */
private fun modelSizeOf(liveFactors: Int, problem: Problem): Long =
    liveFactors.toLong() + problem.numIntVars + problem.numBoolVars

/** Factors [delta] drops or adds plus the columns it narrows from [domains]. */
private fun changedUnitsOf(delta: PassDelta, domains: Array<IntDomain>, invocation: PresolveInvocation?): Long {
    var units = (delta.droppedIndices.size + delta.addedFactors.size).toLong()
    delta.domains?.let { next ->
        for (v in next.indices) {
            if (next[v] !== domains[v]) units++
            if (invocation == null) continue
            val previous = domains[v]
            if (next[v].min > previous.min || next[v].max < previous.max || next[v].valueCount < previous.valueCount) {
                invocation.narrowedColumns++
            }
            if (next[v].isFixed && !previous.isFixed) invocation.fixedColumns++
        }
    }
    return units
}

/** Columns [proved] bounds more tightly than [current] does. */
private fun narrowedColumns(current: IntBounds, proved: IntBounds, invocation: PresolveInvocation?): Int {
    var n = 0
    for (v in 0 until proved.size) {
        val lower = proved.hasLower(v) && (!current.hasLower(v) || proved.lower(v) > current.lower(v))
        val upper = proved.hasUpper(v) && (!current.hasUpper(v) || proved.upper(v) < current.upper(v))
        if (lower || upper) {
            n++
            if (proved.hasLower(v) && proved.hasUpper(v) && proved.lower(v) == proved.upper(v)) {
                invocation?.let { it.fixedColumns++ }
            }
        }
    }
    return n
}

/** Runs enabled problem-transform passes to a bounded fixpoint. */
object Presolver {

    /**
     * Run the factor-only part of presolve over a canonical source model.
     *
     * Source passes read declared bounds and factors; the [SourceDelta] they return cannot name a finite
     * domain or lift a sample, so nothing here can cross the deferred baking boundary. The shared round
     * engine keeps their scheduling and cancellation semantics aligned with the finite lane.
     */
    internal fun runSource(
        problem: Problem,
        config: PresolveConfig,
        context: PresolveContext = PresolveContext.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
    ): SourcePresolved {
        val passes = problemPasses(problem, config, context, PresolvePass.Capability.SOURCE)
        if (passes.isEmpty() || config.maxRounds == 0) return SourcePresolved(problem)
        var ctx = context.withCancellation(cancellation)
            .withPresolveBudget(context.presolveBudget)
            .withSubsumeIncremental(SubsumeMemo())
        val host = object : PresolveRoundEngine.RoundHost {
            var current = problem

            // In the order the passes fired, which is the order [SourceRebuilds.compose] reverses.
            val rebuilds = ArrayList<SourceRebuilds>()
            var changed = 0L
            val entrySize = PresolveEntrySize(ctx.presolveBudget)
            val booleanPins = PresolveBooleanPins()
            var parityInputs: ParityInputRevision? = null
            val linearInputs = LinkedHashMap<PresolvePass, LinearInputRevision>()

            override fun inputRevision(pass: PresolvePass): Long? {
                if (pass.tracksLinearInputs) {
                    val inputs = linearInputs.getOrPut(pass) {
                        LinearInputRevision(current, pass, ctx.presolveBudget?.orSpent(cancellation) ?: cancellation)
                    }
                    return inputs.current(current)
                }
                if (pass != PresolvePass.DERIVE_XOR_UNITS) return null
                val inputs = parityInputs ?: ParityInputRevision(
                    current, ctx.presolveBudget?.orSpent(cancellation) ?: cancellation,
                ).also { parityInputs = it }
                return inputs.current(current)
            }

            override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome {
                val token = (slice ?: ctx.cancellation).let { ctx.presolveBudget?.observeStops(it) ?: it }
                return ctx.presolveBudget.observeRoundPass(
                    pass, PresolveLane.SOURCE, token,
                    { current.presolveSize() },
                    { pass.namespaceEligible(current) },
                ) { invocation ->
                    val units = entrySize.units(current, token)
                    booleanPins.seed(entrySize.booleanPins)
                    invocation.recordEntries(units)
                    token.charge(pass.baseWorkWeight * units)
                    if (token()) return@observeRoundPass PassOutcome.UNCHANGED
                    val delta = pass.applySource(current, ctx.withCancellation(token))
                    if (delta.infeasible) return@observeRoundPass PassOutcome.INFEASIBLE
                    if (delta.isEmpty) {
                        trackAfterCompletedMiss(pass, invocation) { inputRevision(pass) }
                        return@observeRoundPass PassOutcome.UNCHANGED
                    }
                    invocation.recordDelta(current.factors, delta.droppedIndices, delta.addedFactors)
                    val narrowed = delta.bounds?.let { narrowedColumns(current.intBounds, it, invocation) } ?: 0
                    invocation?.narrowedColumns = narrowed
                    // A proved range can empty a column whose declaration it intersects, which refutes the
                    // model even though the pass itself only claimed a bound.
                    parityInputs?.recordDelta(
                        current.factors,
                        delta.droppedIndices,
                        delta.addedFactors,
                        deductionsChanged = delta.bounds != null,
                    )
                    for (inputs in linearInputs.values) inputs.recordDelta(
                        current.factors, delta.droppedIndices, delta.addedFactors,
                        deductionsChanged = delta.bounds != null,
                    )
                    current = current.withSourcePassDelta(delta) ?: return@observeRoundPass PassOutcome.INFEASIBLE
                    ctx = ctx.withObjective(delta.objective)
                    booleanPins.record(invocation, delta.addedFactors, entrySize::isBooleanPinned)
                    if (!delta.rebuild.isEmpty) rebuilds.add(delta.rebuild)
                    changed += delta.droppedIndices.size + delta.addedFactors.size + narrowed
                    PassOutcome.CHANGED
                }
            }

            override fun complexity(): Long = current.factors.size.toLong()

            override fun modelSize(): Long = modelSizeOf(current.factors.size, current)

            override fun changedUnits(): Long = changed
        }
        val rounds = PresolveRoundEngine.run(
            passes,
            config.maxRounds,
            cancellation,
            ctx.presolveBudget,
            host,
            config.abortFraction,
        )
        return SourcePresolved(
            host.current,
            rounds.fired,
            rounds.infeasible,
            SourceRebuilds.compose(host.rebuilds),
            ctx.linearObjective,
        )
    }

    private fun trackAfterCompletedMiss(
        pass: PresolvePass,
        invocation: PresolveInvocation?,
        revision: () -> Long?,
    ) {
        if (!pass.tracksLinearInputs || invocation?.interrupted == true) return
        revision()
        // Dependency tracking follows the completed kernel; its stop cannot revoke that completion.
        if (invocation != null) invocation.interrupted = false
    }

    private fun problemPasses(
        problem: Problem,
        config: PresolveConfig,
        context: PresolveContext,
        capability: PresolvePass.Capability,
    ): List<PresolvePass> {
        val passes = config.problemPasses(context, capability)
        // Cardinality conversion displaces native clause propagation and learning. Keep clausal
        // optimization in that lane unless the caller selects the stronger preparation policy.
        val preserveClauses = context.objectiveBoolVars.isNotEmpty() && problem.isClausal() &&
            config.emphasis != PresolveEmphasis.AGGRESSIVE &&
            PresolvePass.MERGE_AMO_CLIQUES !in config.overrides
        return if (preserveClauses) passes - PresolvePass.MERGE_AMO_CLIQUES else passes
    }

    /** Apply [config]'s passes to [problem] under [context], returning the transformed problem and a
     *  reconstruct mapping its solutions back to the original. [cancellation] is polled between passes
     *  and rounds: a fired deadline returns the partial result so far — every pass is individually
     *  sound, so stopping early only forgoes further reduction, never correctness. */
    fun run(
        problem: BakedProblem,
        config: PresolveConfig,
        context: PresolveContext = PresolveContext.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
    ): Presolved {
        val passes = problemPasses(problem, config, context, PresolvePass.Capability.FINITE)
        val maxRounds = config.maxRounds
        if (passes.isEmpty() || maxRounds == 0) {
            return Presolved(problem, { it }, infeasible = problem.baked is PropagationResult.Unsat).also {
                it.mapping = SourceMapping(problem, problem, TransformationGuarantees.IDENTITY)
            }
        }
        // A gcd-indivisible equality (`Σ cᵢ·xᵢ = b`, `gcd(cᵢ) ∤ b`) is infeasible independent of the
        // variable bounds, so refuting it here in O(factors) reports the verdict without the session ever
        // seeding its state over the model. Only the infeasible case short-circuits; a feasible
        // strengthening is recomputed (idempotently) by the round engine below.
        val earlyRefutation = if (PresolvePass.STRENGTHEN_COEFFICIENTS in passes) {
            val token = context.presolveBudget?.observeStops(cancellation) ?: cancellation
            context.presolveBudget.observeRoundPass(
                PresolvePass.STRENGTHEN_COEFFICIENTS, PresolveLane.FINITE, token,
                { problem.presolveSize() }, { true },
            ) { invocation ->
                val units = PresolveEntrySize(context.presolveBudget).units(problem, token)
                invocation.recordEntries(units)
                token.charge(PresolvePass.STRENGTHEN_COEFFICIENTS.baseWorkWeight * units)
                if (Presolve.strengthenCoefficients(problem, token).infeasible) {
                    PassOutcome.INFEASIBLE
                } else PassOutcome.UNCHANGED
            } == PassOutcome.INFEASIBLE
        } else false
        if (earlyRefutation) {
            return Presolved(problem, { it }, infeasible = true).also {
                it.mapping = SourceMapping(problem, problem, TransformationGuarantees.IDENTITY)
            }
        }
        // Hand the round-engine cancellation to the passes so the long-running ones (affine fixpoint,
        // symmetry search) poll it internally — a presolve budget then bounds them, not just the gaps
        // between passes. Derive the underdetermined flag once from the original problem — later rounds
        // shrink the factor set, so a per-round ratio would spuriously read as underdetermined.
        val underdetermined = problem.factors.isNotEmpty() &&
            problem.numIntVars.toLong() > problem.factors.size.toLong() * UNDERDETERMINED_RATIO
        var ctx = context.withCancellation(cancellation)
            .withAffineUnderdetermined(underdetermined)
            .withAffinePivotOrder(config.affinePivotOrder)
            .withPresolveBudget(context.presolveBudget)

        // Incremental path for the default FAST+MEDIUM rounds: one persistent [PresolveSession] absorbs
        // each pass's delta instead of rebuilding a `Problem` per firing pass. Scoped away from any
        // EXHAUSTIVE pass (probing, implication graph, BVE / BCE), which keeps the fresh-rebuild path.
        if (passes.none { it.timing == PresolveTiming.EXHAUSTIVE }) {
            return runIncremental(problem, passes, maxRounds, ctx, cancellation, config.abortFraction)
        }

        val host = object : PresolveRoundEngine.RoundHost {
            var current = problem
            val reconstructs = ArrayList<(Sample) -> Sample>()
            val rebuilds = ArrayList<SourceRebuilds>()
            var liftDescribedByRebuild = true
            var changed = 0L
            val entrySize = PresolveEntrySize(ctx.presolveBudget)
            val booleanPins = PresolveBooleanPins()
            var parityInputs: ParityInputRevision? = null
            val linearInputs = LinkedHashMap<PresolvePass, LinearInputRevision>()

            override fun inputRevision(pass: PresolvePass): Long? {
                if (pass.tracksLinearInputs) {
                    val inputs = linearInputs.getOrPut(pass) {
                        LinearInputRevision(current, pass, ctx.presolveBudget?.orSpent(cancellation) ?: cancellation)
                    }
                    return inputs.current(current)
                }
                if (pass != PresolvePass.DERIVE_XOR_UNITS) return null
                val inputs = parityInputs ?: ParityInputRevision(
                    current, ctx.presolveBudget?.orSpent(cancellation) ?: cancellation,
                ).also { parityInputs = it }
                return inputs.current(current)
            }

            override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome {
                val token = (slice ?: ctx.cancellation).let { ctx.presolveBudget?.observeStops(it) ?: it }
                return ctx.presolveBudget.observeRoundPass(
                    pass, PresolveLane.FINITE, token,
                    { current.presolveSize() },
                    { pass.namespaceEligible(current) },
                ) { invocation ->
                    val units = entrySize.units(current, token)
                    invocation.recordEntries(units)
                    token.charge(pass.baseWorkWeight * units)
                    if (token()) return@observeRoundPass PassOutcome.UNCHANGED
                    val delta = pass.applyFinite(current, ctx.withCancellation(token))
                    if (delta.infeasible) return@observeRoundPass PassOutcome.INFEASIBLE
                    if (delta.isEmpty) {
                        trackAfterCompletedMiss(pass, invocation) { inputRevision(pass) }
                        return@observeRoundPass PassOutcome.UNCHANGED
                    }
                    invocation.recordDelta(current.factors, delta.droppedIndices, delta.addedFactors)
                    delta.reconstruct?.let(reconstructs::add)
                    if (delta.reconstruct != null && delta.rebuild.isEmpty) liftDescribedByRebuild = false
                    if (!delta.rebuild.isEmpty) rebuilds.add(delta.rebuild)
                    changed += changedUnitsOf(delta, current.rootIntDomainsInPlace, invocation)
                    val root = current.rootDeductions as? PropagationResult.Implied
                    booleanPins.record(invocation, delta.addedFactors) { root?.boolValueOrNull(it) != null }
                    parityInputs?.recordDelta(current.factors, delta.droppedIndices, delta.addedFactors)
                    for (inputs in linearInputs.values) {
                        inputs.recordDelta(current.factors, delta.droppedIndices, delta.addedFactors)
                    }
                    current = current.withPassDelta(delta, ctx.bakeConfig)
                    ctx = ctx.withObjective(delta.objective)
                    PassOutcome.CHANGED
                }
            }

            override fun complexity(): Long = problemComplexity(current.factors.size, current.rootIntDomainsInPlace)

            override fun modelSize(): Long = modelSizeOf(current.factors.size, current)

            override fun changedUnits(): Long = changed
        }
        val rounds = PresolveRoundEngine.run(
            passes, maxRounds, cancellation, ctx.presolveBudget, host, config.abortFraction,
        )
        val mapping = SourceMapping(
            problem, host.current, rounds.fired.transformationGuarantees(),
            SourceRebuilds.compose(host.rebuilds),
            liftDescribedByRebuild = host.liftDescribedByRebuild,
            lift = composeReconstructs(host.reconstructs),
        )
        return Presolved(
            host.current,
            { mapping.reconstructFrom(host.current, it) },
            rounds.fired,
            rounds.infeasible || host.current.baked is PropagationResult.Unsat,
        ).also {
            it.mapping = mapping
            it.objective = ctx.linearObjective
        }
    }

    /**
     * Incremental variant of [run]'s round engine (default FAST+MEDIUM emphasis): identical
     * scheduling — priority order, version-stamp fixpoint skip, [PresolvePass.skipAfterEmpty] parking,
     * and the diminishing-returns abort — but a persistent [PresolveSession] absorbs each firing pass's
     * delta instead of rebuilding a `Problem`. Each pass reads a cheap [PresolveSession.passInput] view
     * and its delta is folded back via [PresolveSession.applyDelta]; the heavyweight solver `Problem`
     * is materialized once at the end.
     */
    private fun runIncremental(
        problem: BakedProblem,
        passes: List<PresolvePass>,
        maxRounds: Int,
        initialContext: PresolveContext,
        cancellation: Cancellation,
        abortFraction: Double,
    ): Presolved {
        var ctx = initialContext
        val session = ctx.presolveBudget.observePreparation(
            PresolvePreparationStage.FINITE_SESSION, PresolveLane.FINITE, problem, cancellation,
        ) { token, observation ->
            PresolveSession(problem, ctx.bakeConfig, token).also {
                if (it.infeasible) observation.preparationRefuted()
            }
        }
        // Persistent subsume indices + the change-mark at subsume's last firing, so each re-run reprocesses
        // only the factors other passes changed in between instead of rescanning the whole live set.
        val subsumeMemo = SubsumeMemo()
        // Infeasibility does not stop the loop, mirroring the fresh path: a pass still fires and records
        // its factors on an already-infeasible problem (e.g. xor-units emitting contradictory units).
        // [PresolveSession.applyDelta] tracks those factor changes but skips re-propagating a conflicted
        // state, and the final materialized problem's bake surfaces the infeasibility.
        val host = object : PresolveRoundEngine.RoundHost {
            var subsumeMark: PresolveSession.ChangeMark? = null
            val booleanPins = PresolveBooleanPins()
            var parityInputs: ParityInputRevision? = null
            val linearInputs = LinkedHashMap<PresolvePass, LinearInputRevision>()

            override fun inputRevision(pass: PresolvePass): Long? {
                if (pass.tracksLinearInputs) {
                    val inputs = linearInputs.getOrPut(pass) {
                        LinearInputRevision(
                            session.passInput(), pass, ctx.presolveBudget?.orSpent(cancellation) ?: cancellation,
                        )
                    }
                    return inputs.current { v ->
                        val domain = session.intDomainOf(v)
                        domain.min to domain.max
                    }
                }
                if (pass != PresolvePass.DERIVE_XOR_UNITS) return null
                val inputs = parityInputs ?: ParityInputRevision(
                    session.passInput(), ctx.presolveBudget?.orSpent(cancellation) ?: cancellation,
                ).also { parityInputs = it }
                return inputs.current(session::boolValueOf) { v ->
                    val domain = session.intDomainOf(v)
                    domain.min to domain.max
                }
            }

            // Affine's change-mark at its last firing; a re-run rescans only the variables touched since.
            var affineMark: PresolveSession.ChangeMark? = null

            // Dup-columns' change-mark at its last firing; a re-run's fast-bail tests only the variables
            // touched since instead of re-signing every column.
            var dupMark: PresolveSession.ChangeMark? = null

            val reconstructs = ArrayList<(Sample) -> Sample>()
            val rebuilds = ArrayList<SourceRebuilds>()
            var liftDescribedByRebuild = true
            var changed = 0L

            override fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome {
                val token = (slice ?: ctx.cancellation).let { ctx.presolveBudget?.observeStops(it) ?: it }
                return ctx.presolveBudget.observeRoundPass(
                    pass, PresolveLane.FINITE, token,
                    { PresolveSizeStats(
                        session.liveFactorCount, problem.numBoolVars, problem.numIntVars, reals = problem.numRealVars,
                    ) },
                    { pass.namespaceEligible(problem, session.liveFactorCount) && incrementalEligible(pass, token) },
                ) { invocation ->
                    // Before the context: [passOccurrence] is keyed against the factor list [passInput] fixes.
                    val input = session.passInput()
                    val units = session.entryUnits(token)
                    invocation.recordEntries(units)
                    token.charge(pass.baseWorkWeight * units)
                    if (token()) return@observeRoundPass PassOutcome.UNCHANGED
                    val base = passContext(pass)
                    val delta = pass.applyFinite(input, base.withCancellation(token))
                    if (delta.infeasible) return@observeRoundPass PassOutcome.INFEASIBLE
                    if (delta.isEmpty) {
                        trackAfterCompletedMiss(pass, invocation) { inputRevision(pass) }
                        return@observeRoundPass PassOutcome.UNCHANGED
                    }
                    invocation.recordDelta(input.factors, delta.droppedIndices, delta.addedFactors)
                    delta.reconstruct?.let(reconstructs::add)
                    if (delta.reconstruct != null && delta.rebuild.isEmpty) liftDescribedByRebuild = false
                    if (!delta.rebuild.isEmpty) rebuilds.add(delta.rebuild)
                    changed += changedUnitsOf(delta, input.rootIntDomainsInPlace, invocation)
                    booleanPins.record(invocation, delta.addedFactors) { session.boolValueOf(it) != null }
                    parityInputs?.recordDelta(input.factors, delta.droppedIndices, delta.addedFactors)
                    for (inputs in linearInputs.values) {
                        inputs.recordDelta(input.factors, delta.droppedIndices, delta.addedFactors)
                    }
                    session.applyDelta(delta)
                    ctx = ctx.withObjective(delta.objective)
                    PassOutcome.CHANGED
                }
            }

            fun incrementalEligible(pass: PresolvePass, token: Cancellation): Boolean = when (pass) {
                PresolvePass.ELIMINATE_AFFINE_SINGLETONS -> session.anyTouchedFactor(affineMark, token) {
                    it.equivalentLinear()?.let { row ->
                        row.integerConstants != null && row.op == LinearOp.EQ && row.vars.size >= 2
                    } == true
                }
                PresolvePass.MERGE_DUPLICATE_COLUMNS -> session.anyTouchedFactor(dupMark, token) {
                    it.equivalentLinear()?.integerConstants != null
                }
                else -> true
            }

            // Hand the session's incrementally-maintained occurrence index only to the passes that read it
            // (affine / dup-columns), so a firing pass that changes the factor set does not force the
            // O(occurrences) dense-view rebuild for the non-consuming passes between it and the next
            // consumer. The view matches the pass input's factor order exactly, so decisions are unchanged.
            fun passContext(pass: PresolvePass): PresolveContext {
                var passCtx = ctx
                if (pass == PresolvePass.REMOVE_REDUNDANT) {
                    passCtx = passCtx.withSubsumeIncremental(subsumeIncremental(session, subsumeMark, subsumeMemo))
                }
                if (pass == PresolvePass.ELIMINATE_AFFINE_SINGLETONS) {
                    passCtx = passCtx.withSharedIntOcc(session.passOccurrence())
                        .withAffineTouchedVars(touchedSince(affineMark))
                }
                if (pass == PresolvePass.MERGE_DUPLICATE_COLUMNS) {
                    passCtx = passCtx.withSharedIntOcc(session.passOccurrence())
                        .withDupColumnsTouchedVars(touchedSince(dupMark))
                }
                return passCtx
            }

            // Advance each incremental pass's mark past its own delta so the next firing sees only what
            // other passes changed since (a pass's own output is folded into its persistent index/scan).
            override fun afterPass(pass: PresolvePass) {
                if (pass == PresolvePass.REMOVE_REDUNDANT) subsumeMark = session.changeMark()
                if (pass == PresolvePass.ELIMINATE_AFFINE_SINGLETONS) affineMark = session.changeMark()
                if (pass == PresolvePass.MERGE_DUPLICATE_COLUMNS) dupMark = session.changeMark()
            }

            // The variables changed since [mark], or `null` when there is no replayable mark (first firing
            // or a reseed invalidated it) so the pass falls back to a full scan.
            fun touchedSince(mark: PresolveSession.ChangeMark?): IntArray? =
                if (mark == null || session.markStale(mark)) null else session.touchedIntVarsSince(mark)

            override fun complexity(): Long = session.complexity()

            override fun modelSize(): Long = modelSizeOf(session.liveFactorCount, problem)

            override fun changedUnits(): Long = changed
        }
        val rounds = PresolveRoundEngine.run(passes, maxRounds, cancellation, ctx.presolveBudget, host, abortFraction)

        // No pass fired: return the input problem itself, matching the fresh path's identity on a no-op.
        if (rounds.fired.isEmpty()) {
            return Presolved(problem, { it }, emptyList(), rounds.infeasible || session.infeasible).also {
                it.mapping = SourceMapping(problem, problem, TransformationGuarantees.IDENTITY)
            }
        }

        val transformed = ctx.presolveBudget.observePreparation(
            PresolvePreparationStage.FINITE_MATERIALIZATION, PresolveLane.FINITE, problem, cancellation,
            output = { it },
        ) { _, observation ->
            session.materialize().also { if (session.infeasible) observation.preparationRefuted() }
        }
        val mapping = SourceMapping(
            problem, transformed, rounds.fired.transformationGuarantees(),
            SourceRebuilds.compose(host.rebuilds),
            liftDescribedByRebuild = host.liftDescribedByRebuild,
            lift = composeReconstructs(host.reconstructs),
        )
        return Presolved(
            transformed,
            { mapping.reconstructFrom(transformed, it) },
            rounds.fired,
            rounds.infeasible || session.infeasible || transformed.baked is PropagationResult.Unsat,
        ).also {
            it.mapping = mapping
            it.objective = ctx.linearObjective
        }
    }

    /** Compose per-pass reconstructs (application order) into the single solution-mapping function,
     *  applied in reverse so a final-problem solution maps all the way back to the original. */
    private fun composeReconstructs(reconstructs: List<(Sample) -> Sample>): (Sample) -> Sample =
        PresolveRoundEngine.compose(reconstructs)

    /** Build subsume's incremental input from the session: a full rebuild when it has no prior mark or the
     *  mark predates a reseed, else the factors added / dropped since. A factor added then dropped between
     *  firings has a null slot now, so `mapNotNull` omits it — it never entered the memo and needs no
     *  retraction. */
    private fun subsumeIncremental(
        session: PresolveSession,
        mark: PresolveSession.ChangeMark?,
        memo: SubsumeMemo,
    ): SubsumeIncremental = if (mark == null || session.markStale(mark)) {
        SubsumeIncremental(rebuild = true, session.passFactorIds(), emptyList(), IntArray(0), memo)
    } else {
        val ids = session.addedIdsSince(mark)
        val added = ArrayList<SubsumeFactor>(ids.size)
        for (id in ids) session.factorAt(id)?.let { added.add(SubsumeFactor(id, it)) }
        SubsumeIncremental(rebuild = false, session.passFactorIds(), added, session.droppedIdsSince(mark), memo)
    }
}

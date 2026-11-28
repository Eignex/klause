package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.withSourceSemantics

/**
 * Result of [satisfyUnderAssumptions]: a satisfying model, a contradiction among the assumptions and the
 * hard constraints, an indeterminate stop, or a problem unsat independent of the assumptions.
 */
sealed interface SatisfyResult {
    /** Effective source interpretation, with assumption restrictions retained separately. */
    val semantics: EffectiveSourceSemantics?

    /** Satisfiable, carrying a model. */
    data class Sat(
        /** The satisfying assignment. */
        val sample: Sample,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
    ) : SatisfyResult

    /** Subset of the explicit and parameter assumptions jointly infeasible with the hard constraints.
     *  Sound but not necessarily minimal; the full input [Assumptions] when the backend extracts no sub-core. */
    data class UnsatUnderAssumptions(
        /** The assumption subset that caused infeasibility. */
        val core: Assumptions,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult

    /** Problem is unsat even with the assumptions dropped. */
    data class GloballyUnsat(
        /** The unsat core, when one is available. */
        val core: UnsatCore? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult

    /** Indeterminate result (e.g. timeout). */
    data class Unknown(
        /** Why the result is indeterminate. */
        val reason: TerminationReason,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult
}

/**
 * Run `solver` with [assumptions] pinned for this call and return a [SatisfyResult].
 *
 * Parameter assumptions merge with [assumptions], with explicit pins taking precedence. On `Unsat`,
 * the merged assumption subset returned in [SatisfyResult.UnsatUnderAssumptions.core]
 * is sound (jointly infeasible with the hard constraints) but not guaranteed minimal.
 * The [BacktrackSolver] override drives the seed phase manually so seed-time conflicts
 * yield the applied prefix as a core; deeper conflicts use the engine's assumption core, falling
 * back to the full input assumption set (minimized when [minimizeCore] is set) when it has none.
 */
fun <P : SolverParams> Solver<P>.satisfyUnderAssumptions(
    assumptions: Assumptions,
    params: P,
    minimizeCore: Boolean = false,
): SatisfyResult {
    val effectiveAssumptions = params.assumptions.mergedWith(assumptions)
    if (this is BacktrackSolver && params is BacktrackParams) {
        return satisfyUnderAssumptionsBacktrack(
            this,
            effectiveAssumptions,
            params.copy(assumptions = Assumptions.None),
            minimizeCore,
        )
            .withSourceSemantics(problem.semantics)
    }
    val merged = params.withAssumptions(assumptions)
    @Suppress("UNCHECKED_CAST")
    return when (val r = solve(merged as P)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)

        is SolveResult.Unsat ->
            if (effectiveAssumptions.isEmpty && effectiveAssumptions.deductions.isEmpty) {
                SatisfyResult.GloballyUnsat(r.core)
            } else {
                val core = if (minimizeCore && params.assumptions.deductions.isEmpty) {
                    deletionMinimize(this, effectiveAssumptions, params)
                } else {
                    effectiveAssumptions
                }
                SatisfyResult.UnsatUnderAssumptions(core)
            }

        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }.withSourceSemantics(problem.semantics)
}

// Runs the seed phase by hand so a seed-time conflict yields the applied prefix of [assumptions],
// including the transitive premises of propagated deductions.
private fun satisfyUnderAssumptionsBacktrack(
    solver: BacktrackSolver,
    assumptions: Assumptions,
    params: BacktrackParams,
    minimizeCore: Boolean,
): SatisfyResult {
    if (assumptions.isEmpty && assumptions.deductions.isEmpty) {
        return when (val r = solver.solve(params)) {
            is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)
            is SolveResult.Unsat -> SatisfyResult.GloballyUnsat(r.core)
            is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
        }
    }
    val problem = solver.problem
    val baked = problem.baked
    if (baked is PropagationResult.Unsat) {
        val core = if (baked.conflictFactors.isEmpty()) {
            null
        } else {
            UnsatCore.of(baked.conflictFactors)
        }
        return SatisfyResult.GloballyUnsat(core)
    }
    val session = PropagationSession(problem)
    val seedResult = session.seed(assumptions)
    if (seedResult is PropagationResult.Unsat) {
        return SatisfyResult.UnsatUnderAssumptions(
            projectSeedConflictToAssumptions(assumptions, seedResult.conflictLevels),
        )
    }
    // Engine cores retain a refuted seed prefix or the full input after tree exhaustion.
    val merged = params.withAssumptions(assumptions)
    return when (val r = solver.solve(merged)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)

        is SolveResult.Unsat -> {
            val engineCore = r.assumptionCore
            val core = when {
                !assumptions.deductions.isEmpty -> assumptions
                engineCore != null && !engineCore.isEmpty -> engineCore
                minimizeCore -> deletionMinimize(solver, assumptions, params)
                else -> assumptions
            }
            SatisfyResult.UnsatUnderAssumptions(core)
        }

        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }
}

// Deletion-based MUS minimization at one solve per pin: a pin whose removal keeps the problem unsat is
// dropped. Frozen parameter pins stay. Callers skip it under inherited deductions, since deleting a pin
// can restore a shadowed restriction.
private fun <P : SolverParams> deletionMinimize(solver: Solver<P>, assumptions: Assumptions, params: P): Assumptions {
    var current = assumptions
    val candidateBools = assumptions.boolKeys.copyOf()
    val candidateInts = assumptions.intKeys.copyOf()

    for (id in candidateBools) {
        if (params.assumptions.isFrozenBool(id)) continue
        if (current.boolValueOrNull(id) == null) continue // already dropped
        val trial = current.dropBool(id)
        if (trial.isEmpty) continue
        val merged = params.withAssumptions(trial)

        @Suppress("UNCHECKED_CAST")
        val r = solver.solve(merged as P)
        if (r is SolveResult.Unsat) {
            current = trial
        }
    }
    for (id in candidateInts) {
        if (params.assumptions.isFrozenInt(id)) continue
        if (current.intValueOrNull(id) == null) continue
        val trial = current.dropInt(id)
        if (trial.isEmpty) continue
        val merged = params.withAssumptions(trial)

        @Suppress("UNCHECKED_CAST")
        val r = solver.solve(merged as P)
        if (r is SolveResult.Unsat) current = trial
    }
    return current
}

private fun Assumptions.dropBool(id: Int): Assumptions {
    val idx = boolKeys.toList().indexOf(id)
    if (idx < 0) return this
    val nk = IntArray(boolKeys.size - 1)
    val nv = BooleanArray(boolKeys.size - 1)
    boolKeys.copyInto(nk, 0, 0, idx)
    boolValues.copyInto(nv, 0, 0, idx)
    boolKeys.copyInto(nk, idx, idx + 1)
    boolValues.copyInto(nv, idx, idx + 1)
    return Assumptions(nk, nv, intKeys, intValues, deductions.withoutSetRestrictions())
}

private fun Assumptions.dropInt(id: Int): Assumptions {
    val idx = intKeys.toList().indexOf(id)
    if (idx < 0) return this
    val nk = IntArray(intKeys.size - 1)
    val nv = LongArray(intKeys.size - 1)
    intKeys.copyInto(nk, 0, 0, idx)
    intValues.copyInto(nv, 0, 0, idx)
    intKeys.copyInto(nk, idx, idx + 1)
    intValues.copyInto(nv, idx, idx + 1)
    return Assumptions(boolKeys, boolValues, nk, nv, deductions.withoutSetRestrictions())
}

// Seed order is bools first, one level each from 1 in [Assumptions.boolKeys] order, then ints from
// `numBools + 1`. Levels outside the seed range are dropped so the result stays a subset of the input.
internal fun projectSeedConflictToAssumptions(input: Assumptions, conflictLevels: IntArray): Assumptions {
    if (!input.deductions.isEmpty) return input
    val nb = input.boolKeys.size
    val ni = input.intKeys.size
    val boolHit = BooleanArray(nb)
    val intHit = BooleanArray(ni)
    for (lvl in conflictLevels) {
        val idx = lvl - 1
        if (idx in 0 until nb) {
            boolHit[idx] = true
        } else if (idx in nb until nb + ni) {
            intHit[idx - nb] = true
        }
    }
    var bc = 0
    for (h in boolHit) if (h) bc++
    var ic = 0
    for (h in intHit) if (h) ic++
    if (bc == 0 && ic == 0) {
        // No level maps to a seed assumption; the full input is still a sound core.
        return input
    }
    val bk = IntArray(bc)
    val bv = BooleanArray(bc)
    var w = 0
    for (i in 0 until nb) {
        if (boolHit[i]) {
            bk[w] = input.boolKeys[i]
            bv[w] = input.boolValues[i]
            w++
        }
    }
    val ik = IntArray(ic)
    val iv = LongArray(ic)
    w = 0
    for (i in 0 until ni) {
        if (intHit[i]) {
            ik[w] = input.intKeys[i]
            iv[w] = input.intValues[i]
            w++
        }
    }
    return Assumptions(bk, bv, ik, iv)
}

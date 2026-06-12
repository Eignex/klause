package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.format.Formats
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective

/** A catalog [ProblemRef] resolved into a concrete, solvable klause [Problem]. */
internal data class ResolvedProblem(
    internal val ref: ProblemRef,
    internal val problem: Problem,
    internal val objective: LinearObjective? = null,
    /** True when the model's objective is a maximization (so "better" = higher). MiniZinc sets it;
     *  other formats leave it false (klause minimises internally). */
    internal val maximize: Boolean = false,
    /** Local-search-only gradient view of [objective] for decomposed objectives, when the model
     *  provides one (see `LocalSearchParams.lsObjective`). Null ⇒ LS descends [objective]. */
    val lsObjective: IncrementalObjective? = null,
    /** Definitional sweep for the LS engine (see [com.eignex.klause.solver.localsearch.DefinitionalSweep]);
     *  carried from `FlatZincProgram.definitionalSweep` by the MiniZinc runner, null elsewhere. */
    val definitionalSweep: DefinitionalSweep? = null,
    /** Search heuristics from the model's `solve :: int_search(...)` annotation (see
     *  `FlatZincProgram.defaultBacktrackParams`); null when the model has none. Metrics
     *  merge their budget/seed/restart config into this so benchmark runs honour the
     *  model author's intended search the same way the competition CLI does. */
    val searchParams: BacktrackParams? = null,
    /** Xor-system search recipe (see `FlatZincProgram.xorSearchParams`); null unless the
     *  model carries two or more xor constraints. Raced as an extra portfolio worker. */
    val xorSearchParams: BacktrackParams? = null,
) {
    internal val name: String get() = ref.name
}

/** Wall-clock / effort budget threaded into solver params by metrics that honor it. */
data class Budget(internal val timeoutMillis: Long = 10_000L)

/**
 * Resolves a [ProblemRef] into a [ResolvedProblem]. The runner axis is *how a problem becomes
 * a klause `Problem`*: [InProcessRunner] parses an in-process format (or runs an `InCode`
 * builder); `MiniZincRunner` compiles `.mzn`→`.fzn` via the `minizinc` CLI first.
 * Solving the resulting `Problem` is then uniform across runners (see the solver axis).
 */
internal interface Runner {
    val id: String
    fun supports(ref: ProblemRef): Boolean
    fun resolve(ref: ProblemRef): ResolvedProblem
}

/** Resolves in-process formats (DIMACS / OPB / JSON-Schema / FlatZinc) and `InCode` builders. */
internal object InProcessRunner : Runner {
    override val id = "in-process"

    override fun supports(ref: ProblemRef): Boolean =
        ref.source is ProblemSource.InCode || Formats[ref.format].inProcess

    override fun resolve(ref: ProblemRef): ResolvedProblem {
        val src = ref.source
        if (src is ProblemSource.InCode) {
            require(ref.format == Format.IN_CODE) { "${ref.name}: InCode source must use Format.IN_CODE" }
            return ResolvedProblem(ref, src.build())
        }
        val format = Formats[ref.format]
        require(format.inProcess) { "${ref.name}: format ${ref.format} is not in-process; use its dedicated runner" }
        val ingested = format.ingest(CorpusFetcher.resolve(src))
        return ResolvedProblem(ref, ingested.problem, ingested.objective)
    }
}

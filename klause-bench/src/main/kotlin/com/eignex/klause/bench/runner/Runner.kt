package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.format.Formats
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem

/** A catalog [ProblemRef] resolved into a concrete, solvable klause [Problem]. */
data class ResolvedProblem(
    val ref: ProblemRef,
    val problem: Problem,
    val objective: Objective? = null,
    /** Local-search-only objective: a functional (gradient-bearing) mirror of [objective] for
     *  decomposed objectives, when the model provides one. Reference/complete backends use
     *  [objective]; the LS engine prefers this. Null ⇒ fall back to [objective]. */
    val lsObjective: Objective? = null,
) {
    val name: String get() = ref.name
}

/** Wall-clock / effort budget threaded into solver params by metrics that honor it. */
data class Budget(val timeoutMillis: Long = 10_000L)

/**
 * Resolves a [ProblemRef] into a [ResolvedProblem]. The runner axis is *how a problem becomes
 * a klause `Problem`*: [InProcessRunner] parses an in-process format (or runs an `InCode`
 * builder); `MiniZincRunner` (phase 2) compiles `.mzn`→`.fzn` via the `minizinc` CLI first.
 * Solving the resulting `Problem` is then uniform across runners (see the solver axis).
 */
interface Runner {
    val id: String
    fun supports(ref: ProblemRef): Boolean
    fun resolve(ref: ProblemRef): ResolvedProblem
}

/** Resolves in-process formats (DIMACS / OPB / JSON-Schema / FlatZinc) and `InCode` builders. */
object InProcessRunner : Runner {
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

package com.eignex.klause.cnf

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SampleResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause

/**
 * Solves a [Problem] by **bit-blasting it to CNF and running klause's own CDCL engine**
 * ([BacktrackSolver]) over the pure-Boolean encoding, then decoding models back to the
 * original variables.
 *
 * This is the bitblast-then-solve path the portfolio's SAT arm is built on: it discards the
 * high-level constraint structure in favour of a flat clause database, which wins on
 * Boolean/bit-vector/parity-heavy and small-domain feasibility instances where the global
 * propagators add little pruning. On structured problems the native engine (which keeps the
 * propagators) is expected to dominate — this is a *diversity* arm, not a replacement.
 *
 * [simplify] (on by default) runs [CnfSimplify.simplify] over the encoding first — subsumption
 * plus bounded variable elimination of the Tseitin aux variables — since eager bit-blasting
 * emits many redundant clauses and aux variables. The pass is model-preserving over the
 * decode variables. Set it `false` to solve the raw encoding.
 *
 * Feasibility only — there is no [com.eignex.klause.solver.Optimizer] implementation here:
 * optimisation over a bit-blasted objective needs bound-search/assumptions and is far better
 * served by the native engine's LP bounding. Use this for CSP.
 */
class BitblastSolver(override val problem: Problem, simplify: Boolean = true) : Solver<BacktrackParams> {

    /** The bit-blasted (and optionally simplified) encoding; decode tables live here. */
    val cnf: CnfProblem = BitBlaster.compile(problem).let { if (simplify) CnfSimplify.simplify(it) else it }

    /** An empty clause in the encoding means the formula is unconditionally UNSAT; [Clause]
     *  can't represent it (it watches `literals[0]`), so we detect it up front. */
    private val triviallyUnsat: Boolean = cnf.clauses.any { it.isEmpty() }

    /** The CNF as a pure-Boolean [Problem]: one [Clause] factor per (non-empty) clause over
     *  [CnfProblem.numVars] Boolean variables. */
    private val cnfProblem: Problem = Problem(
        numBoolVars = cnf.numVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = cnf.clauses.mapNotNull { lits -> if (lits.isEmpty()) null else Clause(lits.copyOf()) }
            .toTypedArray<Factor>(),
    )

    private val inner = BacktrackSolver(cnfProblem)

    /** Lift a CNF model (bits indexed by CNF variable id) back to the original variables. */
    fun decode(model: Sample): Sample = Sample(
        bools = BooleanArray(problem.numBoolVars) { cnf.decodeBool(it, model.bools) },
        ints = IntArray(problem.numIntVars) { cnf.decodeInt(it, model.bools) },
    )

    override fun solve(params: BacktrackParams): SolveResult {
        if (triviallyUnsat) return SolveResult.Unsat()
        return when (val r = inner.solve(params)) {
            is SolveResult.Sat -> SolveResult.Sat(decode(r.assignment), r.stats)
            else -> r // Unsat / Unknown carry through; their assignment-free payload is engine-agnostic
        }
    }

    override fun sample(params: BacktrackParams): SampleResult {
        if (triviallyUnsat) return SampleResult.Infeasible()
        return when (val r = inner.sample(params)) {
            is SampleResult.Found -> SampleResult.Found(decode(r.sample))
            else -> r
        }
    }

    override fun samples(params: BacktrackParams): Sequence<Sample> {
        if (triviallyUnsat) return emptySequence()
        return inner.samples(params).map(::decode)
    }

    /** Distinct models over the *original* variables. Decoding is many-to-one (different
     *  Tseitin-aux assignments project to the same original assignment), so CNF-distinct
     *  models are de-duplicated after decoding to honour the without-replacement contract. */
    override fun enumerate(params: BacktrackParams): Sequence<Sample> {
        if (triviallyUnsat) return emptySequence()
        return sequence {
            val seen = HashSet<Sample>()
            for (model in inner.enumerate(params)) {
                val decoded = decode(model)
                if (seen.add(decoded)) yield(decoded)
            }
        }
    }
}

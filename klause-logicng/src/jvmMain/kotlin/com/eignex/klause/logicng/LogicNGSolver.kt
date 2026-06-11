package com.eignex.klause.logicng

import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.cnf.CnfProblem
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.result.TerminationReason
import org.logicng.datastructures.Tristate
import org.logicng.formulas.Formula
import org.logicng.formulas.FormulaFactory
import org.logicng.formulas.Literal
import org.logicng.solvers.MiniSat
import org.logicng.solvers.SATSolver
import kotlin.random.Random
import org.logicng.datastructures.Assignment as LogicNGAssignment

/**
 * [Solver] backed by LogicNG's MiniSat. The problem is bit-blasted to CNF once via
 * [BitBlaster], then handed to LogicNG; each model LogicNG returns is decoded back to a
 * klause [Sample].
 *
 *  - [solve] — single SAT call; returns [SolveResult.Sat], [SolveResult.Unsat], or
 *    [SolveResult.Unknown] (timeout).
 *  - [samples] — *with replacement*. A fresh solver is built per draw so duplicate models
 *    can recur across iterations.
 *  - [enumerate] — *without replacement*. A single solver runs through models, with a
 *    blocking clause added after each yield. The `params.minHammingDistance` /
 *    `params.recentWindow` fields apply as a post-filter on top.
 */
class LogicNGSolver(override val problem: Problem) : Solver<LogicNGParams> {

    /** Bit-blasted CNF; exposed to [LogicNGSession] for the incremental-solving path. */
    internal val cnf: CnfProblem = BitBlaster.compile(problem)

    /** Return a [LogicNGSession] holding one MiniSat solver across calls so learned
     *  conflict clauses persist between `solve` / `samples` / `enumerate` calls. */
    override fun session(): LogicNGSession = LogicNGSession(this)

    override fun solve(params: LogicNGParams): SolveResult {
        val (_, satSolver) = buildSolver()
        return when (satSolver.sat()) {
            Tristate.TRUE -> SolveResult.Sat(decode(satSolver.model()))
            Tristate.FALSE -> SolveResult.Unsat()
            Tristate.UNDEF -> SolveResult.Unknown(TerminationReason.Timeout)
            null -> SolveResult.Unknown(TerminationReason.Timeout)
        }
    }

    /**
     * Independent random samples. Raw MiniSat with default config is deterministic, so
     * each yield pre-pins a random subset of CNF variables to random polarities, solves,
     * and decodes. Different pin subsets produce different models. If the random pins
     * happen to make the formula Unsat (rare for under-constrained problems), retry with
     * a fresh random subset; after several consecutive failures, fall back to an
     * unpinned solve so the contract isn't violated by a hostile pin set.
     */
    override fun samples(params: LogicNGParams): Sequence<Sample> = sequence {
        val rng = Random(params.randomSeed ?: System.nanoTime())
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val sample = drawDiverseSample(rng)
            if (sample == null) break
            yield(sample)
            attempts++
        }
    }

    /**
     * Pin a random subset of CNF vars and solve. Returns the resulting model, or null
     * when even the unpinned solve fails (problem is Unsat or solver returned Unknown).
     */
    private fun drawDiverseSample(rng: Random): Sample? {
        val pinCount = minOf(cnf.numVars / 2, RANDOM_PIN_COUNT_CAP)
        repeat(RANDOM_PIN_RETRIES) {
            val (factory, satSolver) = buildSolver()
            if (pinCount > 0) {
                val cnfVars = (0 until cnf.numVars).toMutableList().apply { shuffle(rng) }
                for (i in 0 until pinCount) {
                    val v = cnfVars[i]
                    val polarity = rng.nextBoolean()
                    satSolver.add(factory.literal(varName(v), polarity))
                }
            }
            if (satSolver.sat() == Tristate.TRUE) return decode(satSolver.model())
            // Pinned subset turned the problem Unsat — retry with a different random subset.
        }
        // Last resort: unpinned solve. Preserves the contract for over-constrained instances
        // where random pins keep colliding.
        val (_, satSolver) = buildSolver()
        return if (satSolver.sat() == Tristate.TRUE) decode(satSolver.model()) else null
    }

    override fun enumerate(params: LogicNGParams): Sequence<Sample> = sequence {
        val (factory, satSolver) = buildSolver()
        val window = ArrayDeque<Sample>()
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            if (satSolver.sat() != Tristate.TRUE) break
            val model = satSolver.model()
            val s = decode(model)
            attempts++
            satSolver.add(blockingClause(model, factory))
            if (farEnough(s, window, params.minHammingDistance)) {
                yield(s)
                if (params.recentWindow > 0) {
                    if (window.size >= params.recentWindow) window.removeFirst()
                    window.addLast(s)
                }
            }
        }
    }

    private fun buildSolver(): Pair<FormulaFactory, SATSolver> {
        val factory = FormulaFactory()
        val solver = MiniSat.miniSat(factory)
        for (clauseLits in cnf.clauses) {
            solver.add(clauseToFormula(clauseLits, factory))
        }
        return factory to solver
    }

    private companion object {
        /** Cap on the random-pin subset size used by [samples]. Larger → more diversity
         *  but more retries when pins induce Unsat. 8 is a reasonable balance for the
         *  small-to-medium SAT instances combo's bandit hits. */
        const val RANDOM_PIN_COUNT_CAP: Int = 8

        /** Retries on an Unsat-from-random-pins hit before falling back to an unpinned solve. */
        const val RANDOM_PIN_RETRIES: Int = 5
    }

    private fun clauseToFormula(clauseLits: IntArray, factory: FormulaFactory): Formula {
        val literals = clauseLits.map { lit ->
            factory.literal(varName(Lit.variable(lit)), Lit.isPositive(lit))
        }
        return when (literals.size) {
            0 -> factory.falsum()
            1 -> literals[0]
            else -> factory.or(literals)
        }
    }

    private fun blockingClause(model: LogicNGAssignment, factory: FormulaFactory): Formula {
        // For each CNF var, take the negation of its current value as a literal in the
        // blocking disjunction. The OR forbids the exact same model from being returned again.
        val literals = ArrayList<Literal>(cnf.numVars)
        for (v in 0 until cnf.numVars) {
            val varLit = factory.variable(varName(v))
            val isTrue = model.positiveVariables().contains(varLit)
            literals.add(factory.literal(varName(v), !isTrue))
        }
        return factory.or(literals)
    }

    private fun decode(model: LogicNGAssignment): Sample {
        // Build a BooleanArray indexed by CNF var id from the LogicNG model, then lift
        // back to original-problem (bool, int) values via the bit-blaster's decoders.
        val cnfModel = BooleanArray(cnf.numVars)
        for (lit in model.positiveVariables()) {
            val id = literalNameToId(lit.name())
            if (id in 0 until cnf.numVars) cnfModel[id] = true
        }
        val bools = BooleanArray(problem.numBoolVars) { cnf.decodeBool(it, cnfModel) }
        val ints = IntArray(problem.numIntVars) { cnf.decodeInt(it, cnfModel) }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }
    private fun varName(cnfVar: Int): String = "x$cnfVar"
    private fun literalNameToId(name: String): Int =
        if (name.startsWith("x")) name.substring(1).toIntOrNull() ?: -1 else -1
}

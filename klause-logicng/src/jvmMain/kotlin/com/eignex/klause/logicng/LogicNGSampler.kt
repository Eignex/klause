package com.eignex.klause.logicng

import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.cnf.CnfProblem
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Sampler
import com.eignex.klause.solver.SolveResult
import org.logicng.datastructures.Tristate
import org.logicng.formulas.Formula
import org.logicng.formulas.FormulaFactory
import org.logicng.formulas.Literal
import org.logicng.solvers.MiniSat
import org.logicng.solvers.SATSolver
import org.logicng.datastructures.Assignment as LogicNGAssignment

/**
 * [Sampler] backed by LogicNG's MiniSat. The problem is bit-blasted to CNF once via
 * [BitBlaster], then handed to LogicNG; each model LogicNG returns is decoded back to a
 * klause [Sample].
 *
 *  - [solve] — single SAT call; returns [SolveResult.Sat], [SolveResult.Unsat], or
 *    [SolveResult.Unknown] (timeout).
 *  - [sample] — *with replacement*. A fresh solver is built per draw so duplicate models
 *    can recur across iterations.
 *  - [enumerate] — *without replacement*. A single solver runs through models, with a
 *    blocking clause added after each yield. The `params.minHammingDistance` /
 *    `params.recentWindow` fields apply as a post-filter on top.
 */
class LogicNGSampler(override val problem: Problem) : Sampler<LogicNGParams> {

    private val cnf: CnfProblem = BitBlaster.compile(problem)

    override fun solve(params: LogicNGParams): SolveResult {
        val (_, satSolver) = buildSolver()
        return when (satSolver.sat()) {
            Tristate.TRUE -> SolveResult.Sat(decode(satSolver.model()))
            Tristate.FALSE -> SolveResult.Unsat
            Tristate.UNDEF -> SolveResult.Unknown
            null -> SolveResult.Unknown
        }
    }

    override fun samples(params: LogicNGParams): Sequence<Sample> = sequence {
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val (_, satSolver) = buildSolver()
            if (satSolver.sat() != Tristate.TRUE) break
            yield(decode(satSolver.model()))
            attempts++
        }
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

    // ---- helpers ----

    private fun buildSolver(): Pair<FormulaFactory, SATSolver> {
        val factory = FormulaFactory()
        val solver = MiniSat.miniSat(factory)
        for (clauseLits in cnf.clauses) {
            solver.add(clauseToFormula(clauseLits, factory))
        }
        return factory to solver
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
        for (p in window) if (hammingDistance(candidate, p) < minDistance) return false
        return true
    }

    private fun hammingDistance(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }

    private fun varName(cnfVar: Int): String = "x$cnfVar"
    private fun literalNameToId(name: String): Int =
        if (name.startsWith("x")) name.substring(1).toIntOrNull() ?: -1 else -1
}

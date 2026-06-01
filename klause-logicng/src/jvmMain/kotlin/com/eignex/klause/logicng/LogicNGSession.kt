package com.eignex.klause.logicng

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import org.logicng.datastructures.Tristate
import org.logicng.formulas.Formula
import org.logicng.formulas.FormulaFactory
import org.logicng.formulas.Literal
import org.logicng.solvers.MiniSat
import org.logicng.solvers.SATSolver
import kotlin.random.Random
import org.logicng.datastructures.Assignment as LogicNGAssignment

/**
 * Stateful session over a [LogicNGSolver]. Holds ONE underlying MiniSat solver instance
 * across `solve` / `sample` / `enumerate` calls — learned conflict clauses persist between
 * calls. Repeated solves under varying pinned subsets (optimization loops, incremental
 * sample diversification, ALNS pinning via [Assumptions]) are significantly faster than
 * spinning up a fresh solver per call because the solver already knows where the
 * difficulty lies.
 *
 * Assumption handling: push/pop maintains a kumulative stack; every call sees that stack
 * merged with the call-site `params.assumptions`. Merged pins are passed as MiniSat's
 * per-call *literal assumptions* (no permanent clauses), so popping leaves no residue —
 * the only persistent state is the conflict-learned clauses.
 *
 * `enumerate`'s blocking clauses ARE permanent for the session's lifetime: calling
 * `enumerate` a second time on the same session keeps the previously-yielded models
 * excluded. Construct a fresh session for independent enumeration semantics.
 *
 * Sessions are NOT thread-safe — one consumer per session.
 */
class LogicNGSession(override val solver: LogicNGSolver) : Session<LogicNGParams> {

    private val stack: ArrayDeque<Assumptions> = ArrayDeque()
    private val factory: FormulaFactory = FormulaFactory()
    private val satSolver: SATSolver = run {
        val s = MiniSat.miniSat(factory)
        for (clauseLits in solver.cnf.clauses) {
            s.add(clauseToFormula(clauseLits))
        }
        s
    }

    override val depth: Int get() = stack.size

    override fun push(assumptions: Assumptions) { stack.addLast(assumptions) }

    override fun pop() {
        require(stack.isNotEmpty()) { "Session.pop on an empty assumption stack" }
        stack.removeLast()
    }

    override fun solve(params: LogicNGParams): SolveResult {
        val lits = assumptionsToLiterals(mergedAssumptions(params.assumptions))
        return when (satSolver.sat(lits)) {
            Tristate.TRUE -> SolveResult.Sat(decode(satSolver.model()))
            Tristate.FALSE -> SolveResult.Unsat()
            else -> SolveResult.Unknown(TerminationReason.Timeout)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    override fun samples(params: LogicNGParams): Sequence<Sample> = sequence {
        val baseLits = assumptionsToLiterals(mergedAssumptions(params.assumptions))
        val rng = Random(params.randomSeed ?: System.nanoTime())
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val s = drawDiverseSample(baseLits, rng) ?: break
            yield(s)
            attempts++
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    override fun enumerate(params: LogicNGParams): Sequence<Sample> = sequence {
        val baseLits = assumptionsToLiterals(mergedAssumptions(params.assumptions))
        val window = ArrayDeque<Sample>()
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            if (satSolver.sat(baseLits) != Tristate.TRUE) break
            val model = satSolver.model()
            val s = decode(model)
            attempts++
            satSolver.add(blockingClause(model))
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

    private fun drawDiverseSample(baseLits: List<Literal>, rng: Random): Sample? {
        val cnf = solver.cnf
        val pinCount = minOf(cnf.numVars / 2, RANDOM_PIN_COUNT_CAP)
        repeat(RANDOM_PIN_RETRIES) {
            val perCall = ArrayList(baseLits)
            if (pinCount > 0) {
                val cnfVars = (0 until cnf.numVars).toMutableList().apply { shuffle(rng) }
                for (i in 0 until pinCount) {
                    perCall.add(factory.literal(varName(cnfVars[i]), rng.nextBoolean()))
                }
            }
            if (satSolver.sat(perCall) == Tristate.TRUE) return decode(satSolver.model())
        }
        if (satSolver.sat(baseLits) == Tristate.TRUE) return decode(satSolver.model())
        return null
    }

    /** Walk the session's assumption stack on top of the per-call assumptions, last-write-wins. */
    private fun mergedAssumptions(call: Assumptions): Assumptions {
        if (stack.isEmpty() && call.isEmpty) return Assumptions.None
        var merged = call
        for (a in stack) merged = merged.mergedWith(a)
        return merged
    }

    /** Translate klause-level [Assumptions] into LogicNG literals over the bit-blasted CNF. */
    private fun assumptionsToLiterals(a: Assumptions): List<Literal> {
        if (a.isEmpty) return emptyList()
        val cnf = solver.cnf
        val lits = ArrayList<Literal>()
        a.forEachBool { boolVar, value ->
            if (boolVar in 0 until cnf.boolVarToCnfVar.size) {
                lits.add(factory.literal(varName(cnf.boolVarToCnfVar[boolVar]), value))
            }
        }
        a.forEachInt { intVar, value ->
            if (intVar !in cnf.intVarBits.indices) return@forEachInt
            val bits = cnf.intVarBits[intVar]
            val offset = value - cnf.intVarMin[intVar]
            if (offset < 0 || offset >= (1 shl bits.size)) return@forEachInt
            for (i in bits.indices) {
                val bitVal = ((offset shr i) and 1) == 1
                lits.add(factory.literal(varName(bits[i]), bitVal))
            }
        }
        return lits
    }

    private fun clauseToFormula(clauseLits: IntArray): Formula {
        val literals = clauseLits.map { lit ->
            factory.literal(varName(Lit.variable(lit)), Lit.isPositive(lit))
        }
        return when (literals.size) {
            0 -> factory.falsum()
            1 -> literals[0]
            else -> factory.or(literals)
        }
    }

    private fun blockingClause(model: LogicNGAssignment): Formula {
        val cnf = solver.cnf
        val literals = ArrayList<Literal>(cnf.numVars)
        for (v in 0 until cnf.numVars) {
            val varLit = factory.variable(varName(v))
            val isTrue = model.positiveVariables().contains(varLit)
            literals.add(factory.literal(varName(v), !isTrue))
        }
        return factory.or(literals)
    }

    private fun decode(model: LogicNGAssignment): Sample {
        val cnf = solver.cnf
        val cnfModel = BooleanArray(cnf.numVars)
        for (lit in model.positiveVariables()) {
            val id = literalNameToId(lit.name())
            if (id in 0 until cnf.numVars) cnfModel[id] = true
        }
        val bools = BooleanArray(solver.problem.numBoolVars) { cnf.decodeBool(it, cnfModel) }
        val ints = IntArray(solver.problem.numIntVars) { cnf.decodeInt(it, cnfModel) }
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

    private companion object {
        const val RANDOM_PIN_COUNT_CAP: Int = 8
        const val RANDOM_PIN_RETRIES: Int = 5
    }
}

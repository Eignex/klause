package com.eignex.klause.smt

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.UnsatCore
import org.sosy_lab.common.ShutdownNotifier
import org.sosy_lab.common.configuration.Configuration
import org.sosy_lab.common.log.LogManager
import org.sosy_lab.java_smt.SolverContextFactory
import org.sosy_lab.java_smt.api.BooleanFormula
import org.sosy_lab.java_smt.api.Model
import org.sosy_lab.java_smt.api.ProverEnvironment
import org.sosy_lab.java_smt.api.SolverContext
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions
import kotlin.random.Random

/**
 * Stateful session over an [SmtSolver]. Holds ONE [SolverContext] plus ONE
 * [ProverEnvironment] across `solve` / `samples` / `enumerate` calls — the problem is
 * encoded into SMT formulas exactly once, conflict clauses learned by the backend stay
 * resident, and assumption scopes map onto JavaSMT's native [ProverEnvironment.push] /
 * [ProverEnvironment.pop] so popping leaves no residue.
 *
 * The JavaSMT backend is *locked at session-construction time* (defaulting to SMTInterpol
 * via [SmtParams]'s default). Per-call params still drive `randomSeed`, deadlines, and
 * assumptions, but `params.solver` is ignored once the session is open — open a fresh
 * session if you want a different backend.
 *
 * `enumerate`'s blocking clauses are added permanently to the prover (outside any
 * push/pop scope), so a second `enumerate` on the same session keeps previously-yielded
 * models excluded. Construct a fresh session if you want independent enumeration.
 *
 * Sessions are NOT thread-safe — one consumer per session. Call [close] when done so the
 * native solver handle is released.
 */
class SmtSession(override val solver: SmtSolver, initialParams: SmtParams = SmtParams()) : Session<SmtParams> {

    private val context: SolverContext = run {
        val config = Configuration.defaultConfiguration()
        val logger = LogManager.createNullLogManager()
        val notifier = ShutdownNotifier.createDummy()
        SolverContextFactory.createSolverContext(config, logger, notifier, initialParams.solver)
    }
    private val encoding: SmtEncoding
    private val prover: ProverEnvironment
    private val stack: ArrayDeque<Assumptions> = ArrayDeque()
    private var closed: Boolean = false

    /** Reverse map from factor-derived assertion back to its [com.eignex.klause.solver.Problem.factors]
     *  index. Populated at session construction; lookups happen on UNSAT to attribute
     *  the prover's reported unsat core back to klause factor ids. */
    private val factorByFormula: HashMap<BooleanFormula, Int>

    init {
        val t = SmtTranslator.translate(solver.problem, context.formulaManager)
        encoding = t.encoding
        prover = context.newProverEnvironment(
            ProverOptions.GENERATE_MODELS,
            ProverOptions.GENERATE_UNSAT_CORE,
        )
        for (c in t.auxiliary) prover.addConstraint(c)
        factorByFormula = HashMap(t.factorFormulas.size * 2)
        for (fid in t.factorFormulas.indices) {
            val f = t.factorFormulas[fid]
            prover.addConstraint(f)
            factorByFormula[f] = fid
        }
    }

    override val depth: Int get() = stack.size

    override fun push(assumptions: Assumptions) {
        check(!closed) { "SmtSession is closed" }
        prover.push()
        addAssumptionConstraints(assumptions)
        stack.addLast(assumptions)
    }

    override fun pop() {
        check(!closed) { "SmtSession is closed" }
        require(stack.isNotEmpty()) { "Session.pop on an empty assumption stack" }
        prover.pop()
        stack.removeLast()
    }

    override fun solve(params: SmtParams): SolveResult {
        check(!closed) { "SmtSession is closed" }
        return withScope(params.assumptions) {
            if (prover.isUnsat) {
                SolveResult.Unsat(extractCore())
            } else {
                SolveResult.Sat(decode(prover.model))
            }
        }
    }

    /** Attribute the prover's unsat core (a list of formulas it added) back to klause
     *  factor ids. Returns `null` when the backend doesn't honour
     *  [ProverOptions.GENERATE_UNSAT_CORE] (returns empty / throws). */
    private fun extractCore(): UnsatCore? {
        val coreFormulas = try {
            prover.unsatCore
        } catch (_: UnsupportedOperationException) {
            return null
        }
        if (coreFormulas.isEmpty()) return null
        val ids = IntArray(coreFormulas.size)
        var w = 0
        for (f in coreFormulas) {
            val id = factorByFormula[f] ?: continue
            ids[w++] = id
        }
        if (w == 0) return null
        return UnsatCore.of(if (w == ids.size) ids else ids.copyOf(w))
    }

    /**
     * Independent random samples. Per draw, the call-site assumptions plus a random
     * subset of pinned bool/int values are pushed in a fresh prover scope, the prover
     * solves, the model is decoded, then the scope is popped — leaving no residue and
     * no impact on subsequent draws.
     */
    override fun samples(params: SmtParams): Sequence<Sample> = sequence {
        if (closed) return@sequence
        val rng = Random(params.randomSeed ?: System.nanoTime())
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        var attempts = 0L
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val sample = drawDiverseSample(params.assumptions, rng) ?: break
            yield(sample)
            attempts++
        }
    }

    /**
     * Models without replacement. Each yielded model gets a blocking clause added at the
     * prover's top level (no push/pop) so it persists for the rest of the session's
     * lifetime. The call-site assumptions sit in their own scope so popping ends
     * enumeration cleanly.
     */
    override fun enumerate(params: SmtParams): Sequence<Sample> = sequence {
        if (closed) return@sequence
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        val window = ArrayDeque<Sample>()
        var attempts = 0L
        prover.push()
        try {
            addAssumptionConstraints(params.assumptions)
            while (attempts < params.maxModels) {
                if (deadline != null && System.currentTimeMillis() > deadline) break
                if (prover.isUnsat) break
                val s = decode(prover.model)
                attempts++
                // Blocking clause at the prover's outermost level is what we want for an
                // enumeration: persists past the per-call scope below so reuse semantics
                // (no repeats) hold across calls too. JavaSMT applies addConstraint at the
                // current top — pop our scope, add the block, push again so the assumption
                // context is reinstated.
                val block = blockingClause(s)
                prover.pop()
                prover.addConstraint(block)
                prover.push()
                addAssumptionConstraints(params.assumptions)
                if (farEnough(s, window, params.minHammingDistance)) {
                    yield(s)
                    if (params.recentWindow > 0) {
                        if (window.size >= params.recentWindow) window.removeFirst()
                        window.addLast(s)
                    }
                }
            }
        } finally {
            prover.pop()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            prover.close()
        } finally {
            context.close()
        }
    }

    private inline fun <T> withScope(call: Assumptions, body: () -> T): T {
        prover.push()
        try {
            addAssumptionConstraints(call)
            return body()
        } finally {
            prover.pop()
        }
    }

    private fun addAssumptionConstraints(a: Assumptions) {
        if (a.isEmpty) return
        val bmgr = encoding.fm.booleanFormulaManager
        val imgr = encoding.fm.integerFormulaManager
        a.forEachBool { boolVar, value ->
            val lit = encoding.boolFormulas[boolVar]
            prover.addConstraint(if (value) lit else bmgr.not(lit))
        }
        a.forEachInt { intVar, value ->
            prover.addConstraint(imgr.equal(encoding.intFormulas[intVar], imgr.makeNumber(value.toLong())))
        }
    }

    private fun drawDiverseSample(call: Assumptions, rng: Random): Sample? {
        val numBool = solver.problem.numBoolVars
        val numInt = solver.problem.numIntVars
        val totalVars = numBool + numInt
        val pinCount = minOf(totalVars / 2, RANDOM_PIN_COUNT_CAP)
        repeat(RANDOM_PIN_RETRIES) {
            prover.push()
            try {
                addAssumptionConstraints(call)
                if (pinCount > 0) {
                    val candidates = (0 until totalVars).toMutableList().apply { shuffle(rng) }
                    for (i in 0 until pinCount) {
                        val v = candidates[i]
                        if (v < numBool) {
                            if (call.isFrozenBool(v)) continue
                            val bmgr = encoding.fm.booleanFormulaManager
                            val lit = encoding.boolFormulas[v]
                            prover.addConstraint(if (rng.nextBoolean()) lit else bmgr.not(lit))
                        } else {
                            val intIdx = v - numBool
                            if (call.isFrozenInt(intIdx)) continue
                            val d = solver.problem.intDomains[intIdx]
                            val span = d.max - d.min + 1
                            val pick = d.min + rng.nextInt(span)
                            val imgr = encoding.fm.integerFormulaManager
                            prover.addConstraint(
                                imgr.equal(encoding.intFormulas[intIdx], imgr.makeNumber(pick.toLong())),
                            )
                        }
                    }
                }
                if (!prover.isUnsat) return decode(prover.model)
            } finally {
                prover.pop()
            }
        }
        // Last resort: unpinned solve, just the call-site assumptions.
        prover.push()
        try {
            addAssumptionConstraints(call)
            return if (!prover.isUnsat) decode(prover.model) else null
        } finally {
            prover.pop()
        }
    }

    private fun blockingClause(s: Sample): BooleanFormula {
        val bmgr = encoding.fm.booleanFormulaManager
        val imgr = encoding.fm.integerFormulaManager
        val disjuncts = ArrayList<BooleanFormula>(s.bools.size + s.ints.size)
        for (i in s.bools.indices) {
            val v = encoding.boolFormulas[i]
            disjuncts.add(if (s.bools[i]) bmgr.not(v) else v)
        }
        for (i in s.ints.indices) {
            disjuncts.add(bmgr.not(imgr.equal(encoding.intFormulas[i], imgr.makeNumber(s.ints[i].toLong()))))
        }
        return if (disjuncts.isEmpty()) bmgr.makeFalse() else bmgr.or(disjuncts)
    }

    private fun decode(model: Model): Sample {
        val bools = BooleanArray(encoding.boolFormulas.size) { i ->
            model.evaluate(encoding.boolFormulas[i]) == true
        }
        val ints = IntArray(encoding.intFormulas.size) { i ->
            model.evaluate(encoding.intFormulas[i])?.toInt() ?: 0
        }
        val meta = solver.problem.floatMetadata
        if (meta != null) {
            for (fid in 0 until meta.numFloatVars) {
                val real = model.evaluate(encoding.realFormulas[fid])?.toDouble() ?: 0.0
                val ivl = meta.intervals[fid]
                val buckets = meta.bucketCounts[fid]
                val bucket = if (buckets <= 1) {
                    0
                } else {
                    (((real - ivl.lo) / (ivl.hi - ivl.lo)) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
                }
                ints[meta.intVarByFloatVar[fid]] = bucket
            }
        }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }
    private companion object {
        const val RANDOM_PIN_COUNT_CAP: Int = 8
        const val RANDOM_PIN_RETRIES: Int = 5
    }
}

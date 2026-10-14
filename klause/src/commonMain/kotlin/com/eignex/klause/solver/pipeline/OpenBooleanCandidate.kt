package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.HintIndexOutcome
import com.eignex.klause.solver.result.OpenHintUsageSink
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.util.Cancellation

/** Local-search work allowance spent on one candidate draw. */
private const val DEFAULT_CANDIDATE_FLIPS = 20_000L

/**
 * Boolean-only finite projection of the shared clauses a [ComponentPlan] selects.
 *
 * Factor ownership decides what enters, not the theory route: every factor the plan marks
 * [FactorOwner.SHARED] is a clause over source Boolean ids, so the projection keeps the source
 * [Problem.numBoolVars] and carries no integer or real column at all. Theory-owned columns stay
 * symbolic — nothing here materializes a domain for one, and no arithmetic reaches the search.
 */
internal class OpenBooleanSkeleton(
    /** Finite Boolean-only problem holding exactly the plan's shared clauses. */
    val problem: BakedProblem,
    /** Source Boolean ids the shared clauses constrain, ascending. */
    val boolVars: IntArray,
)

/**
 * An unverified Boolean proposal drawn from an [OpenBooleanSkeleton].
 *
 * It satisfies the shared clauses and states nothing beyond them: the theory- and CP-owned halves of the
 * model were never consulted, so this is a starting point for a later search, never a verdict about the
 * model it came from.
 */
internal class OpenBooleanCandidate(
    /** Source Boolean ids this proposal covers, ascending. */
    val boolVars: IntArray,
    /** Proposed value of the id at the same index of [boolVars]. */
    val values: BooleanArray,
)

internal class SourceConjunctionHints private constructor(
    private var primitive: SearchCandidateHints,
    private val inputs: MutableSet<Int>,
    private val gates: MutableMap<Int, Gate>,
    private val stop: Cancellation,
    private val usage: OpenHintUsageSink?,
) : SearchCandidateHints, AutoCloseable {
    private var clauses: SourceClauseGuidance? = null
    private var available: (() -> Boolean)? = null
    private var knownBool: (Int) -> Boolean? = { null }

    override fun preferredBool(variable: Int): Boolean? {
        val live = available
        if (live != null && !live()) return null
        val consultation = Consultation()
        val fallback = forward(variable, consultation)
        if (live == null) return fallback
        if (stop() || !live()) return null
        if (fallback != null) {
            if (!consultation.visit() || !live()) return null
            if (consultation.isPrimitive(variable)) return fallback
        }
        val scoring = clauses ?: return fallback
        usage?.clauseConsulted()
        val preferred = scoring.preferredBool(variable, consultation)
        if (stop() || !live()) {
            usage?.clauseDeclined()
            return null
        }
        if (preferred == null || consultation.declined) {
            usage?.clauseDeclined()
            return fallback
        }
        usage?.clauseSelected()
        return preferred
    }

    internal inner class Consultation {
        private var visits = 0
        private var entries = 0
        var declined = false
            private set
        private val primitiveValues = HashMap<Int, Boolean?>()
        private val knownValues = HashMap<Int, Boolean?>()

        init { reserve(2) }

        fun visit(count: Int = 1): Boolean {
            if (count > MAX_INDEX_ENTRIES - visits) {
                declined = true
                return false
            }
            visits += count
            return !stopped(stop, count).also { if (it) declined = true }
        }

        fun primitive(variable: Int): Boolean? {
            if (!visit()) return null
            if (variable !in primitiveValues) {
                if (!reserve()) return null
                primitiveValues[variable] = primitive.preferredBool(variable).takeUnless { stop() }
            }
            return primitiveValues[variable]
        }

        fun known(variable: Int): Boolean? {
            if (!visit()) return null
            if (variable !in knownValues) {
                if (!reserve()) return null
                knownValues[variable] = knownBool(variable)
            }
            return knownValues[variable]
        }

        fun isPrimitive(variable: Int): Boolean = variable in inputs

        fun reserve(count: Int = 1): Boolean {
            if (count > MAX_INDEX_ENTRIES - entries) {
                declined = true
                return false
            }
            entries += count
            return visit(count)
        }
    }

    private fun forward(variable: Int, consultation: Consultation): Boolean? {
        if (!consultation.visit(1)) return null
        if (variable in inputs) return consultation.primitive(variable).takeUnless { stop() }
        if (variable !in gates) return null
        if (!consultation.reserve(4)) return null
        val values = HashMap<Int, Boolean>()
        val visiting = HashSet<Int>()
        val stack = arrayListOf(variable)
        while (stack.isNotEmpty()) {
            if (!consultation.visit(3) || values.size + visiting.size + stack.size > MAX_INDEX_ENTRIES) return null
            val current = stack.last()
            if (current in values) {
                stack.removeAt(stack.lastIndex)
                continue
            }
            if (current in inputs) {
                val value = consultation.primitive(current) ?: return null
                if (!consultation.reserve()) return null
                stack.removeAt(stack.lastIndex)
                values[current] = value
                continue
            }
            val gate = gates[current] ?: return null
            if (current !in visiting) {
                if (values.size + visiting.size + stack.size + gate.inputs.size + 1 > MAX_INDEX_ENTRIES) return null
                visiting += current
                if (!consultation.reserve(gate.inputs.size + 1)) return null
                for (literal in gate.inputs) {
                    if (!consultation.visit(1)) return null
                    val input = Lit.variable(literal)
                    if (input in visiting) return null
                    if (input !in values) stack += input
                }
            } else {
                var value = true
                for (literal in gate.inputs) {
                    if (!consultation.visit(1)) return null
                    val input = values[Lit.variable(literal)] ?: return null
                    value = value && input == Lit.isPositive(literal)
                }
                visiting.remove(current)
                stack.removeAt(stack.lastIndex)
                values[current] = value == Lit.isPositive(gate.target)
                if (!consultation.reserve()) return null
            }
        }
        return values[variable].takeUnless { stop() }
    }

    internal fun isPrimitive(variable: Int): Boolean = variable in inputs

    internal fun isGate(variable: Int): Boolean = variable in gates && variable !in inputs

    override fun close() {
        clauses?.close()
        clauses = null
        available = null
        knownBool = { null }
        primitive = SearchCandidateHints.None
        inputs.clear()
        gates.clear()
    }

    internal class Gate(val target: Int, val inputs: IntArray)

    companion object {
        // Optional source-clause indexing bounds private hint storage, not Boolean search coverage.
        const val MAX_INDEX_ENTRIES = 65_536

        fun create(
            clauses: List<Clause>,
            boolCount: Int,
            variables: IntArray,
            primitive: SearchCandidateHints,
            stop: Cancellation,
            maxClauses: Int = MAX_INDEX_ENTRIES,
            usage: OpenHintUsageSink? = null,
            available: (() -> Boolean)? = null,
            knownBool: (Int) -> Boolean? = { null },
            maxClauseEntries: Int = MAX_INDEX_ENTRIES,
        ): SourceConjunctionHints? {
            var outcome = HintIndexOutcome.ABORTED
            fun decline(reason: HintIndexOutcome): SourceConjunctionHints? {
                outcome = reason
                return null
            }
            try {
                if (clauses.size > maxClauses || variables.size > maxClauses) {
                    return decline(HintIndexOutcome.SOURCE_LIMIT)
                }
                if (stop()) return decline(HintIndexOutcome.STOPPED)
                val inputs = HashSet<Int>()
                var preparationVisits = 0L
                for (variable in variables) {
                    preparationVisits++
                    if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                    if (variable !in 0 until boolCount) return decline(HintIndexOutcome.INVALID_PRIMITIVE)
                    inputs += variable
                }
                val pairs = HashSet<Long>()
                for (clause in clauses) {
                    preparationVisits += 1 + minOf(3, clause.literals.size)
                    if (stopped(stop, 1 + minOf(3, clause.literals.size))) return decline(HintIndexOutcome.STOPPED)
                    val literals = clause.literals
                    if (literals.size != 2 || clause.tautological) continue
                    if (literals.any { Lit.variable(it) !in 0 until boolCount }) continue
                    val key = pair(literals[0], literals[1])
                    if (key !in pairs && inputs.size + pairs.size >= MAX_INDEX_ENTRIES) {
                        return decline(HintIndexOutcome.STORAGE)
                    }
                    pairs += key
                }
                val gates = HashMap<Int, Gate>()
                val ambiguous = HashSet<Int>()
                var entries = inputs.size + pairs.size
                var visits = 0L
                for (clause in clauses) {
                    if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                    if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                    val literals = clause.literals
                    if (literals.size < 3 || clause.tautological) continue
                    if (literals.size > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.STORAGE)
                    var valid = true
                    for (literal in literals) {
                        if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                        if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                        if (Lit.variable(literal) !in 0 until boolCount) valid = false
                    }
                    if (!valid) continue
                    for (index in literals.indices) {
                        if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                        if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                        val target = literals[index]
                        val variable = Lit.variable(target)
                        if (variable in inputs || variable in ambiguous) continue
                        val count = literals.size - 1
                        if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                        if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                        val first = if (index == 0) 1 else 0
                        if (pair(Lit.negate(target), Lit.negate(literals[first])) !in pairs) continue
                        if (entries.toLong() + count + 1L > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.STORAGE)
                        visits += count
                        if (visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                        if (stopped(stop, count)) return decline(HintIndexOutcome.STOPPED)
                        val operands = IntArray(count)
                        var complete = true
                        var next = 0
                        for (other in literals.indices) {
                            if (other == index) continue
                            if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                            if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                            val operand = Lit.negate(literals[other])
                            if (pair(Lit.negate(target), operand) !in pairs) {
                                complete = false
                                break
                            }
                            operands[next++] = operand
                        }
                        if (!complete) continue
                        var width = count
                        var levels = 0
                        while (width > 0) { width = width ushr 1; levels++ }
                        val sortingWork = count.toLong() * levels
                        visits += sortingWork
                        if (visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                        if (stopped(stop, sortingWork.toInt())) return decline(HintIndexOutcome.STOPPED)
                        operands.sort()
                        var previous = -1
                        for (operand in operands) {
                            if (++visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                            if (stopped(stop, 1)) return decline(HintIndexOutcome.STOPPED)
                            val input = Lit.variable(operand)
                            if (input == variable || input == previous) complete = false
                            previous = input
                        }
                        if (!complete) continue
                        val prior = gates[variable]
                        if (prior != null) {
                            visits += count
                            if (visits > MAX_INDEX_ENTRIES) return decline(HintIndexOutcome.VISITS)
                            if (stopped(stop, count)) return decline(HintIndexOutcome.STOPPED)
                        }
                        if (prior != null && (prior.target != target || !prior.inputs.contentEquals(operands))) {
                            gates.remove(variable)
                            ambiguous += variable
                            entries -= prior.inputs.size
                        } else if (prior == null) {
                            gates[variable] = Gate(target, operands)
                            entries += operands.size + 1
                        }
                    }
                }
                val result = SourceConjunctionHints(primitive, inputs, gates, stop, usage)
                if (available != null) {
                    result.available = available
                    result.knownBool = knownBool
                    result.clauses = SourceClauseGuidance.create(
                        clauses, boolCount, gates, ambiguous, stop,
                        minOf(maxClauseEntries, MAX_INDEX_ENTRIES - entries),
                        MAX_INDEX_ENTRIES - visits - preparationVisits,
                    )
                    usage?.clauseIndex(result.clauses != null)
                }
                outcome = HintIndexOutcome.ADMITTED
                return result
            } finally {
                usage?.index(outcome)
            }
        }

        private fun pair(a: Int, b: Int): Long =
            (minOf(a, b).toLong() shl 32) or (maxOf(a, b).toLong() and 0xffff_ffffL)

        private fun stopped(stop: Cancellation, visits: Int): Boolean {
            stop.charge(visits.toLong())
            return stop()
        }
    }
}

/** This proposal as unverified branch-order steering, the only thing a search may do with it. */
internal fun OpenBooleanCandidate.hints(): SearchCandidateHints =
    SearchCandidateHints.ofLiterals(IntArray(boolVars.size) { Lit.make(boolVars[it], values[it]) })

/**
 * A hint that draws its proposal only once [minSplits] splits have consulted it.
 *
 * The producer costs work before the traversal can use anything, and a model whose Boolean columns
 * propagation settles reaches almost no split at all — so drawing up front spends the allowance exactly
 * where no branch order could have paid for it. Deferring makes the search prove it is branching-bound
 * first: the shared branching consults a hint once per two-polarity Boolean split and only then, so the
 * consultations are the evidence. Nothing is refused for its size, and a search that never reaches the
 * threshold simply never pays.
 *
 * The first [minSplits] splits keep the default order, which is what a hint that had not been drawn yet
 * would have given them anyway.
 */
internal class DeferredCandidateHints(private val minSplits: Long, private val draw: () -> SearchCandidateHints) :
    SearchCandidateHints {

    private var splits = 0L
    private var drawn: SearchCandidateHints? = null

    override fun preferredBool(variable: Int): Boolean? {
        drawn?.let { return it.preferredBool(variable) }
        if (++splits < minSplits) return null
        val hints = draw()
        drawn = hints
        return hints.preferredBool(variable)
    }
}

/**
 * A hint that counts the splits it decided the first branch of.
 *
 * Drawing a proposal and steering a traversal are different things: propagation may fix every hinted
 * column before a split reaches it, so a hint covering half the model can still order nothing. The
 * shared branching consults a hint once per two-polarity Boolean split and only then, which is what
 * makes the consultation count the usage.
 */
internal class CountingCandidateHints(private val delegate: SearchCandidateHints) : SearchCandidateHints {

    /** Splits whose first branch this hint selected. */
    var steeredSplits: Long = 0L
        private set

    override fun preferredBool(variable: Int): Boolean? = delegate.preferredBool(variable)?.also { steeredSplits++ }
}

/**
 * What one draw from a plan's shared clauses produced, and the local-search work it spent.
 *
 * The cost is reported whether or not a proposal came out of it, since a draw that reached none is
 * overhead the traversal never gets back — exactly the part a measurement of the producer must see.
 */
internal class OpenBooleanDraw(
    /** The proposal, or null when the draw reached none. */
    val candidate: OpenBooleanCandidate?,
    /** Local-search moves the draw spent. */
    val moves: Long,
) {
    internal companion object {
        /** The draw of a plan with no shared clause to draw from, which spends nothing. */
        val NOTHING: OpenBooleanDraw = OpenBooleanDraw(null, 0)
    }
}

/** Bounded, deterministic settings for [ComponentPlan.openBooleanDraw]. */
internal data class OpenCandidateParams(
    /** Local-search work allowance; a zero allowance produces nothing. */
    val maxFlips: Long = DEFAULT_CANDIDATE_FLIPS,
    /** Seed of the search RNG, fixed so one skeleton and allowance always draw the same proposal. */
    val randomSeed: Long = 1L,
    /** Cooperative cancellation token, observed by the projection's bake and by the search. */
    val cancellation: Cancellation = Cancellation.Never,
)

/**
 * The shared clause skeleton of [source] under this plan, or null when the plan shares no clause.
 *
 * Boolean ids are the source ids: the projection reuses the source column count and remaps nothing, so a
 * proposal drawn from it names the same variables the model does.
 */
internal fun ComponentPlan.booleanSkeleton(
    source: Problem,
    cancellation: Cancellation = Cancellation.Never,
): OpenBooleanSkeleton? {
    if (source.numBoolVars == 0) return null
    val shared = source.factors.indices.filter {
        factorOwner(it) == FactorOwner.SHARED && source.factors[it] is Clause
    }
    if (shared.isEmpty()) return null
    val clauses = shared.map { source.factors[it] }
    val touched = BooleanArray(source.numBoolVars)
    for (clause in clauses) for (v in clause.variables.boolVars) touched[v] = true
    val skeleton = Problem(
        settings = source.settings,
        numBoolVars = source.numBoolVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses,
        impliedFactorMask = source.impliedFactorMask?.let { mask ->
            BooleanArray(shared.size) { mask[shared[it]] }
        },
    )
    return OpenBooleanSkeleton(
        skeleton.bake(cancellation),
        IntArray(touched.count { it }).also { out ->
            var next = 0
            for (v in touched.indices) if (touched[v]) out[next++] = v
        },
    )
}

/**
 * Draw one unverified Boolean proposal from this plan's shared clauses, proposing nothing when there is
 * nothing to propose — no shared clause, no assignment reached within [params], or a cancelled draw.
 *
 * The recipe is probSAT: the skeleton is a pure clause model, so a break-driven walk is the arm that fits
 * it, and a fixed seed under a fixed allowance makes the draw reproducible. Root propagation ruling the
 * skeleton out is one more way to have nothing to propose, not a refutation of [source] — the clauses are
 * only part of the model.
 */
internal fun ComponentPlan.openBooleanDraw(
    source: Problem,
    params: OpenCandidateParams = OpenCandidateParams(),
): OpenBooleanDraw {
    val skeleton = booleanSkeleton(source, params.cancellation) ?: return OpenBooleanDraw.NOTHING
    val drawn = LocalSearchSolver(skeleton.problem, strategy = ProbSat.adaptive()).solve(
        LocalSearchParams(
            maxFlips = params.maxFlips,
            randomSeed = params.randomSeed,
            cancellation = params.cancellation,
        ),
    )
    val moves = drawn.stats.ls.moves.sum.toLong()
    val sample = (drawn as? SolveResult.Sat)?.assignment ?: return OpenBooleanDraw(null, moves)
    val vars = skeleton.boolVars
    return OpenBooleanDraw(
        OpenBooleanCandidate(vars, BooleanArray(vars.size) { sample.boolValue(vars[it]) }),
        moves,
    )
}

internal fun observedCandidateHints(
    caller: SearchCandidateHints,
    component: SearchCandidateHints,
    usage: OpenHintUsageSink,
): SearchCandidateHints {
    if (caller === SearchCandidateHints.None && component === SearchCandidateHints.None) {
        return SearchCandidateHints.None
    }
    return SearchCandidateHints { variable ->
        if (caller !== SearchCandidateHints.None) {
            usage.callerConsulted()
            val preferred = caller.preferredBool(variable)
            if (preferred != null) {
                usage.callerSelected()
                return@SearchCandidateHints preferred
            }
        }
        if (component === SearchCandidateHints.None) return@SearchCandidateHints null
        usage.componentConsulted()
        component.preferredBool(variable)?.also {
            val indexed = component as? SourceConjunctionHints
            usage.componentSelected(indexed?.isPrimitive(variable), indexed?.isGate(variable) == true)
        }
    }
}

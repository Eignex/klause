package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

internal data class RegularPrefixDefinitions(val problem: Problem, val states: IntArray, val booleans: IntArray)

internal fun regularPrefixDefinitions(source: Problem): RegularPrefixDefinitions {
    require(source.numRealVars == 0)
    val selected = source.factors.filterIsInstance<Regular>().let {
        if (source.numIntVars <= 8) it else it.take(1)
    }
    require(selected.isNotEmpty())
    val domains = (0 until source.numIntVars).map { requireNotNull(source.intDomainOrNull(it)) }.toMutableList()
    val factors = source.factors.toMutableList()
    val prefixes = HashMap<String, Int>()
    var cells = 0
    var chosen = IntArray(0)
    for (factor in selected) {
        require(factor.seq.size <= 32 && factor.numStates <= 64 && factor.alphabetSize <= 16)
        require(factor.seq.toSet().size == factor.seq.size)
        require(factor.seq.all { domains[it].min >= 1 && domains[it].max <= factor.alphabetSize })
        val kernel = "${factor.numStates}/${factor.alphabetSize}/${factor.q0}/${factor.transitions.joinToString(",")}:"
        val chain = IntArray(factor.seq.size + 1)
        for (i in chain.indices) {
            val key = kernel + factor.seq.take(i).joinToString(",")
            val previous = prefixes[key]
            chain[i] = previous ?: domains.size.also { variable ->
                domains.add(
                    if (i == 0) {
                        IntDomain(factor.q0.toLong(), factor.q0.toLong())
                    } else {
                        IntDomain(0, factor.numStates.toLong())
                    },
                )
                prefixes[key] = variable
                if (i > 0) {
                    val tuples = ArrayList<Long>()
                    for (q in 0..factor.numStates) {
                        for (symbol in 1..factor.alphabetSize) {
                            val next = if (q == 0) {
                                0L
                            } else {
                                factor.transitions[(q - 1) * factor.alphabetSize + symbol - 1]
                                    .takeIf { it in 1L..factor.numStates.toLong() } ?: 0L
                            }
                            tuples.add(q.toLong())
                            tuples.add(symbol.toLong())
                            tuples.add(next)
                        }
                    }
                    cells += tuples.size
                    require(cells <= 32_768)
                    factors.add(Table(intArrayOf(chain[i - 1], factor.seq[i - 1], variable), tuples.toLongArray()))
                }
            }
        }
        factors.add(Table(intArrayOf(chain.last()), factor.accepting.map(Int::toLong).toLongArray()))
        if (chain.size > chosen.size) chosen = chain
    }
    val boundary = if (source.numIntVars <= 8) chosen.size - 3 else (chosen.size - 1) / 2
    val target = chosen[boundary]
    val count = domains[target].max.toInt()
    val booleans = IntArray(count) { source.numBoolVars + it }
    for (q in 1..count) {
        factors.add(
            ReifiedLinear(
                booleans[q - 1],
                longArrayOf(1),
                intArrayOf(target),
                LinearOp.EQ,
                q.toLong(),
            ),
        )
    }
    val mask = source.impliedFactorMask?.let { original ->
        BooleanArray(
            factors.size,
        ) { it < original.size && original[it] }
    }
    return RegularPrefixDefinitions(
        Problem(
            source.numBoolVars + count,
            domains.size,
            domains.toTypedArray(),
            factors.toTypedArray(),
            impliedFactorMask = mask,
            hasSymmetryBreaking = source.hasSymmetryBreaking,
            settings = source.settings,
            semantics = source.semantics,
        ),
        chosen,
        booleans,
    )
}

internal object RegularPrefixReplay {
    fun capture(source: Problem, identity: String, out: File, original: Problem, reconstruct: (Sample) -> Sample) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val definitions = regularPrefixDefinitions(source)
        val definitionNs = System.nanoTime() - start
        val definitionBytes = bean.getThreadAllocatedBytes(thread) - before
        val records = out.resolve("replay.jsonl").apply { writeText("") }
        for (mode in listOf(
            "native",
            "source-reasons",
            "definitions-source-reasons",
            "definitions-learning",
            "definitions-branching",
        )) {
            val model = if (mode.startsWith("definitions")) definitions.problem else source
            val allocated = bean.getThreadAllocatedBytes(thread)
            val started = System.nanoTime()
            val stop = Cancellation.after(2.seconds)
            val native = PropagationSession(model, cancellation = stop)
            val originalBranching = CpBranching { session, sourceId ->
                originalAlternatives(session, source.numIntVars, sourceId)
            }
            val cp = CpSearchComponent(native, branching = originalBranching)
            val component = if (mode.endsWith("source-reasons")) {
                SourceReasons(
                    cp,
                    source.numIntVars,
                    source.numBoolVars,
                )
            } else {
                cp
            }
            val shared = SearchSession(
                listOf(component),
                cancellation = stop,
                atoms = SearchAtomRegistry(model.numBoolVars),
            )
            var decisions = 0
            var conflicts = 0
            var helperDecisions = 0
            val observer = object : SearchRunObserver {
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    decisions++
                    if (decision is SearchDecision.Bool && Lit.variable(decision.literal) >= source.numBoolVars) {
                        helperDecisions++
                    }
                }
                override fun onConflict(decision: SearchDecision?) {
                    conflicts++
                }
            }
            val result = shared.solve(
                model.numBoolVars,
                SearchSolveParams(maxDecisions = 2048),
                booleanBranching = BooleanBranching.SourceOrder(
                    if (mode == "definitions-branching") {
                        model.numBoolVars
                    } else {
                        source.numBoolVars
                    },
                ),
                observer = observer,
            )
            val runNs = System.nanoTime() - started
            val runBytes = bean.getThreadAllocatedBytes(thread) - allocated
            val checkStarted = System.nanoTime()
            val checkStop = Cancellation.after(2.seconds)
            val checked = if (result is SearchResult.Satisfied) {
                val sample = Sample(
                    BooleanArray(source.numBoolVars) { native.boolValue(it) ?: false },
                    LongArray(source.numIntVars) { native.intDomain(it).min },
                )
                FiniteConflictSelection.checkSource(original.bake(checkStop), reconstruct(sample), checkStop)
            } else {
                null
            }
            val clauseGetter = native.javaClass.declaredMethods.single {
                it.name.startsWith("learnedClauseLiterals") && it.parameterCount == 1
            }
            val helperClauses = (0 until native.learnedClauseCount).filter { index ->
                (clauseGetter.invoke(native, index) as IntArray).any {
                    Lit.variable(it) in source.numBoolVars until model.numBoolVars
                }
            }
            val record = buildJsonObject {
                put("preparedIdentity", identity)
                put("mode", mode)
                put("result", result.javaClass.simpleName)
                put("stopped", stop())
                put("decisions", decisions)
                put("conflicts", conflicts)
                put("helperDecisions", helperDecisions)
                put("nativePropagations", native.propagationCount)
                put("nativeWork", work(native))
                put("nativeStored", native.learnedClauseCount)
                put("sharedStored", shared.learnedClauseCount)
                put("helperBooleanClauses", helperClauses.size)
                put("helperBooleanClausesMarkedUsed", helperClauses.count(native::learnedClauseUsedSinceReduction))
                put(
                    "helperClauseSamples",
                    JsonArray(
                        helperClauses.take(16).map { index ->
                            JsonArray((clauseGetter.invoke(native, index) as IntArray).map(::JsonPrimitive))
                        },
                    ),
                )
                put("definitionNs", if (mode.startsWith("definitions")) definitionNs else 0)
                put("definitionThreadBytes", if (mode.startsWith("definitions")) definitionBytes else 0)
                put("runNs", runNs)
                put("runThreadBytes", runBytes)
                put("nativeOriginalWitnessAccepted", checked)
                put("nativeOriginalCheckNs", System.nanoTime() - checkStarted)
                put("nativeOriginalCheckStopped", checkStop())
                put("helperIntegers", model.numIntVars - source.numIntVars)
                put("helperBooleans", model.numBoolVars - source.numBoolVars)
                put("retainedFactors", model.factors.size)
                put("definitionIdentity", sha256(definitions.states.joinToString(",").toByteArray()))
                put("independentSourceCheck", false)
                put("sourceVerdictClaim", false)
                put("satisfactionDiagnosticOnly", true)
            }
            records.appendText("$record\n")
            println(record)
        }
        if (source.numIntVars <= 8 && source.factors.filterIsInstance<Regular>().first().accepting.size == 3) {
            reuse(source, definitions, out)
        }
    }

    private fun work(session: PropagationSession): Long {
        val getter = session.javaClass.declaredMethods.single {
            it.name.startsWith(
                "getWork",
            ) && it.parameterCount == 0
        }
        return getter.invoke(session) as Long
    }

    private fun reuse(source: Problem, definitions: RegularPrefixDefinitions, out: File) {
        val boundary = source.numIntVars - 2
        val native = PropagationSession(definitions.problem)
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(definitions.problem.numBoolVars))
        check(shared.initialize() is ComponentResult.Consistent)
        check(shared.push(SearchDecision.Bool(Lit.make(definitions.booleans[0], true))) is ComponentResult.Conflict)
        val learned = (cp.resolveConflict(shared) as SearchConflictResolution.Backjump).conflict
        shared.popTo(learned.decisionLevel)
        check(learned.apply(shared) == SearchLearnedConflictResult.Resume)
        check(native.boolValue(definitions.booleans[0]) == false)
        val clauses = native.exportGlueClauses(2, 128)
        check(clauses.isNotEmpty())
        val sourceProof = PropagationSession(source)
        val pins = (0 until boundary).fold(Assumptions.None) { assumptions, v -> assumptions.withInt(v, 1) }
        check(sourceProof.seed(pins) is PropagationResult.Unsat)
        val records = out.resolve("reuse.jsonl").apply { writeText("") }
        for (mode in listOf("native-source-clause", "definitions-source-clause", "definitions-learned-state")) {
            for (symbol in listOf(1L, 2L)) {
                val session = PropagationSession(if (mode == "native-source-clause") source else definitions.problem)
                if (mode == "definitions-learned-state") {
                    clauses.forEach { session.importClause(it) }
                    check(session.implyIntAtMost(0, session.intDomain(0).max) is PropagationResult.Implied)
                    check(session.boolValue(definitions.booleans[0]) == false)
                } else {
                    check(
                        session.addLearnedClause(
                            Clause(IntArray(boundary) { session.boundGeLit(it, 2, true) }),
                            boundary,
                        ) is PropagationResult.Implied,
                    )
                }
                var completed = 0
                var beforePin = false
                for (v in 0 until boundary) {
                    if (symbol !in session.intDomain(v)) {
                        beforePin = true
                        break
                    }
                    if (session.pinInt(v, symbol) is PropagationResult.Unsat) break
                    completed++
                }
                check(completed < boundary)
                val record = buildJsonObject {
                    put("mode", mode)
                    put("prefix", List(boundary) { symbol }.joinToString(","))
                    put("actualPrefixState", 1)
                    put("successfulPinsBeforeRejection", completed)
                    put("rejectedByDomainBeforePin", beforePin)
                    put("nativeWork", work(session))
                    put("acquisitionWork", work(native) + work(sourceProof))
                    put("learnedStateFalseAtRoot", native.boolValue(definitions.booleans[0]) == false)
                    put("independentSourceCheck", false)
                }
                records.appendText("$record\n")
                println(record)
            }
        }
    }

    private class SourceReasons(
        private val cp: CpSearchComponent,
        private val integers: Int,
        private val booleans: Int,
    ) : SearchBrancher {
        private val choices = LinkedHashSet<SearchDecision>()
        private val active = ArrayList<Pair<Int, SearchDecision>>()

        private fun explanation(context: SearchContext): SearchExplanation? = context.explainAtoms(
            SearchAtomPremise.All(active.map { SearchAtomPremise.Asserted(it.second) }),
        )

        private fun result(result: ComponentResult, context: SearchContext): ComponentResult =
            if (result is ComponentResult.Conflict) ComponentResult.Conflict(explanation(context)) else result

        override fun initialize(context: SearchContext): ComponentResult = result(cp.initialize(context), context)
        override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
            val literal = context.atomLiteral(decision)
            val original = if (decision is SearchDecision.Bool && Lit.variable(decision.literal) < booleans) {
                decision
            } else {
                choices.firstOrNull { context.atomLiteral(it) == literal && literal != null }
            }
            if (original != null && (context as SearchSession).reasonFor(requireNotNull(literal) ushr 1) == null) {
                active.add(context.decisionLevel to original)
            }
            return result(cp.assert(decision, context), context)
        }
        override fun propagate(context: SearchContext): ComponentResult = result(cp.propagate(context), context)
        override fun retract(decisionLevel: Int) {
            active.removeAll { it.first > decisionLevel }
            cp.retract(decisionLevel)
        }
        override fun check(context: SearchContext): ComponentCheck = when (val checked = cp.check(context)) {
            is ComponentCheck.Infeasible -> ComponentCheck.Infeasible(explanation(context))
            else -> checked
        }
        override fun contributeModel(model: SearchModel, context: SearchContext) = cp.contributeModel(model, context)
        override fun nextBranch(context: SearchContext): List<SearchDecision>? =
            originalAlternatives(cp.session, integers) { it }?.also { choices.addAll(it) }
    }
}

private fun originalAlternatives(
    session: PropagationSession,
    count: Int,
    sourceId: (Int) -> Int,
): List<SearchDecision>? {
    for (v in 0 until count) {
        val domain = session.intDomain(v)
        if (domain.min == domain.max) continue
        val middle = domain.min + ((domain.max - domain.min) ushr 1)
        return listOf(SearchDecision.IntAtMost(sourceId(v), middle), SearchDecision.IntAtLeast(sourceId(v), middle + 1))
    }
    return null
}

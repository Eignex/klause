package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MixedPbLocalityProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val records = out.resolve("locality.jsonl")
        records.writeText("")
        for (triggerConflict in listOf(false, true)) {
            for (mode in 0..2) {
                for (pb in listOf(false, true)) {
                    val started = System.nanoTime()
                    val before = bean.getThreadAllocatedBytes(thread)
                    val booleans = 5
                    val factors = buildList<Factor> {
                        add(Cardinality(intArrayOf(Lit.make(0, false), Lit.make(2, true),
                            Lit.make(3, true), Lit.make(4, true)), min = 2, max = 4))
                        add(Clause(intArrayOf(Lit.make(1, true), Lit.make(2, false))))
                        add(Clause(intArrayOf(Lit.make(1, true), Lit.make(3, false))))
                        if (mode == 2) add(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5))
                    }
                    val problem = Problem(booleans, if (mode == 0) 0 else 1,
                        if (mode == 0) emptyArray() else arrayOf(IntDomain(0, 10)), factors.toTypedArray())
                    val native = PropagationSession(problem, pbLearning = pb)
                    val cp = CpSearchComponent(native)
                    val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(booleans))
                    val clauseKind = native.javaClass.declaredMethods.single {
                        it.name.startsWith("isLearnedClause") && it.parameterCount == 1
                    }
                    val state = LivePropagationState.require(native)
                    val reasons = state.javaClass.getMethod("getBoolReason").invoke(state) as IntArray
                    var consumedPins = 0
                    var decisions = 0
                    var backjumps = 0
                    val observer = object : SearchRunObserver {
                        override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                            decisions++
                            consumedPins += reasons.count { fid ->
                                val index = fid - problem.factors.size
                                index in 0 until native.learnedClauseCount &&
                                    !(clauseKind.invoke(native, index) as Boolean)
                            }
                        }
                        override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                            backjumps++
                        }
                    }
                    check(shared.initialize() is ComponentResult.Consistent)
                    val brancher = BooleanBranching { context ->
                        (0 until booleans).firstOrNull { context.boolValue(it) == null }?.let { variable ->
                            val first = variable == 0 && triggerConflict
                            listOf(SearchDecision.Bool(Lit.make(variable, first)),
                                SearchDecision.Bool(Lit.make(variable, !first)))
                        }
                    }
                    val result = shared.solve(booleans, booleanBranching = brancher, observer = observer)
                    val storedPb = (0 until native.learnedClauseCount).count {
                        !(clauseKind.invoke(native, it) as Boolean)
                    }
                    check(result is SearchResult.Satisfied)
                    if (pb && triggerConflict) check(storedPb > 0 && consumedPins > 0)
                    run {
                        val checker = PropagationSession(problem.withIntDomains(Array(problem.numIntVars) {
                            val value = native.intDomain(it).min
                            IntDomain(value, value)
                        }))
                        check(!checker.isUnsatAtRoot)
                        for (variable in 0 until booleans) {
                            val checked = checker.pinBool(variable, checkNotNull(native.boolValue(variable)))
                            check(checked is PropagationResult.Implied)
                        }
                    }
                    val record = buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("projection", listOf("boolean", "retained-integer", "connected-integer")[mode])
                        put("source", "!b0+b2+b3+b4>=2; b2<=b1; b3<=b1")
                        put("triggerConflict", triggerConflict)
                        put("integerDomain", if (mode == 0) "absent" else "0..10")
                        put("connectedRelation", if (mode == 2) "b0 iff x0<=5" else "absent")
                        put("preparation", "direct finite projection; no presolve, binary substitution or LP")
                        put("pbLearning", pb)
                        put("feasible", true)
                        put("verdict", result::class.simpleName)
                        put("nativeSourceCheck", true)
                        put("independentWitnessOrProofCheck", false)
                        put("storedPb", storedPb)
                        put("observedPbReasonPins", consumedPins)
                        put("decisions", decisions)
                        put("backjumps", backjumps)
                        put("storedConstraints", native.learnedClauseCount)
                        put("work", state.javaClass.declaredMethods.single {
                            it.name.startsWith("getWork") && it.parameterCount == 0
                        }.invoke(state) as Long)
                        put("elapsedNs", System.nanoTime() - started)
                        put("threadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                        put("osName", System.getProperty("os.name"))
                        put("osArch", System.getProperty("os.arch"))
                        put("javaVersion", System.getProperty("java.version"))
                    }
                    records.appendText("$record\n")
                    println(record)
                }
            }
        }
    }
}

package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.simplex.exact.BigFraction
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ExactLinearScoringProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        out.resolve("scoring.jsonl").bufferedWriter().use { output ->
            for (width in listOf(8, 64, 512, 4096)) {
                val row = ExactLinear.of(IntArray(width) {
                    if (it % 2 == 0) Term.ofIntVar(it / 2) else Term.ofRealVar(it / 2)
                }, List(width) { BigFraction.ofLong(if (it % 3 == 0) -16L else 8L) },
                    LinearOp.EQ, BigFraction.ZERO)
                val model = LocalSearchModel.open(Problem(0, width / 2,
                    Array(width / 2) { IntDomain(-16, 16) }, arrayOf(row), numRealVars = width / 2,
                    realLower = DoubleArray(width / 2) { -16.0 }, realUpper = DoubleArray(width / 2) { 16.0 }))
                val state = LocalSearchState(model, Random(3))
                val rounds = 256
                val count = 16
                val candidates = Array(rounds) { round ->
                    Array<Move>(count) { choice ->
                        val term = (round * 37 + choice * 17) % width
                        val value = ((round * 11 + choice * 7) % 33 - 16).toLong()
                        if (term % 2 == 0) Move.IntSet(term / 2, value) else Move.RealSet(term / 2, value.toDouble())
                    }
                }
                var expectedScores: Long? = null
                var expectedChoices: Long? = null
                repeat(8) { batch ->
                    for (v in 0 until width / 2) {
                        state.assignment.setInt(v, 0)
                        state.assignment.setReal(v, 0.0)
                    }
                    state.recompute()
                    var scoreHash = 1L
                    var choiceHash = 1L
                    var scoringNs = 0L
                    var scoringBytes = 0L
                    var applyNs = 0L
                    for (moves in candidates) {
                        var selected = 0
                        var best = Long.MAX_VALUE
                        val allocated = bean.getThreadAllocatedBytes(thread)
                        val started = System.nanoTime()
                        for (choice in moves.indices) {
                            val score = state.netDelta(moves[choice])
                            scoreHash = 31 * scoreHash + score
                            if (score < best) {
                                best = score
                                selected = choice
                            }
                        }
                        scoringNs += System.nanoTime() - started
                        scoringBytes += bean.getThreadAllocatedBytes(thread) - allocated
                        choiceHash = 31 * choiceHash + selected
                        val before = state.cost
                        val applyStart = System.nanoTime()
                        state.apply(moves[selected])
                        applyNs += System.nanoTime() - applyStart
                        check(state.cost - before == best)
                    }
                    val before = state.cost
                    state.recompute()
                    check(state.cost == before)
                    check(expectedScores == null || expectedScores == scoreHash)
                    check(expectedChoices == null || expectedChoices == choiceHash)
                    expectedScores = scoreHash
                    expectedChoices = choiceHash
                    val record = buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("invariant", state.factors[0].javaClass.simpleName)
                        put("width", width)
                        put("batch", batch - 3)
                        put("warmup", batch < 3)
                        put("candidates", rounds * count)
                        put("commits", rounds)
                        put("scoreHash", scoreHash)
                        put("choiceHash", choiceHash)
                        put("finalDegree", state.cost)
                        put("scoringNs", scoringNs)
                        put("scoringThreadBytes", scoringBytes)
                        put("applyNs", applyNs)
                        put("osName", System.getProperty("os.name"))
                        put("osArch", System.getProperty("os.arch"))
                        put("javaVersion", System.getProperty("java.version"))
                        put("sourceVerdictClaim", false)
                    }
                    output.appendLine(record.toString())
                    output.flush()
                    println(record)
                }
            }
        }
    }
}

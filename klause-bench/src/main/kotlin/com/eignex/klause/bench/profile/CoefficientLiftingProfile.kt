package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CoefficientLiftingProfile {
    @Volatile
    private var retained: PassDelta? = null

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val identity = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("scope", "coefficient-strengthening-java-thread-allocations")
            put("javaVersion", System.getProperty("java.version"))
            put("jvmOptions", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
        }
        out.resolve("identity.json").writeText("$identity\n")
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        out.resolve("allocations.jsonl").bufferedWriter().use { output ->
            for (width in listOf(64, 512, 4096)) {
                for (op in listOf(LinearOp.LE, LinearOp.GE)) {
                    for (productive in listOf(false, true)) {
                        val problem = model(width, op, productive)
                        repeat(512) { retain(problem, productive) }
                        repeat(5) { batch ->
                            val calls = 256
                            val before = bean.getThreadAllocatedBytes(thread)
                            repeat(calls) { retain(problem, productive) }
                            val allocated = bean.getThreadAllocatedBytes(thread) - before
                            val record = buildJsonObject {
                                put("width", width)
                                put("inputOp", op.name)
                                put("productive", productive)
                                put("batch", batch)
                                put("calls", calls)
                                put("threadAllocatedBytes", allocated)
                                put("bytesPerCall", allocated.toDouble() / calls)
                            }
                            output.appendLine(record.toString())
                            output.flush()
                            println(record)
                        }
                    }
                }
            }
        }
    }

    private fun model(width: Int, op: LinearOp, productive: Boolean): BakedProblem {
        val sign = if (op == LinearOp.LE) 1L else -1L
        val row = Linear(
            LongArray(width) { sign * if (productive && it == 0) 2L * width else 1L },
            IntArray(width) { it },
            op,
            sign * if (productive) 2L * width else width.toLong() - 1L,
        )
        val problem = Problem(0, width, Array(width) { IntDomain(0, 1) }, listOf(row)).bake()
        check(problem.factors.single() === row)
        for (v in 0 until width) {
            val domain = problem.rootIntDomain(v)
            check(domain.min == 0L && domain.max == 1L)
        }
        return problem
    }

    private fun retain(problem: BakedProblem, productive: Boolean) {
        val delta = Presolve.strengthenCoefficients(problem)
        check(!delta.infeasible)
        if (productive) check(delta.droppedIndices.size == 1 && delta.addedFactors.size == 1)
        else check(delta.isEmpty)
        retained = delta
    }
}

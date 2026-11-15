package com.eignex.klause.bench.profile

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.polynomial.RationalPolynomialArithmetic
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticCertification
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticInterrupted
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.quadratic.QuadraticRow
import com.eignex.klause.arithmetic.quadratic.QuadraticRowRelation
import com.eignex.klause.arithmetic.transcendental.RationalEnclosure
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.QuadraticNativeScope
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.parseBigInt
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.nanoseconds

internal object ArithmeticCancellationProfile {
    private val cpu = ManagementFactory.getThreadMXBean()
    private val workloads = listOf("float1", "float16", "float64", "float256", "exact8", "exact16",
        "polynomial8", "polynomial24", "rational16", "rational64", "exp4", "exp12",
        "large-exact", "large-quadratic")
    @Volatile private var sink = 0

    @JvmStatic
    fun main(args: Array<String>) {
        cpu.isThreadCpuTimeEnabled = true
        when (args.singleOrNull() ?: "throughput") {
            "throughput" -> {
                for (name in workloads) for (mode in listOf("none", "deadline", "owned", "nested", "opaque")) {
                    measure(name, mode)
                }
                for (name in listOf("float64", "float256", "polynomial24", "exp12")) measureParallel(name)
            }
            "latency" -> latency()
            else -> error("expected throughput or latency")
        }
        println("sink\t$sink")
    }

    private class Accounting(mode: String, cancellation: ExecutionControl = ExecutionControl.Never) {
        private var charged = 0L
        private val parent = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = Long.MAX_VALUE), false)
        private val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = Long.MAX_VALUE,
            cancellation = if (mode == "nested") parent.polynomial or cancellation else cancellation), false)
        val token: ExecutionControl = when (mode) {
            "none" -> ExecutionControl.Never
            "deadline" -> ExecutionControl.after(60_000_000_000.nanoseconds)
            "owned", "nested" -> scope.worker(0).polynomial
            "opaque" -> object : ExecutionControl() {
                override fun isCancelled(): Boolean = cancellation()
                override val accounting: WorkMeter get() = WorkMeter { charged += it }
            }
            else -> error("unknown mode")
        }
        val work: Long get() = if (modeIsOpaque) charged else scope.work
        private val modeIsOpaque = mode == "opaque"
        fun verify() {
            if (parent.work != 0L) check(parent.work == scope.work)
        }
    }

    private fun kernel(name: String, token: ExecutionControl): () -> Int = when {
        name.startsWith("large-") -> latencyKernel(name, token)
        name.startsWith("float") -> {
            val n = name.removePrefix("float").toInt()
            val model = QuadraticModel(List(n) { q((it % 7 - 3).toLong()) }, List(n) { i ->
                List(n) { j -> q(if (i == j) 2 else 0) }
            }, rows = List(maxOf(1, n / 4)) { row ->
                QuadraticRow(List(n) { q(if (it == row) 1 else 0) }, q(1), QuadraticRowRelation.LE)
            })
            val candidates = QuadraticCandidates(QuadraticControl(token))
            val start = List(n) { (it % 5 - 2).toDouble() }
            ({ checkNotNull(candidates.proposeActiveRows(model, start)).hashCode() })
        }
        name.startsWith("exact") -> {
            val n = name.removePrefix("exact").toInt()
            val model = QuadraticModel(List(n) { q(1) }, List(n) { i ->
                List(n) { j -> q(if (i == j) 2 else 0) }
            })
            val checker = QuadraticCertification(QuadraticControl(token))
            val point = List(n) { q(it.toLong() + 1) * q(13).reciprocal() }
            ({ checker.evaluate(model, point).hashCode() })
        }
        name.startsWith("polynomial") -> {
            val n = name.removePrefix("polynomial").toInt()
            val left = Polynomial.of(List(n) { PolynomialTerm(Monomial.of(mapOf(0 to it)), q(it.toLong() + 1)) })
            val right = Polynomial.of(List(n) { PolynomialTerm(Monomial.of(mapOf(1 to it)), q(it.toLong() + 2)) })
            val arithmetic = PolynomialArithmetic(token)
            ({ arithmetic.multiply(left, right).hashCode() })
        }
        name.startsWith("rational") -> {
            val n = name.removePrefix("rational").toInt()
            val value = RationalPolynomial(List(n) { q(it.toLong() + 1) * q(13).reciprocal() })
            val arithmetic = RationalPolynomialArithmetic(PolynomialArithmetic(token))
            ({ arithmetic.evaluate(value, q(19) * q(17).reciprocal()).hashCode() })
        }
        name.startsWith("exp") -> {
            val terms = name.removePrefix("exp").toInt()
            val arithmetic = TranscendentalArithmetic(token)
            val input = RationalEnclosure.point(q(if (terms > 4) 8 else 1))
            ({ arithmetic.exp(input, terms).hashCode() })
        }
        else -> error("unknown workload")
    }

    private fun repetitions(name: String): Int = when {
        name.startsWith("large-") -> 1
        name == "float256" -> 2
        name == "float64" -> 16
        name == "float1" -> 2048
        name.startsWith("float") -> 128
        name.startsWith("exact") -> 512
        name == "polynomial8" -> 256
        name.startsWith("polynomial") -> 64
        name == "rational16" -> 2048
        name.startsWith("rational") -> 512
        name == "exp4" -> 1024
        else -> 64
    }

    private fun measure(name: String, mode: String) {
        val setup = System.nanoTime()
        val accounting = Accounting(mode)
        val run = kernel(name, accounting.token)
        val expected = kernel(name, ExecutionControl.Never).invoke()
        val setupNanos = System.nanoTime() - setup
        val count = repetitions(name)
        repeat(4) { repeat(count) { check(run() == expected) } }
        val warmUntil = cpu.currentThreadCpuTime + 500_000_000L
        do { repeat(count) { check(run() == expected) } } while (cpu.currentThreadCpuTime < warmUntil)
        repeat(5) { sample ->
            val beforeWork = accounting.work
            val beforeCpu = cpu.currentThreadCpuTime
            val before = System.nanoTime()
            repeat(count) { check(run() == expected); sink = sink xor expected }
            val wall = System.nanoTime() - before
            val consumedCpu = cpu.currentThreadCpuTime - beforeCpu
            accounting.verify()
            println("sample\t$name\t$mode\t$sample\t$count\t$wall\t$consumedCpu\t" +
                "${accounting.work - beforeWork}\t$expected\t$setupNanos")
        }
    }

    private fun measureParallel(name: String) {
        val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = Long.MAX_VALUE))
        val runs = List(2) { kernel(name, scope.worker(it).polynomial) }
        val expected = kernel(name, ExecutionControl.Never).invoke()
        val count = repetitions(name)
        repeat(4) { runs.forEach { run -> repeat(count) { check(run() == expected) } } }
        repeat(5) { sample ->
            val start = CountDownLatch(1)
            val consumedCpu = AtomicLong()
            val beforeWork = scope.work
            val threads = runs.map { run -> thread {
                start.await()
                val beforeCpu = cpu.currentThreadCpuTime
                repeat(count) { check(run() == expected) }
                consumedCpu.addAndGet(cpu.currentThreadCpuTime - beforeCpu)
            } }
            val before = System.nanoTime()
            start.countDown()
            threads.forEach { it.join() }
            val wall = System.nanoTime() - before
            println("sample\t$name\tparallel\t$sample\t${2 * count}\t$wall\t${consumedCpu.get()}\t" +
                "${scope.work - beforeWork}\t$expected\t0")
        }
    }

    private fun latency() {
        for (family in listOf("float256", "polynomial24", "exp12", "large-exact", "large-quadratic", "linear64",
            "finite-search", "finite-local-search")) {
            val warm = latencyKernel(family, ExecutionControl.Never)
            repeat(16) { sink = warm() }
            for (kind in listOf("flag", "deadline")) repeat(120) { attempt ->
                val trial = attempt - 20
                val flag = AtomicBoolean()
                val ready = CountDownLatch(1)
                val start = CountDownLatch(1)
                val request = AtomicLong()
                val requestCpu = AtomicLong()
                val observed = AtomicLong()
                val observedCpu = AtomicLong()
                val offset = 250_000L * (1 + attempt % 8)
                val liveDeadline = AtomicReference(ExecutionControl.Never)
                val token = ExecutionControl { flag.get() || liveDeadline.get()() }
                val accounting = Accounting("owned", token)
                val run = latencyKernel(family, accounting.token)
                val worker = thread {
                    ready.countDown()
                    start.await()
                    try {
                        while (true) sink = run()
                    } catch (_: QuadraticInterrupted) {
                        observed.set(System.nanoTime())
                    } catch (_: PolynomialInterrupted) {
                        observed.set(System.nanoTime())
                    } catch (_: TranscendentalInterrupted) {
                        observed.set(System.nanoTime())
                    } catch (_: ProfileInterrupted) {
                        observed.set(System.nanoTime())
                    } catch (_: SearchInitializationCancelled) {
                        check(token())
                        observed.set(System.nanoTime())
                    } finally {
                        observedCpu.set(cpu.currentThreadCpuTime)
                    }
                }
                ready.await()
                if (kind == "deadline") {
                    request.set(System.nanoTime() + offset)
                    liveDeadline.set(ExecutionControl.after(offset.nanoseconds))
                }
                start.countDown()
                if (kind == "flag") {
                    LockSupport.parkNanos(offset)
                    requestCpu.set(cpu.getThreadCpuTime(worker.threadId()))
                    request.set(System.nanoTime())
                    flag.set(true)
                }
                worker.join(10_000)
                check(!worker.isAlive && observed.get() != 0L)
                val wall = (observed.get() - request.get()).coerceAtLeast(0)
                val consumedCpu = if (kind == "flag") {
                    (observedCpu.get() - requestCpu.get()).coerceAtLeast(0)
                } else -1
                if (trial >= 0) println("latency\t$family\t$kind\t$trial\t$wall\t$consumedCpu\t${accounting.work}")
            }
        }
    }

    private fun latencyKernel(family: String, token: ExecutionControl): () -> Int = when (family) {
        "finite-search" -> finite(token, localSearch = false)
        "finite-local-search" -> finite(token, localSearch = true)
        "linear64" -> linear(token)
        "large-exact" -> largeExact(token)
        "large-quadratic" -> {
            val numerator = parseBigInt("9".repeat(2048))
            val denominator = parseBigInt("1" + "0".repeat(2048) + "7")
            val value = BigFraction.of(numerator, denominator)
            val model = QuadraticModel(List(2) { value }, List(2) { List(2) { value } })
            val point = List(2) { value }
            val checker = QuadraticCertification(QuadraticControl(token))
            ({ checker.evaluate(model, point).hashCode() })
        }
        else -> kernel(family, token)
    }

    private class ProfileInterrupted : RuntimeException()

    private fun finite(token: ExecutionControl, localSearch: Boolean): () -> Int {
        val pigeons = 8
        val holes = 7
        val clauses = ArrayList<Factor>()
        repeat(pigeons) { pigeon -> clauses += Clause(IntArray(holes) { Lit.make(pigeon * holes + it, true) }) }
        repeat(holes) { hole ->
            repeat(pigeons) { first ->
                for (second in first + 1 until pigeons) {
                    clauses += Clause(intArrayOf(Lit.make(first * holes + hole, false),
                        Lit.make(second * holes + hole, false)))
                }
            }
        }
        val model = Problem(pigeons * holes, 0, emptyArray(), clauses.toTypedArray()).bake()
        return if (localSearch) {
            val solver = LocalSearchSolver(model, greedyRepairOnRestart = false)
            ({
                solver.resumableSolve(LocalSearchParams(randomSeed = 1L)).use { handle ->
                    handle.runSlice(token, Long.MAX_VALUE, 64L)
                }
                if (token()) throw ProfileInterrupted()
                0
            })
        } else {
            val solver = BacktrackSolver(model)
            val objective = LinearObjective(boolWeights = LongArray(pigeons * holes))
            ({
                solver.resumable(objective, BacktrackParams(randomSeed = 1L)).use { handle ->
                    handle.runSlice(token, Long.MAX_VALUE, 64L) { }
                }
                if (token()) throw ProfileInterrupted()
                0
            })
        }
    }

    private fun linear(token: ExecutionControl): () -> Int {
        val dimension = 64
        val references = IntArray(dimension) { Term.ofRealVar(it) }
        val rows = Array<Factor>(96) { row ->
            ExactLinear.of(references, List(dimension) { column -> q(((row + column * 3) % 7 - 3).toLong()) },
                LinearOp.LE, q(64))
        }
        val model = Problem(0, 0, emptyArray(), rows, numRealVars = dimension,
            realLower = DoubleArray(dimension) { -10.0 }, realUpper = DoubleArray(dimension) { 10.0 })
        return {
            val result = ExactLiraSearchComponent(model).use { component ->
                val session = SearchSession(listOf(component), cancellation = token)
                session.initialize()
                if (token()) throw ProfileInterrupted()
                session.solve(0)
            }
            if (token()) throw ProfileInterrupted()
            result.hashCode()
        }
    }

    private fun largeExact(token: ExecutionControl): () -> Int {
        val numerator = parseBigInt("9".repeat(2048))
        val denominator = parseBigInt("1" + "0".repeat(2048) + "7")
        val value = BigFraction.of(numerator, denominator)
        val polynomial = Polynomial.of(List(8) { PolynomialTerm(Monomial.of(mapOf(0 to it)), value) })
        val arithmetic = PolynomialArithmetic(token)
        return { arithmetic.multiply(polynomial, polynomial).hashCode() }
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}

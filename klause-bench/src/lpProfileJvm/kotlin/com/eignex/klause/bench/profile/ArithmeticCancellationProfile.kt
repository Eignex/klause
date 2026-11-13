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
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.QuadraticNativeScope
import com.eignex.klause.util.Cancellation
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
        "polynomial8", "polynomial24", "rational16", "rational64", "exp4", "exp12")
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

    private class Accounting(mode: String, cancellation: Cancellation = Cancellation.Never) {
        private var charged = 0L
        private val parent = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = Long.MAX_VALUE), false)
        private val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = Long.MAX_VALUE,
            cancellation = if (mode == "nested") parent.polynomial or cancellation else cancellation), false)
        val token: Cancellation = when (mode) {
            "none" -> Cancellation.Never
            "deadline" -> Cancellation.after(60_000_000_000.nanoseconds)
            "owned", "nested" -> scope.worker(0).polynomial
            "opaque" -> object : Cancellation {
                override fun isCancelled(): Boolean = cancellation()
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            else -> error("unknown mode")
        }
        val work: Long get() = if (modeIsOpaque) charged else scope.work
        private val modeIsOpaque = mode == "opaque"
        fun verify() {
            if (parent.work != 0L) check(parent.work == scope.work)
        }
    }

    private fun kernel(name: String, token: Cancellation): () -> Int = when {
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
        name == "float256" -> 2
        name == "float64" -> 16
        name == "float1" -> 2048
        name.startsWith("float") -> 128
        name.startsWith("exact") -> 64
        name.startsWith("polynomial") -> 32
        name.startsWith("rational") -> 128
        else -> 4
    }

    private fun measure(name: String, mode: String) {
        val setup = System.nanoTime()
        val accounting = Accounting(mode)
        val run = kernel(name, accounting.token)
        val expected = kernel(name, Cancellation.Never).invoke()
        val setupNanos = System.nanoTime() - setup
        val count = repetitions(name)
        repeat(4) { repeat(count) { check(run() == expected) } }
        val warmUntil = cpu.currentThreadCpuTime + 100_000_000L
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
        val expected = kernel(name, Cancellation.Never).invoke()
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
        for (family in listOf("float256", "polynomial24", "exp12", "large-exact")) {
            val warm = if (family == "large-exact") largeExact(Cancellation.Never)
                else kernel(family, Cancellation.Never)
            repeat(16) { sink = warm() }
            for (kind in listOf("flag", "deadline")) repeat(100) { trial ->
                val flag = AtomicBoolean()
                val ready = CountDownLatch(1)
                val start = CountDownLatch(1)
                val request = AtomicLong()
                val requestCpu = AtomicLong()
                val observed = AtomicLong()
                val observedCpu = AtomicLong()
                val offset = 250_000L * (1 + trial % 8)
                val liveDeadline = AtomicReference(Cancellation.Never)
                val token = Cancellation { flag.get() || liveDeadline.get()() }
                val accounting = Accounting("owned", token)
                val run = if (family == "large-exact") largeExact(accounting.token)
                    else kernel(family, accounting.token)
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
                    } finally {
                        observedCpu.set(cpu.currentThreadCpuTime)
                    }
                }
                ready.await()
                if (kind == "deadline") {
                    request.set(System.nanoTime() + offset)
                    liveDeadline.set(Cancellation.after(offset.nanoseconds))
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
                println("latency\t$family\t$kind\t$trial\t$wall\t$consumedCpu\t${accounting.work}")
            }
        }
    }

    private fun largeExact(token: Cancellation): () -> Int {
        val numerator = parseBigInt("9".repeat(2048))
        val denominator = parseBigInt("1" + "0".repeat(2048) + "7")
        val value = BigFraction.of(numerator, denominator)
        val polynomial = Polynomial.of(List(8) { PolynomialTerm(Monomial.of(mapOf(0 to it)), value) })
        val arithmetic = PolynomialArithmetic(token)
        return { arithmetic.multiply(polynomial, polynomial).hashCode() }
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}

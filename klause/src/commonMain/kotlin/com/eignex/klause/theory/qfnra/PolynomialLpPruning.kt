package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.EnvelopeInterval
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal sealed interface PolynomialLpStep {
    data object Pending : PolynomialLpStep
    data object Unavailable : PolynomialLpStep
    data object Refuted : PolynomialLpStep
    class Witness(val assignment: QfNraAssignment) : PolynomialLpStep
}

// Closed bisection children cover their parent's source-proved box. Their envelopes stay node-local.
internal class PolynomialLpPruning(
    private val atoms: List<PolynomialAtom>,
    private val variables: Int,
    private val arithmetic: PolynomialArithmetic,
    private val work: QfNraWork,
) : AutoCloseable {
    private val pending = ArrayDeque<Map<Int, EnvelopeInterval>>().apply { addLast(emptyMap()) }
    private var windows: Map<Int, EnvelopeInterval> = emptyMap()
    private var current: PolynomialLpRelaxation? = null
    private var closed = false
    private var tightened = false

    fun advance(witnessCheck: (List<BigFraction>) -> QfNraAssignment?): PolynomialLpStep = try {
        advanceAdmitted(witnessCheck)
    } catch (_: PolynomialResourceLimit) {
        unavailable()
    }

    private fun advanceAdmitted(witnessCheck: (List<BigFraction>) -> QfNraAssignment?): PolynomialLpStep {
        check(!closed)
        val relaxation = current ?: run {
            if (pending.isEmpty()) return PolynomialLpStep.Refuted
            windows = pending.removeLast()
            PolynomialLpRelaxation.prepare(atoms, variables, arithmetic, work, windows)
                ?.also { current = it } ?: return unavailable()
        }
        val solved = relaxation.solve()
        work.envelopeQueries++
        arithmetic.step()
        if (solved?.verdict == LpVerdict.INFEASIBLE) {
            work.envelopeRefutations++
            retire()
            return if (pending.isEmpty()) PolynomialLpStep.Refuted else PolynomialLpStep.Pending
        }
        val point = solved?.exactPrimal ?: return unavailable()
        witnessCheck(point)?.let { original ->
            val centered = relaxation.center(point) { work.envelopeQueries++ }
            val witness = witnessCheck(centered) ?: original
            close()
            return PolynomialLpStep.Witness(witness)
        }
        if (relaxation.refine(point)) {
            work.envelopeRefinements++
            return PolynomialLpStep.Pending
        }
        if (!tightened) {
            tightened = true
            val tightening = relaxation.tightenSquares { work.envelopeQueries++ }
            if (tightening.infeasible) {
                work.envelopeRefutations++
                retire()
                return if (pending.isEmpty()) PolynomialLpStep.Refuted else PolynomialLpStep.Pending
            }
            if (tightening.changed) return PolynomialLpStep.Pending
        }
        val (variable, middle) = relaxation.split(point) ?: return unavailable()
        arithmetic.step()
        if (middle.num.magnitudeBitLength() > 4096 || middle.den.magnitudeBitLength() > 4096) return unavailable()
        val interval = windows[variable] ?: EnvelopeInterval()
        arithmetic.matrixCells(1, pending.sumOf { it.size.toLong() } + 2L * windows.size + 2)
        pending.addLast(windows + (variable to interval.copy(lower = middle)))
        pending.addLast(windows + (variable to interval.copy(upper = middle)))
        retire()
        return PolynomialLpStep.Pending
    }

    private fun unavailable(): PolynomialLpStep {
        close()
        return PolynomialLpStep.Unavailable
    }

    private fun retire() {
        current?.close()
        current = null
        tightened = false
    }

    override fun close() {
        retire()
        pending.clear()
        closed = true
    }
}

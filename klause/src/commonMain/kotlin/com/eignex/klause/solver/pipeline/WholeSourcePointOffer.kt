package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.wholeRealPointTruths
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException

internal class WholeSourcePointOffer(
    private val source: Problem,
    private val original: Problem,
    private val mapping: SourceMapping,
    private val publicationCancellation: Cancellation,
) {
    private val witnesses = SourceWitnessAssembler(source, original)
    private var booleanPreparationAttempted = false
    private var booleanModel: BakedProblem? = null
    private var used: BooleanArray? = null
    private var verification: PropagationProblem? = null
    private var pending: OpenTheoryAssignment? = null
    var internalPause: Boolean = false
        private set

    val applicable: Boolean get() = mapping.target === source && mapping.source === original &&
        source.numIntVars == 0 && original.numIntVars == 0 && source.numBoolVars > 0 &&
        source.numBoolVars == original.numBoolVars && source.numRealVars == original.numRealVars &&
        !source.semantics.isApproximate && !original.semantics.isApproximate

    fun completeBooleans(guesses: IntArray, stop: Cancellation): BooleanArray? {
        if (!applicable || guesses.size != source.numBoolVars || stop()) return null
        if (!booleanPreparationAttempted) {
            booleanPreparationAttempted = true
            try {
                booleanModel = prepareBooleans(stop)
            } catch (_: CancellationException) {
                return null
            }
        }
        val model = booleanModel ?: return null
        val referenced = used ?: return null
        stop.charge(8L * (guesses.size + 1L))
        if (stop()) return null
        for (variable in guesses.indices) {
            if (guesses[variable] !in -1..1) return null
            if (guesses[variable] == -1 && !referenced[variable]) guesses[variable] = 0
        }
        val keys = guesses.indices.filter { guesses[it] != -1 }.toIntArray()
        val values = BooleanArray(keys.size) { guesses[keys[it]] == 1 }
        if (stop()) return null
        val session = PropagationSession.forPointCheck(model,
            Assumptions(keys, values, intArrayOf(), longArrayOf()), stop)
        return try {
            if (session.isUnsatAtRoot || session.fixpointCancelled || stop()) return null
            val result = BooleanArray(source.numBoolVars) { session.boolValue(it) ?: return null }
            if (stop() || session.checkSelectedProjection() != ComponentCheck.Feasible) return null
            result.takeUnless { stop() }
        } finally {
            stop.charge(session.work)
        }
    }

    fun offer(value: ExactLiraAssignment, stop: Cancellation): Boolean {
        if (!applicable || stop() || publicationCancellation()) return false
        if (wholeRealPointTruths(original, value.reals, stop) == null) return false
        val assignment = witnesses.assembleRationalProposal(source, value.bools, value.reals, mapping) ?: return false
        stop.charge(8L * (original.numBoolVars + original.numRealVars + original.numFactors + 1L))
        if (stop()) return false
        val approximate = DoubleArray(value.reals.size) { value.reals[it].toDouble() }
        if (approximate.any { !it.isFinite() }) return false
        val sample = Sample(BooleanArray(original.numBoolVars) { assignment.boolValue(it) },
            longArrayOf(), approximate, exactReals = value.reals)
        return try {
            val projection = verification ?: PropagationProblem(original).also { verification = it }
            if (stop()) return false
            when (verifyOpenWitness(original, sample, rootAccounting(stop), projection, onWork = { stop.charge(it) })) {
                is Verification.Accepted -> Unit
                else -> return false
            }
            if (stop() || publicationCancellation()) return false
            pending = assignment
            true
        } catch (_: CancellationException) {
            false
        }
    }

    fun invalidate() {
        pending = null
    }

    fun take(): OpenTheoryAssignment? {
        val result = pending
        pending = null
        return result.takeUnless { publicationCancellation() }
    }

    fun policy(caller: SearchNodePolicy): SearchNodePolicy = object : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            internalPause = false
            val disposition = caller.beforeBranch(context)
            if (disposition != SearchNodeDisposition.Expand || pending == null || publicationCancellation()) {
                return disposition
            }
            internalPause = true
            return SearchNodeDisposition.Pause
        }
    }

    private fun prepareBooleans(stop: Cancellation): BakedProblem? {
        if (source.numBoolVars > 100000 || source.numFactors > 100000 || stop()) return null
        stop.charge(8L * (source.numBoolVars + source.numFactors + 1L))
        if (stop()) return null
        val referenced = BooleanArray(source.numBoolVars)
        val clauses = ArrayList<Clause>()
        var entries = 0L
        for (factor in source.factors) {
            stop.charge(1L)
            if (stop()) return null
            when (factor) {
                is Clause -> {
                    entries += factor.literals.size + 1L
                    if (entries > 1000000L) return null
                    stop.charge(8L * (factor.literals.size + 1L))
                    if (stop()) return null
                    for (literal in factor.literals) referenced[Lit.variable(literal)] = true
                    clauses += factor
                }
                is ExactLinear -> Unit
                else -> return null
            }
        }
        val problem = Problem(source.numBoolVars, 0, emptyArray(), Array<Factor>(clauses.size) { clauses[it] },
            settings = source.settings, semantics = source.semantics)
        val baked = problem.bake(rootAccounting(stop))
        if (stop()) return null
        used = referenced
        return baked
    }

    private fun rootAccounting(stop: Cancellation): Cancellation = object : Cancellation {
        private val meter = object : RootWorkMeter {
            override fun charge(units: Long) = stop.charge(units)
            override fun construction(entries: Long) = stop.charge(entries)
            override fun propagation(units: Long) = stop.charge(units)
        }

        override fun isCancelled(): Boolean = stop()
        override fun deadline() = stop.deadline()
        override fun workMeter(): RootWorkMeter = meter
    }
}

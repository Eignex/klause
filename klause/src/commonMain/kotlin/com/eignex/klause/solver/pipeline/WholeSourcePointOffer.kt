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
import com.eignex.klause.propagation.PropagationResult
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
import com.eignex.klause.util.IntArrayList
import kotlin.coroutines.cancellation.CancellationException

internal class WholeSourcePointOffer(
    private val source: Problem,
    private val original: Problem,
    private val mapping: SourceMapping,
    private val publicationCancellation: Cancellation,
    private val onDecline: ((String) -> Unit)? = null,
    private val onBooleanClause: ((IntArray) -> Unit)? = null,
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
        if (!applicable || guesses.size != source.numBoolVars || stop()) return declined("BOOLEAN_SCOPE")
        if (!booleanPreparationAttempted) {
            booleanPreparationAttempted = true
            try {
                booleanModel = prepareBooleans(stop)
            } catch (_: CancellationException) {
                return null
            }
        }
        val model = booleanModel ?: return declined("BOOLEAN_PREPARATION")
        val referenced = used ?: return declined("BOOLEAN_REFERENCES")
        stop.charge(8L * (guesses.size + 1L))
        if (stop()) return null
        for (variable in guesses.indices) {
            if (guesses[variable] !in -1..1) return null
            if (guesses[variable] == -1 && !referenced[variable]) guesses[variable] = 0
        }
        val staged = onBooleanClause != null
        val assigned = guesses.indices.filter { guesses[it] != -1 }
        val keys = if (staged) (assigned.filter { referenced[it] } + assigned.filter { !referenced[it] }).toIntArray()
            else assigned.toIntArray()
        val values = BooleanArray(keys.size) { guesses[keys[it]] == 1 }
        if (staged) stop.charge(64L * keys.size + 1L)
        if (stop()) return null
        val session = PropagationSession.forPointCheck(model,
            if (staged) Assumptions.None else Assumptions(keys, values, intArrayOf(), longArrayOf()), stop)
        var accountedWork = 0L
        return try {
            if (session.isUnsatAtRoot) return declined("BOOLEAN_ROOT_CONFLICT")
            if (session.fixpointCancelled || stop()) return null
            if (staged) {
                stop.charge(4L * source.numBoolVars + 1L)
                if (stop()) return null
                val roots = IntArray(source.numBoolVars) {
                    session.boolValue(it)?.let { value -> if (value) 1 else 0 } ?: -1
                }
                val prefix = IntArrayList()
                for (index in keys.indices) {
                    stop.charge(session.work - accountedWork + 1L)
                    accountedWork = session.work
                    if (stop() || session.fixpointCancelled) return null
                    val previous = session.boolValue(keys[index])
                    if (previous == values[index]) continue
                    prefix.add(Lit.make(keys[index], values[index]))
                    if (previous != null) return rejectBooleanCube(prefix, stop, session, roots)
                    val result = session.pinBool(keys[index], values[index])
                    stop.charge(session.work - accountedWork)
                    accountedWork = session.work
                    if (stop() || session.fixpointCancelled) return null
                    if (result is PropagationResult.Unsat) return rejectBooleanCube(prefix, stop)
                }
            }
            val result = BooleanArray(source.numBoolVars) {
                session.boolValue(it) ?: return declined("BOOLEAN_UNRESOLVED")
            }
            if (stop() || session.checkSelectedProjection() != ComponentCheck.Feasible) return declined("BOOLEAN_CHECK")
            result.takeUnless { stop() }
        } finally {
            stop.charge(session.work - accountedWork)
        }
    }

    private fun rejectBooleanCube(
        prefix: IntArrayList,
        stop: Cancellation,
        session: PropagationSession? = null,
        roots: IntArray? = null,
    ): Nothing? {
        onDecline?.invoke("BOOLEAN_ROOT_CONFLICT")
        stop.charge(4L * prefix.size + 1L)
        if (stop() || publicationCancellation()) return null
        if (prefix.size > 4096) return declined("BOOLEAN_CUBE_PREFIX_LIMIT")
        val support = if (session != null && roots != null) {
            onDecline?.invoke("BOOLEAN_CUBE_CAUSAL_ATTEMPT")
            causalBooleanCube(prefix, session, roots, stop) ?: prefix
        } else prefix
        if (stop() || publicationCancellation()) return null
        onDecline?.invoke("BOOLEAN_CUBE_SUPPORT_${support.size}_FROM_${prefix.size}")
        val clause = IntArray(support.size) { Lit.negate(support[it]) }
        if (!stop() && !publicationCancellation()) onBooleanClause?.invoke(clause)
        return null
    }

    private fun causalBooleanCube(
        prefix: IntArrayList,
        session: PropagationSession,
        roots: IntArray,
        stop: Cancellation,
    ): IntArrayList? {
        stop.charge(4L * (roots.size + prefix.size) + 1L)
        if (prefix.isEmpty() || stop()) return null
        val trials = IntArray(roots.size) { -1 }
        for (index in 0 until prefix.size - 1) trials[Lit.variable(prefix[index])] = index
        val selected = BooleanArray(prefix.size)
        selected[prefix.size - 1] = true
        if (session.litTruth(prefix.last()) != false) return null
        val pending = IntArrayList().also { it.add(Lit.variable(prefix.last())) }
        val seen = BooleanArray(roots.size)
        var entries = 0
        while (!pending.isEmpty()) {
            stop.charge(4L)
            if (stop()) return null
            val variable = pending.last()
            pending.truncateTo(pending.size - 1)
            if (variable !in roots.indices) return null
            if (seen[variable]) continue
            seen[variable] = true
            val value = session.boolValue(variable) ?: return null
            val trial = trials[variable]
            if (trial >= 0) {
                if (Lit.isPositive(prefix[trial]) != value) return null
                selected[trial] = true
                continue
            }
            // The disposable clause-only root has no trial assumptions.
            if (roots[variable] >= 0) {
                if (roots[variable] != if (value) 1 else 0) return null
                continue
            }
            val reason = session.boolReasonClause(variable, 65536 - entries) { stop.charge(it) } ?: return null
            entries += reason.size
            stop.charge(4L * reason.size + 1L)
            if (stop() || reason.isEmpty() || reason[0] != Lit.make(variable, value)) return null
            for (index in 1 until reason.size) {
                val literal = reason[index]
                if (literal < 0 || Lit.variable(literal) !in roots.indices || session.litTruth(literal) != false) {
                    return null
                }
                pending.add(Lit.variable(literal))
            }
        }
        stop.charge(prefix.size.toLong() + 1L)
        return if (stop()) null else IntArrayList().also { support ->
            for (index in selected.indices) if (selected[index]) support.add(prefix[index])
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
                else -> return declined("BOOLEAN_FACTOR_${factor::class.simpleName}")
            }
        }
        val problem = Problem(source.numBoolVars, 0, emptyArray(), Array<Factor>(clauses.size) { clauses[it] },
            settings = source.settings, semantics = source.semantics)
        val baked = problem.bake(rootAccounting(stop))
        if (stop()) return null
        used = referenced
        return baked
    }

    private fun declined(reason: String): Nothing? {
        onDecline?.invoke(reason)
        return null
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

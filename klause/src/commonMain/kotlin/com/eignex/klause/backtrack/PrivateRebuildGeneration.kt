package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.WorkMeter
import kotlin.time.TimeSource

internal class PrivateRebuildGeneration private constructor(
    val problem: BakedProblem,
    val mapping: SourceMapping,
    val modeledBytes: Long,
    val eliminated: Int,
    val reconstructionWork: Long,
    val passStopped: Boolean,
    val admittedColumns: Int,
    val warmClauses: PrivateRebuildWarmClauses,
    val warmClauseNanos: Long,
    val bakeWork: PrivateRebuildBakeWork,
    val mappingWork: Long,
    val reusedSourceClauses: Int,
    val indexedFactors: Int,
    val sourceFactors: Int,
    val equivalenceMerges: Int,
    val parityPins: Int,
    val affineColumns: Int,
    val affineFreeColumns: Int,
    val parityReducedRows: Int,
    val parityReducedLiterals: Long,
    val parityTriangularRows: Int,
    val parityCoveredClauses: Int,
    val paritySourceRows: Int,
    val paritySourceLiterals: Long,
    val preferredFreeColumns: IntArray,
    val freeSelectorActivity: Boolean,
    val parityJointFactor: Int,
    val parityJointModeledBytes: Long,
) {
    fun reconstruct(sample: Sample): Sample = mapping.reconstructFrom(problem, sample)

    companion object {
        private val guarantees = TransformationGuarantees(true, true, true, false, false)

        fun prepare(
            session: PropagationSession,
            byteLimit: Long,
            cancellation: Cancellation,
            onDecline: (String) -> Unit = {},
            learnedBinary: PrivateRebuildBinaryLearning? = null,
            parity: PrivateRebuildParity? = null,
            jointAffine: Boolean = true,
            passWork: () -> Long = { Long.MAX_VALUE },
        ): PrivateRebuildGeneration? {
            if (!session.canSimplifyRoot || cancellation()) return null
            val source = session.problem as? BakedProblem ?: return null
            if (source.numIntVars != 0 || source.numRealVars != 0) return null
            if (learnedBinary != null && learnedBinary.source !== source) {
                onDecline("BinarySourceIdentity")
                return null
            }
            if (parity != null && (parity.source !== source || learnedBinary != null)) {
                onDecline("ParitySourceIdentity")
                return null
            }
            var entries = source.numBoolVars.toLong() + source.numFactors + 1L
            var retainedEntries = 0L
            var retainedFactors = 0
            for (index in source.factors.indices) {
                val factor = source.factors[index]
                cancellation.charge(1L)
                if (cancellation()) return null
                if (factor !is Clause) {
                    onDecline("Unsupported${factor::class.simpleName ?: "Factor"}")
                    return null
                }
                entries += factor.literals.size
                if (parity != null && !parity.coveredSource[index]) {
                    retainedEntries += factor.literals.size + 1L
                    retainedFactors++
                }
            }
            entries += learnedBinary?.clauses?.sumOf { it.literals.size + 1L } ?: 0L
            if (parity != null) entries += source.numBoolVars * 2L
            val affine = parity?.affine
            val basis = parity?.basis
            val rowPlan = if (affine == null && basis != null) {
                basis.planRows(cancellation, checkNotNull(parity).triangularBasis, onDecline)
            } else null
            if (cancellation()) return null
            val rowLiterals = rowPlan?.literals
            val rowView = rowPlan != null
            val sourceRows = if (rowView) checkNotNull(parity).sourceRowCount else 0
            val sourceRowLiterals = if (rowView) checkNotNull(parity).sourceRowLiterals else 0L
            if (parity != null && affine == null && !rowView && parity.newPins == 0) return null
            val modelEntries = if (rowView) {
                source.numBoolVars * 3L + checkNotNull(basis).rank + checkNotNull(rowLiterals) +
                    sourceRows + sourceRowLiterals + retainedEntries + 1L
            } else entries
            // This admission model covers simultaneous primitive buffers and reference slots,
            // including admission, the BVE database, source copies, reconstruction and a fresh worker.
            // Object headers, the retained canonical worker and allocator overhead are separate.
            val binaryBytes = if (learnedBinary == null) 0L else PrivateRebuildBinaryLearning.SCRATCH_BYTES
            val selectorBytes = if (rowView && source.numBoolVars * 2L <= PROPAGATION_WORK_PER_NODE) {
                PrivateRebuildFreeSelector.modeledBytes(source.numBoolVars)
            } else 0L
            val bytes = modelEntries * 512L + binaryBytes + (parity?.modeledBytes ?: 0L)
            if (bytes > byteLimit) {
                onDecline("Memory")
                return null
            }
            val jointBytes = if (rowView && jointAffine) checkNotNull(basis).jointModeledBytes() else 0L
            val jointFunded = jointBytes > 0L && jointBytes <= byteLimit - bytes - selectorBytes
            val fundedBytes = bytes + if (jointFunded) jointBytes else 0L
            cancellation.charge(source.numBoolVars * if (parity == null) 1L else 3L)
            if (cancellation()) return null
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            if (parity != null) for (variable in fixed.indices) {
                val value = session.boolValue(variable)
                val derived = parity.fixed[variable]
                if (value != null && derived != null && value != derived) {
                    onDecline("ParityRootChanged")
                    return null
                }
                fixed[variable] = value ?: derived
            }
            if (learnedBinary != null) {
                cancellation.charge(2L)
                if (cancellation()) return null
                if (learnedBinary.clauses[0].literals.any { fixed[Lit.variable(it)] != null }) {
                    onDecline("BinaryFixedColumn")
                    return null
                }
            }
            if (affine != null) {
                cancellation.charge(source.numBoolVars * 2L)
                if (cancellation()) return null
            }
            val privateFixed = if (affine == null) fixed else Array(source.numBoolVars) { affine.privateValue(it) }
            val capacity = when {
                rowView -> retainedFactors + checkNotNull(basis).rank + sourceRows + source.numBoolVars +
                    if (jointFunded) 1 else 0
                affine != null -> source.numBoolVars
                else -> source.numFactors
            }
            if (rowView) {
                cancellation.charge(capacity.toLong())
                if (cancellation()) return null
            }
            val residual = ArrayList<Factor>(capacity)
            var jointFactor = -1
            var reused = 0
            if (affine == null) for (index in source.factors.indices) {
                val factor = source.factors[index]
                if (cancellation()) return null
                val literals = (factor as Clause).literals
                cancellation.charge(literals.size * 2L + 1L)
                if (cancellation()) return null
                if (rowView && checkNotNull(parity).coveredSource[index]) continue
                var changed = false
                var satisfied = false
                for (literal in literals) {
                    val variable = Lit.variable(literal)
                    val value = fixed[variable] ?: continue
                    changed = true
                    if (Lit.evaluate(literal, value)) {
                        satisfied = true
                        break
                    }
                }
                if (satisfied) continue
                if (!changed) {
                    residual.add(factor)
                    reused++
                } else {
                    val remaining = literals.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                    if (remaining.isEmpty()) return null
                    residual.add(Clause(remaining))
                }
            }
            if (rowView) {
                residual.addAll(checkNotNull(parity).materializeSourceRows(cancellation) ?: return null)
                val rows = checkNotNull(basis).materializeRows(
                    cancellation, checkNotNull(rowPlan), parity.triangularBasis,
                ) ?: return null
                residual.addAll(rows)
                if (jointFunded) {
                    val joint = checkNotNull(basis).jointRows(cancellation, rows) ?: return null
                    jointFactor = residual.size
                    residual.add(joint)
                }
            }
            val preferred = if (rowView) {
                checkNotNull(basis).preferredFreeCoordinates(cancellation) ?: return null
            } else EmptyIntArray
            if (parity != null) {
                cancellation.charge(source.numBoolVars * 16L)
                if (cancellation()) return null
                for (variable in privateFixed.indices) privateFixed[variable]?.let { value ->
                    residual.add(Clause(intArrayOf(Lit.make(variable, value))))
                }
            }
            if (learnedBinary != null) {
                cancellation.charge(learnedBinary.clauses.size * 3L)
                if (cancellation()) return null
                residual.addAll(learnedBinary.clauses)
            }
            val pinned = Problem(
                source.numBoolVars, 0, emptyArray(), residual.toTypedArray(),
                settings = source.settings, semantics = source.semantics,
            )
            val admission = if (learnedBinary == null && parity == null) {
                PrivateRebuildAdmission.analyze(pinned, cancellation) ?: return null
            } else null
            if (admission?.candidates == 0) {
                onDecline("GrowthProxy")
                return null
            }
            var mapping = SourceMapping(source, pinned, guarantees, lift = { sample ->
                affine?.reconstruct(sample) ?:
                    Sample(BooleanArray(fixed.size) { fixed[it] ?: sample.boolValue(it) }, longArrayOf())
            })
            val neighborhood = admission?.let {
                PrivateRebuildNeighborhood.prepare(pinned, it, cancellation) ?: return null
            }
            var spent = 0L
            val allowance = passWork().coerceAtLeast(0L)
            val meter = WorkMeter {
                spent = saturatedWork(spent, it)
                cancellation.charge(it)
            }
            val passToken = object : Cancellation {
                override fun isCancelled(): Boolean = cancellation() || spent >= allowance
                override fun deadline() = cancellation.deadline()
                override fun workMeter(): WorkMeter = meter
            }
            // A local pass boundary retains complete pivots; the containing call still controls
            // publication, opening and checks, and can decline the entire generation.
            val delta = neighborhood?.eliminate(passToken)
            val equivalences = if (delta == null && parity == null) {
                PrivateRebuildAlias.reduce(pinned, checkNotNull(learnedBinary).clauses, passToken)
            } else null
            val rebuild = delta?.rebuild ?: equivalences?.rebuild ?: SourceRebuilds(emptyList())
            if (cancellation()) return null
            if (delta?.infeasible == true || equivalences?.infeasible == true) {
                onDecline("Refutation")
                return null
            }
            if (rebuild.steps.isEmpty() && parity == null) {
                onDecline(if (spent >= allowance) "PassStoppedEmpty" else "PassCompleteEmpty")
                return null
            }
            val passStopped = spent >= allowance
            var descriptorWork = 0L
            for (step in rebuild.steps) {
                cancellation.charge(1L)
                if (cancellation()) return null
                descriptorWork += 8L
                when (step) {
                    is RebuildStep.SatisfyClauses -> for (clause in step.clauses) {
                        cancellation.charge(1L)
                        if (cancellation()) return null
                        descriptorWork += (clause.size + 1L) * 4L
                    }
                    is RebuildStep.CopyLiteral -> if (step.variable !in 0 until source.numBoolVars ||
                        Lit.variable(step.source) !in 0 until source.numBoolVars) return null
                    else -> return null
                }
            }
            val baseBytes = fundedBytes + descriptorWork * 8L
            val freeSelectorActivity = selectorBytes > 0L && selectorBytes <= byteLimit - baseBytes
            val privateBytes = baseBytes + if (freeSelectorActivity) selectorBytes else 0L
            if (privateBytes > byteLimit) {
                onDecline("Memory")
                return null
            }
            cancellation.charge(entries + descriptorWork)
            if (cancellation()) return null
            val reduced = when {
                delta != null -> delta.materialize(cancellation) ?: return null
                equivalences != null -> pinned.withSourcePassDelta(equivalences)
                    .takeUnless { cancellation() } ?: return null
                else -> pinned
            }
            mapping = mapping.then(SourceMapping(pinned, reduced, guarantees, rebuild))
            val warmStart = TimeSource.Monotonic.markNow()
            val warm = if (affine == null) {
                PrivateRebuildWarmClauses.capture(session, rebuild, fixed, byteLimit - privateBytes,
                    (allowance - spent).coerceAtLeast(0L), cancellation) ?: return null
            } else {
                cancellation.charge(8L)
                if (cancellation()) return null
                PrivateRebuildWarmClauses.empty()
            }
            val warmNanos = warmStart.elapsedNow().inWholeNanoseconds
            val enriched = if (warm.clauses.isEmpty()) reduced else reduced.withFactors(reduced.factors + warm.clauses)
            mapping = mapping.then(SourceMapping(reduced, enriched, TransformationGuarantees.IDENTITY))
            val bakeWork = PrivateRebuildBakeWork(cancellation)
            val baked = enriched.bake(bakeWork)
            if (cancellation()) return null
            mapping = mapping.then(SourceMapping(enriched, baked, TransformationGuarantees.IDENTITY))
            return PrivateRebuildGeneration(baked, mapping, privateBytes + warm.modeledBytes, rebuild.steps.size,
                maxOf(entries * 4L, source.numBoolVars * 12L + descriptorWork, affine?.reconstructionWork ?: 0L),
                passStopped, admission?.candidates ?: 0,
                warm, warmNanos, bakeWork, descriptorWork, reused, neighborhood?.indexedFactors ?: 0,
                neighborhood?.sourceFactors ?: 0, rebuild.steps.count { it is RebuildStep.CopyLiteral },
                parity?.newPins ?: 0, affine?.rank ?: 0, affine?.freeColumns ?: 0,
                if (rowView) checkNotNull(basis).rank else 0, rowLiterals ?: 0L,
                rowPlan?.triangularRows ?: 0,
                if (rowView) checkNotNull(parity).coveredClauses else 0, sourceRows, sourceRowLiterals,
                preferred, freeSelectorActivity, jointFactor, if (jointFunded) jointBytes else 0L)
        }
    }
}

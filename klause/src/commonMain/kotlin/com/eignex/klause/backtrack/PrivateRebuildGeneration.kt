package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
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
) {
    fun reconstruct(sample: Sample): Sample = mapping.reconstructFrom(problem, sample)

    companion object {
        private val guarantees = TransformationGuarantees(true, true, true, false, false)

        fun prepare(
            session: PropagationSession,
            byteLimit: Long,
            cancellation: Cancellation,
            onDecline: (String) -> Unit = {},
            passWork: () -> Long = { Long.MAX_VALUE },
        ): PrivateRebuildGeneration? {
            if (!session.canSimplifyRoot || cancellation()) return null
            val source = session.problem as? BakedProblem ?: return null
            if (source.numIntVars != 0 || source.numRealVars != 0) return null
            var entries = source.numBoolVars.toLong() + source.numFactors + 1L
            for (factor in source.factors) {
                cancellation.charge(1L)
                if (cancellation()) return null
                if (factor !is Clause) {
                    onDecline("Unsupported${factor::class.simpleName ?: "Factor"}")
                    return null
                }
                entries += factor.literals.size
            }
            // This admission model covers simultaneous primitive buffers and reference slots,
            // including admission, the BVE database, source copies, reconstruction and a fresh native worker.
            // Object headers, the retained canonical worker and allocator overhead are separate.
            val bytes = entries * 512L
            if (bytes > byteLimit) {
                onDecline("Memory")
                return null
            }
            cancellation.charge(source.numBoolVars.toLong())
            if (cancellation()) return null
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            val residual = ArrayList<Factor>(source.numFactors)
            var reused = 0
            for (factor in source.factors) {
                if (cancellation()) return null
                val literals = (factor as Clause).literals
                cancellation.charge(literals.size * 2L + 1L)
                if (cancellation()) return null
                var changed = false
                var satisfied = false
                for (literal in literals) {
                    val value = fixed[Lit.variable(literal)] ?: continue
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
            val pinned = Problem(
                source.numBoolVars, 0, emptyArray(), residual.toTypedArray(),
                settings = source.settings, semantics = source.semantics,
            )
            val admission = PrivateRebuildAdmission.analyze(pinned, cancellation) ?: return null
            if (admission.candidates == 0) {
                onDecline("GrowthProxy")
                return null
            }
            var mapping = SourceMapping(source, pinned, guarantees, lift = { sample ->
                Sample(BooleanArray(fixed.size) { fixed[it] ?: sample.boolValue(it) }, longArrayOf())
            })
            val neighborhood = PrivateRebuildNeighborhood.prepare(pinned, admission, cancellation) ?: return null
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
            val delta = neighborhood.eliminate(passToken)
            if (cancellation()) return null
            if (delta.infeasible) {
                onDecline("Refutation")
                return null
            }
            if (delta.rebuild.steps.isEmpty()) {
                onDecline(if (spent >= allowance) "PassStoppedEmpty" else "PassCompleteEmpty")
                return null
            }
            val passStopped = spent >= allowance
            var descriptorWork = 0L
            for (step in delta.rebuild.steps) {
                cancellation.charge(1L)
                if (cancellation()) return null
                val eliminated = step as? RebuildStep.SatisfyClauses ?: return null
                descriptorWork += 8L
                for (clause in eliminated.clauses) {
                    cancellation.charge(1L)
                    if (cancellation()) return null
                    descriptorWork += (clause.size + 1L) * 4L
                }
            }
            val privateBytes = bytes + descriptorWork * 8L
            if (privateBytes > byteLimit) {
                onDecline("Memory")
                return null
            }
            cancellation.charge(entries + descriptorWork)
            if (cancellation()) return null
            val reduced = delta.materialize(cancellation) ?: return null
            mapping = mapping.then(SourceMapping(pinned, reduced, guarantees, delta.rebuild))
            val warmStart = TimeSource.Monotonic.markNow()
            val warm = PrivateRebuildWarmClauses.capture(session, delta.rebuild, fixed, byteLimit - privateBytes,
                (allowance - spent).coerceAtLeast(0L), cancellation) ?: return null
            val warmNanos = warmStart.elapsedNow().inWholeNanoseconds
            val enriched = if (warm.clauses.isEmpty()) reduced else reduced.withFactors(reduced.factors + warm.clauses)
            mapping = mapping.then(SourceMapping(reduced, enriched, TransformationGuarantees.IDENTITY))
            val bakeWork = PrivateRebuildBakeWork(cancellation)
            val baked = enriched.bake(bakeWork)
            if (cancellation()) return null
            mapping = mapping.then(SourceMapping(enriched, baked, TransformationGuarantees.IDENTITY))
            return PrivateRebuildGeneration(baked, mapping, privateBytes + warm.modeledBytes, delta.rebuild.steps.size,
                maxOf(entries * 4L, source.numBoolVars * 12L + descriptorWork), passStopped, admission.candidates,
                warm, warmNanos, bakeWork, descriptorWork, reused, neighborhood.indexedFactors,
                neighborhood.sourceFactors)
        }
    }
}

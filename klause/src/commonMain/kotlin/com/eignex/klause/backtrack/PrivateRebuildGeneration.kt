package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BoundedVariableElimination
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class PrivateRebuildGeneration private constructor(
    val problem: BakedProblem,
    val mapping: SourceMapping,
    val modeledBytes: Long,
    val eliminated: Int,
    val reconstructionWork: Long,
    val passStopped: Boolean,
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
            // including the BVE database, source copies, reconstruction and a fresh native worker.
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
            for (factor in source.factors) {
                if (cancellation()) return null
                val literals = (factor as Clause).literals
                cancellation.charge(literals.size * 2L + 1L)
                if (cancellation()) return null
                if (literals.any { fixed[Lit.variable(it)]?.let { value -> Lit.evaluate(it, value) } == true }) {
                    continue
                }
                val remaining = literals.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                if (remaining.isEmpty()) return null
                residual.add(Clause(remaining))
            }
            val pinned = Problem(
                source.numBoolVars, 0, emptyArray(), residual.toTypedArray(),
                settings = source.settings, semantics = source.semantics,
            )
            var mapping = SourceMapping(source, pinned, guarantees, lift = { sample ->
                Sample(BooleanArray(fixed.size) { fixed[it] ?: sample.boolValue(it) }, longArrayOf())
            })
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
            val delta = BoundedVariableElimination.eliminateSource(pinned, cancellation = passToken)
            if (cancellation()) return null
            if (delta.infeasible) {
                onDecline("Refutation")
                return null
            }
            if (delta.rebuild.steps.isEmpty()) {
                onDecline(if (spent >= allowance) "PassStoppedEmpty" else "PassCompleteEmpty")
                return null
            }
            cancellation.charge(entries)
            if (cancellation()) return null
            val reduced = pinned.withSourcePassDelta(delta) ?: return null
            mapping = mapping.then(SourceMapping(pinned, reduced, guarantees, delta.rebuild))
            val baked = reduced.bake(cancellation)
            if (cancellation()) return null
            mapping = mapping.then(SourceMapping(reduced, baked, TransformationGuarantees.IDENTITY))
            return PrivateRebuildGeneration(baked, mapping, bytes, delta.rebuild.steps.size, entries * 4L,
                spent >= allowance)
        }
    }
}

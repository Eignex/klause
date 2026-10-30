package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation

internal class PrivateRebuildWarmClauses private constructor(
    val clauses: Array<Factor>,
    val literals: Long,
    val modeledBytes: Long,
    val work: Long,
    val translated: Int,
    val projectionScratchBytes: Long,
) {
    companion object {
        fun capture(
            session: PropagationSession,
            rebuild: SourceRebuilds,
            fixed: Array<Boolean?>,
            byteLimit: Long,
            allowance: Long,
            cancellation: Cancellation,
        ): PrivateRebuildWarmClauses? {
            if (!session.canSimplifyRoot || cancellation()) return null
            var spent = 0L
            fun visit(work: Long): Boolean {
                if (work > allowance - spent) return false
                spent += work
                cancellation.charge(work)
                return !cancellation()
            }
            val clauses = ArrayList<Factor>()
            var literals = 0L
            var bytes = 0L
            var translated = 0
            var scratchBytes = 0L
            fun result(): PrivateRebuildWarmClauses? = if (cancellation()) null else {
                PrivateRebuildWarmClauses(clauses.toTypedArray(), literals, bytes + scratchBytes, spent,
                    translated, scratchBytes)
            }
            val projection = PrivateRebuildClauseProjection.prepare(rebuild, fixed.size, ::visit) ?: return result()
            for (index in 0 until session.learnedClauseCount) {
                if (!visit(5L)) break
                if (!session.isLearnedClause(index) || session.learnedClausePermanent(index) ||
                    session.learnedClauseOrigin(index) != OWN_ORIGIN) continue
                val size = session.learnedClauseLiteralCount(index)
                if ((size + 1L) * 512L > byteLimit - bytes - scratchBytes) continue
                if (!visit(size * 3L + 1L)) break
                val source = session.learnedClauseLiterals(index)
                var needsProjection = false
                var supported = true
                for (literal in source) {
                    val variable = Lit.variable(literal)
                    if (variable >= fixed.size) { supported = false; break }
                    needsProjection = needsProjection || projection.eliminates(variable)
                }
                if (!supported) continue
                if (source.any { fixed[Lit.variable(it)]?.let { value -> Lit.evaluate(it, value) } == true }) continue
                var residual = source.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                if (residual.isEmpty()) return null
                if (needsProjection) {
                    if (scratchBytes == 0L) {
                        if (PrivateRebuildClauseProjection.SCRATCH_BYTES > byteLimit - bytes) continue
                        scratchBytes = PrivateRebuildClauseProjection.SCRATCH_BYTES
                    }
                    when (val projected = projection.project(residual, ::visit)) {
                        is PrivateRebuildClauseProjection.Result.Complete -> residual = projected.literals
                        PrivateRebuildClauseProjection.Result.Skipped -> continue
                        PrivateRebuildClauseProjection.Result.Stopped -> break
                        PrivateRebuildClauseProjection.Result.Refuted -> return null
                    }
                    if (!visit(residual.size * 2L + 1L)) break
                    val satisfied = residual.any {
                        fixed[Lit.variable(it)]?.let { value -> Lit.evaluate(it, value) } == true
                    }
                    if (satisfied) continue
                    residual = residual.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                    if (residual.isEmpty()) return null
                }
                if ((residual.size + 1L) * 512L > byteLimit - bytes - scratchBytes) continue
                val copied = Clause(residual)
                if (copied.tautological) continue
                clauses.add(copied)
                if (needsProjection) translated++
                literals += copied.literals.size
                bytes += (copied.literals.size + 1L) * 512L
            }
            return result()
        }
    }
}

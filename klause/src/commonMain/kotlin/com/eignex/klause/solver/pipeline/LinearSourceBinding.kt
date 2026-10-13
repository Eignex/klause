package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.LinearSourceSemanticsChecker
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceSemanticsChecker
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import kotlin.uuid.Uuid

internal fun OpenTheoryRequest.linearAuthorityContext(cancellation: Cancellation): SourceProofContext? {
    linearContext?.let { return it }
    val objective = objective ?: return null
    val original = preparedPlan.originalSource as? Problem ?: return null
    return original.linearAuthorityContext(objective, if (maximize) ObjectiveSense.MAXIMIZE
        else ObjectiveSense.MINIMIZE, cancellation)
}

internal fun Problem.linearAuthorityContext(
    objective: LinearObjective,
    sense: ObjectiveSense,
    cancellation: Cancellation,
): SourceProofContext? {
    for (factor in factors) {
        if (cancellation()) return null
        val form = factor.linearForm as? LinearForm.Conjunction ?: return null
        for (row in form.rows) {
            if (row.relation == LinearOp.NE ||
                (row.relation == LinearOp.EQ && row.activator != LinearRow.ALWAYS)
            ) return null
            val booleans = linkedSetOf<Int>()
            if (row.activator != LinearRow.ALWAYS) booleans += row.activator
            for (id in 0 until row.size) {
                if (Term.isBool(row.ref(id))) booleans += Lit.variable(Term.lit(row.ref(id)))
            }
            if (booleans.size > 8) return null
        }
    }
    val identity = Uuid.random().toString()
    val scope = SourceScope(SourceRevision(identity, "klause-ir-exact-v1"),
        objective = SourceObjective("$identity/objective", sense))
    val captured = sourceProofContext(scope, objective, cancellation) ?: return null
    val affine = LinearSourceSemanticsChecker(captured.coordinates, captured.premises.values.toList(),
        captured.objective, captured.objectiveConstant)
    val source = this
    val checker = object : SourceSemanticsChecker {
        override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck {
            val checked = affine.witness(sample, scope, cancellation)
            if (checked != SourceEvidenceCheck.Checked) return checked
            for (id in 0 until source.numIntVars) {
                if (cancellation()) return SourceEvidenceCheck.Unavailable("affine source check cancelled")
                val domain = source.intDomainOrNull(id) ?: continue
                val value = sample.exactIntValue(id)
                if (!value.fitsLong() || value.toLongExact() !in domain) {
                    return SourceEvidenceCheck.Rejected("integer value is outside its original declared set")
                }
            }
            return SourceEvidenceCheck.Checked
        }

        override fun objectiveEquals(
            sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
        ): SourceEvidenceCheck = affine.objectiveEquals(sample, value, scope, cancellation)

        override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck {
            val checked = witness(claim.sample, scope, cancellation)
            return if (checked == SourceEvidenceCheck.Checked) affine.ray(claim, scope, cancellation) else checked
        }
    }
    return SourceProofContext(scope, captured.coordinates, captured.premises, captured.objective,
        captured.objectiveConstant, checker)
}

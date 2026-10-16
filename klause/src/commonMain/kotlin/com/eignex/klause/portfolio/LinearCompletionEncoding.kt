package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.finiteLinearModel
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.util.Cancellation

internal class LinearCompletionEncoding(private val encoders: PreparedFactorProjections) {
    private var reusable: Boolean? = null
    private var encoded: Problem? = null

    fun modelFor(sample: Sample, cancellation: Cancellation): Problem? {
        if (cancellation()) return null
        encoded?.let { return it }
        if (reusable == null) {
            var independent = encoders.providers.encoder === BuiltInEncoderProvider
            for (factor in encoders.problem.factors) {
                if (cancellation()) return null
                if (factor.variables.reals.isNotEmpty() && factor !is Linear && factor !is ExactLinear) {
                    independent = false
                }
            }
            reusable = independent
        }
        if (reusable != true) return encoders.finiteLinearModel(sample, cancellation)
        val indices = encoders.problem.factors.indices.filter {
            encoders.problem.factors[it].variables.reals.isNotEmpty()
        }
        // Empty conditions make this source model valid for every finite candidate in this worker.
        val prepared = encoders.linearTheoryModel(EncodingTarget.LINEAR, indices, cancellation = cancellation)
            ?: return null
        if (cancellation()) return null
        encoded = prepared
        return prepared
    }
}

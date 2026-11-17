package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.PropagationCapability

internal fun PreparedFactorProjections.hasFinitePeer(index: Int): Boolean =
    problem.factors[index].variables.reals.isEmpty() &&
        capabilities(index).propagation != PropagationCapability.UNSUPPORTED

internal fun Problem.finiteFactorPeer(indices: List<Int>): Problem = Problem.of(
    numBoolVars, numIntVars, declaredIntDomains,
    Array(indices.size) { factors[indices[it]] },
    impliedFactorMask?.let { mask -> BooleanArray(indices.size) { mask[indices[it]] } },
    hasSymmetryBreaking, settings = settings, semantics = semantics,
)

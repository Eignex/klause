package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor

/**
 * Real-valued linear constraint: `Σ coeffs[i] * floatVars[i] ⟨op⟩ bound`. Coefficients and
 * the right-hand side bound are [Double]; variables are float-var ids (indices into
 * [com.eignex.klause.solver.Problem.floatDomains]).
 *
 * This is a pure *data* class today — it carries the constraint shape but neither
 * propagates nor participates in local-search moves. Engines reach it via the
 * per-backend float strategy:
 *
 *  - Bit-blaster, LogicNG: routed through a `FloatLowering` pass that buckets each
 *    float var onto an int-var range and rewrites this factor as an integer
 *    [Linear] with scaled-and-rounded coefficients.
 *  - Z3: emitted natively as `mkReal*` arithmetic over `Real` sort.
 *  - LocalSearchSolver / BacktrackSolver: temporarily routed through the same
 *    bucketing lowering until native float moves / interval branching land.
 *
 * Mixed factors (containing both float and int vars) are *not* modeled here — split
 * the constraint or introduce auxiliary float variables that equal integer values.
 */
class FloatLinear(
    val coeffs: DoubleArray,
    val vars: IntArray,
    val op: LinearOp,
    val bound: Double,
) : Factor {
    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "FloatLinear must have at least one term" }
    }

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = EMPTY
    override val floatVars: IntArray = vars

    companion object {
        private val EMPTY = IntArray(0)
    }
}

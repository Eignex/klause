package com.eignex.klause.solver

/**
 * MiniZinc-style `solve minimize x` builders. Return a [LinearObjective] sized to the
 * caller's [Problem] so backends that index coefficient arrays by variable id (e.g.
 * `WorstObjective` destroy in ALNS, the Z3 translator's per-var loop) read consistent
 * bounds. The factories live as `Problem` extensions rather than on [LinearObjective]'s
 * companion because they need the variable-count context the Problem already carries —
 * keeping [LinearObjective] a pure data class while the "point at one variable" idiom
 * sits at the layer that knows about variables.
 *
 * For schema-level callers, see the `CompiledProblem.minimize(handle)` overloads which
 * delegate here after resolving handle names to ids and folding float-bucket scaling
 * into real-valued coefficients.
 */
fun Problem.minimizeInt(intVar: Int): LinearObjective {
    require(intVar in 0 until numIntVars) { "intVar $intVar out of [0, $numIntVars)" }
    val arr = LongArray(numIntVars)
    arr[intVar] = 1L
    return LinearObjective(intCoefficients = arr)
}

/** Optimizers minimise; a -1 coefficient maximises. */
fun Problem.maximizeInt(intVar: Int): LinearObjective {
    require(intVar in 0 until numIntVars) { "intVar $intVar out of [0, $numIntVars)" }
    val arr = LongArray(numIntVars)
    arr[intVar] = -1L
    return LinearObjective(intCoefficients = arr)
}

/** Penalise the Boolean being true. */
internal fun Problem.minimizeBool(boolVar: Int): LinearObjective {
    require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
    val arr = LongArray(numBoolVars)
    arr[boolVar] = 1L
    return LinearObjective(boolWeights = arr)
}

/** Reward the Boolean being true. */
internal fun Problem.maximizeBool(boolVar: Int): LinearObjective {
    require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
    val arr = LongArray(numBoolVars)
    arr[boolVar] = -1L
    return LinearObjective(boolWeights = arr)
}

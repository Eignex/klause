package com.eignex.klause.solver.objective
import com.eignex.klause.ir.Problem

/**
 * Minimise one integer variable. The objective is sized to this problem, so consumers that index
 * coefficient arrays by variable id read consistent bounds. The factories are [Problem] extensions
 * because they need its variable counts, which keeps [LinearObjective] free of problem context.
 */
fun Problem.minimizeInt(intVar: Int): LinearObjective {
    require(intVar in 0 until numIntVars) { "intVar $intVar out of [0, $numIntVars)" }
    val arr = LongArray(numIntVars)
    arr[intVar] = 1L
    return LinearObjective(intCoefficients = arr)
}

/** Maximise one integer variable as a -1 coefficient, since optimizers minimise. */
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

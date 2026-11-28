package com.eignex.klause.solver.objective
import com.eignex.klause.ir.Problem

/** Minimise one integer variable, with coefficients sized to this problem. */
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

internal fun Problem.minimizeBool(boolVar: Int): LinearObjective {
    require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
    val arr = LongArray(numBoolVars)
    arr[boolVar] = 1L
    return LinearObjective(boolWeights = arr)
}

internal fun Problem.maximizeBool(boolVar: Int): LinearObjective {
    require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
    val arr = LongArray(numBoolVars)
    arr[boolVar] = -1L
    return LinearObjective(boolWeights = arr)
}

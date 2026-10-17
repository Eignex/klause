package com.eignex.klause.solver.objective

import com.eignex.klause.ir.QuadraticObjectiveSpec

internal interface SourceObjectivePairing {
    val owner: Any

    fun original(objective: LinearObjective): QuadraticObjectiveSpec

    fun copied(previous: LinearObjective, replacement: LinearObjective): SourceObjectivePairing

    fun negated(): SourceObjectivePairing
}

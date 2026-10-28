package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective

internal class ResidualProbeContext(
    val snapshot: ResidualSnapshot,
    val reconstruct: (Sample) -> Sample,
    val originalProblem: Problem,
    val originalObjective: LinearObjective,
    val preparedRoot: BakedProblem,
    val preparedObjective: LinearObjective,
)

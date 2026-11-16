package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem

internal class TrajectoryModel(
    val problem: Problem,
    val objective: LinearObjectiveSpec?,
    val intVarNames: Map<String, Int>,
    val sense: ObjectiveSense,
    val definedVars: IntArray = IntArray(0),
    val definitionHints: List<DefinitionHint> = emptyList(),
) {
    val semantics get() = problem.semantics
}

internal fun trajectoryModel(bytes: ByteArray, format: String): TrajectoryModel = when (format) {
    "xcsp3" -> Xcsp3.parse(bytes.decodeToString()).let {
        TrajectoryModel(it.problem, it.objective, it.intVarNames, it.sense, it.definedVars, it.definitionHints)
    }
    "mps" -> Mps.parse(bytes.decodeToString()).toProblem().let {
        require(it.sourceExact && it.floatColumns == 0 && it.model.numRealVars == 0 && !it.maximize) {
            "MPS trajectories require an exactly retained pure integer minimization model"
        }
        TrajectoryModel(it.model, it.objective, it.columns.associate { column -> column.name to column.id },
            ObjectiveSense.MINIMIZE)
    }
    else -> error("unsupported trajectory format: $format")
}

package com.eignex.klause.solver.evidence

import com.eignex.klause.solver.pipeline.QuadraticBoxBound
import com.eignex.klause.theory.qfnra.ConvexPolynomialOptimum
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialIntegerRecession
import com.eignex.klause.theory.qfnra.PolynomialObjectiveEvidence

internal sealed interface QuadraticEngineDerivation : SourceDerivation.Extension {
    data class Convex(val evidence: ConvexPolynomialOptimum) : QuadraticEngineDerivation
    data class Image(val evidence: PolynomialObjectiveEvidence) : QuadraticEngineDerivation
    data class Recession(val evidence: PolynomialIntegerRecession) : QuadraticEngineDerivation
    class Condition(condition: PolynomialCondition) : QuadraticEngineDerivation {
        private val captured = PolynomialCondition(condition.bools, condition.lower, condition.upper)
        val condition: PolynomialCondition get() = PolynomialCondition(captured.bools, captured.lower, captured.upper)
    }
    data class Box(val bounds: QuadraticBoxBound) : QuadraticEngineDerivation
    class Branches(evidence: List<SourceDerivation>) : QuadraticEngineDerivation {
        private val captured = evidence.toList()
        val evidence: List<SourceDerivation> get() = captured.toList()
    }
    data class Both(val first: SourceDerivation, val second: SourceDerivation) : QuadraticEngineDerivation
}

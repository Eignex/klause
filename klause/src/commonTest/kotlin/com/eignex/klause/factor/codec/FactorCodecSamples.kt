package com.eignex.klause.factor.codec

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.Sort
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl

// One factor or more per built-in codec, over 6 Booleans, 6 integers in [-10, 10] and 4 reals in [-5, 5].

internal val sampleWide = bigIntOf(1) shl 100

private val wide = sampleWide

private fun lit(variable: Int, positive: Boolean = true) = Lit.make(variable, positive)

private fun ratio(num: Long, den: Long) = BigFraction.of(bigIntOf(num), bigIntOf(den))

internal fun factorCodecSamples(): List<Pair<String, Factor>> = listOf(
    "exactRealEquality" to com.eignex.klause.factor.arithmetic.ExactRealEquality(
        SourceRealId(0), com.eignex.klause.formats.json.ExactRealJson.decodeFromString("""{"exp":"1"}"""),
    ),
    "clause" to Clause(intArrayOf(lit(0), lit(1, false))),
    "cardinality" to Cardinality(intArrayOf(lit(0), lit(1), lit(2)), 1, 2),
    "xor" to Xor(intArrayOf(lit(0), lit(1), lit(2, false)), 1),
    "gaussianXor" to GaussianXor(listOf(Xor(intArrayOf(lit(0), lit(1)), 1), Xor(intArrayOf(lit(1), lit(2)), 0))),
    "pseudoBoolean" to PseudoBoolean(longArrayOf(3, -5), intArrayOf(lit(0), lit(1, false)), PbOp.LE, 2),
    "reifiedCardinality" to ReifiedCardinality(5, intArrayOf(lit(0), lit(1)), 1, 2),
    "reifiedPseudoBoolean" to ReifiedPseudoBoolean(
        5, arrayOf(wide, bigIntOf(-3)), intArrayOf(lit(0), lit(1)), PbOp.GE, bigIntOf(7),
    ),
    "linear" to Linear(longArrayOf(2, -3), intArrayOf(0, 1), LinearOp.GE, 4),
    "linear" to Linear(intArrayOf(0, 1), arrayOf(wide, bigIntOf(-1)), LinearOp.EQ, wide),
    "linear" to Linear(
        intArrayOf(0), doubleArrayOf(0.5), intArrayOf(1), doubleArrayOf(-1.25), LinearOp.LE, 2.5, true,
    ),
    "reifiedLinear" to ReifiedLinear(4, intArrayOf(0, 1), arrayOf(wide, BIG_ONE), LinearOp.EQ, wide),
    "exactLinear" to ExactLinear.of(
        intArrayOf(Term.ofIntVar(0), Term.ofRealVar(1)),
        listOf(ratio(1, 3), BigFraction.ONE),
        LinearOp.GE,
        ratio(5, 2),
        strict = true,
        activator = 3,
    ),
    "linearIndicator" to LinearIndicator(
        2,
        true,
        ExactLinear.of(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ofLong(4)),
    ),
    "reifiedRealLinear" to ReifiedRealLinear(
        1, intArrayOf(0), doubleArrayOf(1.5), intArrayOf(0), doubleArrayOf(-0.25), LinearOp.LE, 3.75, true,
    ),
    "comparisonClause" to ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(3, -2)),
    "product" to Product(0, 1, 2),
    "power" to Power(0, 2, 1),
    "power" to Power(Power.Coordinate.Real(SourceRealId(0)), 3, Power.Coordinate.Real(SourceRealId(1))),
    "realProduct" to RealProduct(0, 0, 1, -5.0, Double.POSITIVE_INFINITY),
    "continuousProduct" to ContinuousProduct(0, 1, 2),
    "continuousProduct" to ContinuousProduct(
        0, 1, 2, 0, doubleArrayOf(0.5, -1.0), intArrayOf(lit(0), lit(1, false)),
    ),
    "abs" to Abs(0, 1),
    "realAbs" to RealAbs(0, 1),
    "affineAbs" to AffineAbs(
        AbsAffineValue(mapOf(0 to wide, 1 to bigIntOf(-2)), bigIntOf(3)),
        AbsAffineValue(mapOf(2 to BIG_ONE), BIG_ZERO),
    ),
    "gridAbs" to GridAbs(0, 1, -2.0, 2.0, 5, 0.0, 2.0, 3),
    "arrayMinMax" to ArrayMinMax(0, intArrayOf(1, 2, 2), true),
    "realArrayMinMax" to RealArrayMinMax(0, intArrayOf(1, 2), false),
    "gridArrayMinMax" to GridArrayMinMax(
        intArrayOf(0, 1), doubleArrayOf(0.5, 0.25), doubleArrayOf(0.0, -1.0), booleanArrayOf(false, true), 4, true,
    ),
    "gridProduct" to GridProduct(
        GridCoordinate(0, 0.0, 1.0, 3), GridCoordinate(1, -1.0, 1.0, 5), GridCoordinate(2, -1.0, 1.0, 9),
    ),
    "gridPower" to GridPower(GridCoordinate(0, -1.0, 1.0, 5), 2, GridCoordinate(1, 0.0, 1.0, 5)),
    "divMod" to DivMod(0, 1, 2, 3),
    "divMod" to DivMod(0, wide, 1, 2, DivisionConvention.EUCLIDEAN),
    "wideDivMod" to WideDivMod(
        DivModExpression(mapOf(0 to wide), BIG_ONE),
        bigIntOf(7),
        DivModExpression(mapOf(1 to BIG_ONE), BIG_ZERO),
        DivModExpression(mapOf(2 to BIG_ONE), BIG_ZERO),
    ),
    "realFiniteDomain" to RealFiniteDomain(0, doubleArrayOf(1.5, -0.0, 0.1)),
    "allDifferent" to AllDifferent(
        intArrayOf(0, 1, 2), intArrayOf(lit(0), lit(1), lit(2)), longArrayOf(0, -4), boundsConsistent = true,
    ),
    "allDifferentTuples" to AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3))),
    "distinct" to Distinct(
        listOf(
            Distinct.Operand(
                intArrayOf(Term.ofIntVar(0), Term.ofLit(lit(1, false))),
                listOf(BigFraction.ONE, ratio(1, 2)),
                ratio(-1, 3),
            ),
            Distinct.Operand(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.of(wide, BIG_ONE))),
        ),
        activator = 2,
    ),
    "globalCardinality" to GlobalCardinality(intArrayOf(0, 1, 2), longArrayOf(1, 2), countVars = intArrayOf(3, 4)),
    "globalCardinality" to GlobalCardinality(
        intArrayOf(0, 1),
        longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
        countLow = intArrayOf(0, 1),
        countHigh = intArrayOf(1, 2),
        closed = true,
        presents = intArrayOf(lit(0), lit(1)),
    ),
    "nValue" to NValue(2, intArrayOf(0, 1, 2), NValue.Mode.AtMost, exceptSet = longArrayOf(0)),
    "increasing" to Increasing(intArrayOf(0, 1, 2), true),
    "sort" to Sort(intArrayOf(0, 1), intArrayOf(2, 3)),
    "valuePrecede" to ValuePrecede(Long.MIN_VALUE, Long.MAX_VALUE, intArrayOf(0, 1, 2)),
    "lexLess" to LexLess(intArrayOf(0, 1), intArrayOf(2, 3), false),
    "inverse" to Inverse(intArrayOf(0, 1), intArrayOf(2, 3), 1, 0),
    "symmetricAllDifferent" to SymmetricAllDifferent(intArrayOf(0, 1, 2, 3), 1),
    "circuit" to Circuit(intArrayOf(0, 1, 2), subcircuit = true),
    "cumulative" to Cumulative(
        intArrayOf(0, 1), longArrayOf(2, 3), longArrayOf(1, 1), 2,
        durationVars = intArrayOf(2, 3), resourceVars = intArrayOf(4, 5), capacityVar = 5,
    ),
    "diffn" to Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(1, 2), longArrayOf(2, 1), nonStrict = true),
    "element" to Element(0, 1, longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE), false, 0),
    "element" to Element(0, 1, longArrayOf(2, 3), true),
    "realElement" to RealElement(0, 1, intArrayOf(2, 3), 1),
    "realElement" to RealElement(0, 1, doubleArrayOf(-0.5, 1e-300), 0),
    "table" to Table(intArrayOf(0, 1), longArrayOf(1, 2, 3, 4), longArrayOf(1, 5, 3, 4)),
    "regular" to Regular(intArrayOf(0, 1), 2, 2, longArrayOf(1, 2, 2, 0), 1, intArrayOf(2)),
    "mdd" to Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 1), longArrayOf(0, 5, 0), 0, intArrayOf(0), 3),
    "symmetryHandling" to SymmetryHandling(listOf(intArrayOf(1, 0, 2, 3, 4, 5) to intArrayOf(0, 1, 2, 3, 4, 5))),
)

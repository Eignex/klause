package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.*
import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.FloatCoordinate
import com.eignex.util.collections.IntArrayList
import kotlin.math.*

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 4 else 3)
    val varRefsAll = evalFloatVarArray(c.args[1])

    val coefs = evalFloatConstArray(c.args[0])
    if (coefs.size != varRefsAll.size) {
        failHere(
            "float linear coefficient and variable arrays have different lengths",
        )
    }
    val varRefs = varRefsAll
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> LinearOp.LE
    }
    postExactFloatLinear(
        coefs,
        IntArray(coefs.size) { varRefs[it].varId },
        op,
        evalFloatConst(c.args[2]),
        reifier = if (reified) resolveBoolLit(c.args[3]) else null,
    )
}

internal fun FlatZincCompiler.emitInt2Float(c: FznConstraint) {
    expectArity(c, 2)
    val xInt = resolveIntVar(c.args[0])
    val yName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("int2float: second arg must be a float var identifier")
    val yBk = floatVars[yName] ?: failHere("`$yName` is not a float var")

    // y (real) = x (int): the mixed row 1·x − 1·y = 0.
    factors.add(
        Linear(intArrayOf(xInt), doubleArrayOf(1.0), intArrayOf(yBk.varId), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
    )
}

internal fun FlatZincCompiler.resolveFloatVarOrConst(e: FznExpr): FloatRef = when (e) {
    is FznExpr.FloatLit -> FloatRef.Const(e.value)

    is FznExpr.IntLit -> FloatRef.Const(exactFloatInteger(e.value))

    is FznExpr.Ident -> floatVars[e.name]?.let { FloatRef.Var(it) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Float)?.let { FloatRef.Const(it.value) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Int)?.let { FloatRef.Const(exactFloatInteger(it.value)) }
        ?: failHere("`${e.name}` is not a float var or float param")

    is FznExpr.ArrayAccess -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> {
            val coordinates = arr.floatCoordinates ?: failHere("`${e.name}` is not a float var array")
            FloatRef.Var(coordinates[arrayOffset(coordinates.size, e.index, e.name)])
        }

        is FlatZincArray.FloatParam -> FloatRef.Const(arr.values[arrayOffset(arr.length, e.index, e.name)])

        is FlatZincArray.IntParam ->
            FloatRef.Const(exactFloatInteger(arr.values[arrayOffset(arr.length, e.index, e.name)]))

        else -> failHere("`${e.name}` is not a float array")
    }

    else -> failHere("expected float var or float constant, got ${e::class.simpleName}")
}

internal sealed interface FloatRef {
    data class Var(val bk: FloatCoordinate) : FloatRef
    data class Const(val value: Double) : FloatRef
}

internal fun FlatZincCompiler.resolveFloatElement(
    e: FznExpr,
    name: String,
): FloatCoordinate = when (val ref = resolveFloatVarOrConst(e)) {
    is FloatRef.Var -> ref.bk

    is FloatRef.Const -> {
        allocFloat(name, ref.value, ref.value)
        floatVars.getValue(name)
    }
}

internal fun FlatZincCompiler.emitFloatBinaryCmp(c: FznConstraint, op: LinearOp, strict: Boolean, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
    val a = resolveFloatVarOrConst(c.args[0])
    val b = resolveFloatVarOrConst(c.args[1])
    when {
        a is FloatRef.Const && b is FloatRef.Const -> {
            val holds = when (op) {
                LinearOp.LE -> if (strict) a.value < b.value else a.value <= b.value
                LinearOp.GE -> if (strict) a.value > b.value else a.value >= b.value
                LinearOp.EQ -> a.value == b.value
                LinearOp.NE -> a.value != b.value
            }
            if (reified) {
                val r = resolveBoolLit(c.args[2])
                factors.add(
                    Clause(
                        intArrayOf(
                            if (holds) r else Lit.negate(r),
                        ),
                    ),
                )
            } else if (!holds) {
                // Two exact float constants that violate their relation: an exact contradiction.
                postFalseFactor()
            }
            return
        }

        else -> Unit
    }

    // `a OP b` ⟺ `(a − b) OP 0`: real coefficients on the var operands, constants moved to the bound.
    val rv = IntArrayList()
    val rc = ArrayList<Double>()
    var bound = 0.0
    for ((ref, sign) in listOf(a to 1.0, b to -1.0)) {
        when (ref) {
            is FloatRef.Var -> {
                rv.add(ref.bk.varId)
                rc.add(sign)
            }

            is FloatRef.Const -> bound -= sign * ref.value
        }
    }
    postExactFloatLinear(
        rc.toDoubleArray(),
        rv.toIntArray(),
        op,
        bound,
        strict,
        if (reified) resolveBoolLit(c.args[2]) else null,
    )
}

internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 4 else 3)
    val variables = evalFloatVarArray(c.args[1])

    val coefficients = evalFloatConstArray(c.args[0])
    if (coefficients.size != variables.size) {
        failHere("float linear coefficient and variable arrays differ in length")
    }
    postExactFloatLinear(
        coefficients,
        IntArray(variables.size) { variables[it].varId },
        LinearOp.LE,
        evalFloatConst(c.args[2]),
        strict = true,
        reifier = if (reified) resolveBoolLit(c.args[3]) else null,
    )
}

internal fun FlatZincCompiler.emitFloatMinMax(c: FznConstraint, max: Boolean) {
    expectArity(c, 3)
    emitRealMinMax(c, max)
}

// c = a / b posted as float_times(b, c, a); arity is checked before reordering so a truncated call fails
// with a located error.
internal fun FlatZincCompiler.emitFloatDiv(c: FznConstraint) {
    expectArity(c, 3)
    val denominator = resolveFloatVarOrConst(c.args[1])
    if ((denominator as? FloatRef.Const)?.value == 0.0) {
        failHere("float_div: division by zero")
    }
    if (denominator is FloatRef.Var) {
        emitFloatBinaryCmp(
            FznConstraint("float_ne", listOf(c.args[1], FznExpr.FloatLit(0.0)), emptyList()),
            LinearOp.NE,
            strict = false,
            reified = false,
        )
    }
    emitFloatTimes(
        FznConstraint(
            name = "float_times",
            args = listOf(c.args[1], c.args[2], c.args[0]),
            annotations = c.annotations,
        ),
    )
}

internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    expectArity(c, 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    emitIntegerImageProduct(c, cRef)
    if (aRef is FloatRef.Const && bRef is FloatRef.Const) {
        emitFiniteFloatProduct(aRef, bRef, cRef)
        return
    }
    if ((aRef is FloatRef.Const) != (bRef is FloatRef.Const)) {
        val k = (aRef as? FloatRef.Const)?.value ?: (bRef as FloatRef.Const).value
        val xArg = if (aRef is FloatRef.Const) c.args[1] else c.args[0]
        emitFloatLinear(
            FznConstraint(
                "float_lin_eq",
                listOf(
                    FznExpr.ArrayLit(
                        if (cRef is FloatRef.Const) {
                            listOf(FznExpr.FloatLit(k))
                        } else {
                            listOf(FznExpr.FloatLit(k), FznExpr.FloatLit(-1.0))
                        },
                    ),
                    FznExpr.ArrayLit(if (cRef is FloatRef.Const) listOf(xArg) else listOf(xArg, c.args[2])),
                    FznExpr.FloatLit((cRef as? FloatRef.Const)?.value ?: 0.0),
                ),
                emptyList(),
            ),
            reified = false,
        )
        return
    }
    val aImage = (aRef as? FloatRef.Var)?.let { integerFloatImages[it.bk.varId] }
    val bImage = (bRef as? FloatRef.Var)?.let { integerFloatImages[it.bk.varId] }
    val image = aImage ?: bImage
    val realRef = if (aImage != null) bRef else aRef
    if (cRef is FloatRef.Var && image != null && realRef is FloatRef.Var) {
        emitIntegerFloatProduct(image, realRef, cRef)
        return
    }
    emitFiniteFloatProduct(aRef, bRef, cRef)
}

internal fun FlatZincCompiler.emitFloatAbs(c: FznConstraint) {
    expectArity(c, 2)
    val xRef = resolveFloatVarOrConst(c.args[0])
    val yRef = resolveFloatVarOrConst(c.args[1])
    if (xRef is FloatRef.Const) {
        emitFloatLinear(
            FznConstraint(
                "float_lin_eq",
                listOf(
                    FznExpr.ArrayLit(listOf(FznExpr.FloatLit(1.0))),
                    FznExpr.ArrayLit(listOf(c.args[1])),
                    FznExpr.FloatLit(abs(xRef.value)),
                ),
                emptyList(),
            ),
            reified = false,
        )
        return
    }
    val x = (xRef as FloatRef.Var).bk
    val y = when (yRef) {
        is FloatRef.Var -> yRef.bk
        is FloatRef.Const -> resolveFloatElement(c.args[1], "__float_abs_${floatVars.size}")
    }
    factors.add(RealAbs(x.varId, y.varId))
}

internal fun FlatZincCompiler.emitArrayFloatElement(c: FznConstraint) = emitFloatSelection(c)

internal fun FlatZincCompiler.evalFloatVarArray(e: FznExpr): List<FloatCoordinate> = when (e) {
    is FznExpr.ArrayLit -> {
        e.elements.map { resolveFloatElement(it, "__float_${floatVars.size}") }
    }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars ->
            arr.floatCoordinates
                ?: failHere("`${e.name}` is not a float var array")

        else -> failHere("`${e.name}` is not a float var array")
    }

    else -> failHere("expected float var array, got ${e::class.simpleName}")
}

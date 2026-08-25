package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.FunctionalObjective.Operand
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap

/** Build an exact [FunctionalObjective] from `defines_var` annotations when possible: a bool-count
 *  objective `Σ w_i·bool2int(and_i)` (`array_bool_and` / `_or` indicators, whose defining sweep already
 *  excludes them from search) when it matches, else the general int cone. */
internal fun FlatZincCompiler.buildFunctionalObjective(objName: String, minimize: Boolean): FunctionalObjective? =
    buildBoolCountObjective(objName, minimize) ?: buildIntFunctionalObjective(objName, minimize)

/**
 * Recognize `objective = Σ w_i·bool2int(b_i)` where each `b_i` is an `array_bool_and` / `array_bool_or`
 * indicator, and mirror it as a bool-term [FunctionalObjective] over those indicators' literals. The
 * indicators are functionally defined (their sweep excludes them from search), so a plain
 * [com.eignex.klause.solver.objective.LinearObjective] gives moves on the literals a zero gradient; this
 * evaluates each `and_i` from its literals so a literal flip yields the true objective delta. Returns
 * null on any shape it can't mirror exactly, falling back to [buildIntFunctionalObjective].
 */
internal fun FlatZincCompiler.buildBoolCountObjective(objName: String, minimize: Boolean): FunctionalObjective? {
    val objId = intVars[objName] ?: return null
    val byInt = MutableIntObjectMap<FznConstraint>()
    val byBool = MutableIntObjectMap<FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val arg = ann.args.firstOrNull() ?: continue
        val iv = varIdOrNull(arg)
        if (iv != null) {
            if (!byInt.containsKey(iv)) byInt.put(iv, c)
        } else {
            val bv = boolIdOrNull(arg) ?: continue
            if (!byBool.containsKey(bv)) byBool.put(bv, c)
        }
    }
    val objDef = byInt[objId] ?: return null
    if (objDef.name != "int_lin_eq") return null
    val coeffs = runCatching { evalIntConstArray(objDef.args[0]) }.getOrNull() ?: return null
    val opnds = arrayOperands(objDef.args[1]) ?: return null
    val cval = runCatching { evalIntConst(objDef.args[2]) }.getOrNull() ?: return null
    if (coeffs.size != opnds.size) return null
    val slot = opnds.indexOfFirst { it.varId == objId }
    if (slot < 0) return null
    val outCoeff = coeffs[slot].toLong()
    if (outCoeff != 1L && outCoeff != -1L) return null

    val boolTerms = IntArrayList()
    val boolCoeffs = LongArrayList()
    val boolNodes = ArrayList<FunctionalObjective.BoolFold>()
    val boolLeaves = LinkedHashSet<Int>()
    val nodeAdded = IntHashSet()
    for (k in opnds.indices) {
        if (k == slot) continue
        val termVar = opnds[k].varId
        if (termVar < 0) return null
        // obj = (c − Σ coeffs·term)/outCoeff, so term's weight in the objective is −coeffs[k]/outCoeff.
        val termDef = byInt[termVar] ?: return null
        if (termDef.name != "bool2int") return null
        val b = boolIdOrNull(termDef.args[0]) ?: return null
        val bDef = byBool[b] ?: return null
        if (bDef.name != "array_bool_and" && bDef.name != "array_bool_or") return null
        val lits = runCatching { evalBoolVarArray(bDef.args[0]) }.getOrNull() ?: return null
        val weight = -coeffs[k].toLong() / outCoeff
        boolTerms.add(b)
        boolCoeffs.add(if (minimize) weight else -weight)
        if (nodeAdded.add(b)) {
            boolNodes.add(FunctionalObjective.BoolFold(b, lits, bDef.name == "array_bool_and"))
            for (lit in lits) boolLeaves.add(Lit.variable(lit))
        }
    }
    if (boolTerms.isEmpty()) return null
    val constant = cval / outCoeff
    return FunctionalObjective(
        EmptyIntArray, EmptyLongArray, if (minimize) constant else -constant, minimize = true,
        emptyList(), EmptyIntArray,
        boolTerms.toIntArray(), boolCoeffs.toLongArray(), boolNodes, boolLeaves.toIntArray(),
    )
}

/** Build an exact int-cone [FunctionalObjective] from `defines_var` annotations when possible. */
internal fun FlatZincCompiler.buildIntFunctionalObjective(objName: String, minimize: Boolean): FunctionalObjective? {
    val objId = intVars[objName] ?: return null
    val byDef = MutableIntObjectMap<FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val defId = varIdOrNull(ann.args.firstOrNull() ?: continue) ?: continue
        if (!byDef.containsKey(defId)) byDef.put(defId, c)
    }
    if (!byDef.containsKey(objId)) return null

    val nodes = ArrayList<FunctionalObjective.Node>()
    val visited = IntHashSet()
    var ok = true
    fun visit(id: Int) {
        if (!ok || id in visited) return
        val c = byDef[id] ?: return
        visited.add(id)
        val node = buildObjNode(c, id)
        if (node == null) {
            ok = false
            return
        }
        for (inId in nodeInputVarIds(node)) {
            visit(inId)
            if (!ok) return
        }
        nodes.add(node)
    }
    visit(objId)
    if (!ok) return null
    val defined = IntHashSet()
    for (n in nodes) defined.add(n.out)
    val leaves = LinkedHashSet<Int>()
    for (n in nodes) for (inId in nodeInputVarIds(n)) if (inId !in defined) leaves.add(inId)
    return FunctionalObjective(objId, minimize, nodes, leaves.toIntArray())
}

/** Build a [DefinitionalSweep] from evaluable `defines_var` constraints. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun FlatZincCompiler.buildDefinitionalSweep(): DefinitionalSweep? {
    val byIntDef = MutableIntObjectMap<FznConstraint>()
    val byBoolDef = MutableIntObjectMap<FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val arg = ann.args.firstOrNull() ?: continue
        val intId = varIdOrNull(arg)
        if (intId != null) {
            if (!byIntDef.containsKey(intId)) byIntDef.put(intId, c)
            continue
        }
        val boolId = boolIdOrNull(arg) ?: continue
        if (!byBoolDef.containsKey(boolId)) byBoolDef.put(boolId, c)
    }
    if (byIntDef.isEmpty() && byBoolDef.isEmpty()) return null

    val nodes = ArrayList<DefinitionalSweep.SweepNode>(byIntDef.size + byBoolDef.size)
    // Cycles cannot be topologically swept in one pass, so cycle members are left as searched vars.
    val grayInt = IntHashSet()
    val grayBool = IntHashSet()
    val doneInt = IntHashSet()
    val doneBool = IntHashSet()
    val cyclicInt = IntHashSet()
    val cyclicBool = IntHashSet()
    var visitBoolRef: ((Int) -> Unit)? = null
    fun finishVisit(
        id: Int,
        built: DefinitionalSweep.SweepNode?,
        cyclic: IntHashSet,
        gray: IntHashSet,
        done: IntHashSet,
        visitInt: (Int) -> Unit,
    ) {
        if (built != null) {
            for (inId in built.intInputs) visitInt(inId)
            for (inId in built.boolInputs) visitBoolRef?.invoke(inId)
            if (id !in cyclic) nodes.add(built)
        }
        gray.remove(id)
        done.add(id)
    }

    fun visitInt(id: Int) {
        if (id in doneInt) return
        if (id in grayInt) {
            cyclicInt.add(id)
            return
        }
        val c = byIntDef[id] ?: return
        grayInt.add(id)
        val built = buildIntSweepNode(c, id)
        finishVisit(id, built, cyclicInt, grayInt, doneInt, ::visitInt)
    }

    fun visitBool(id: Int) {
        if (id in doneBool) return
        if (id in grayBool) {
            cyclicBool.add(id)
            return
        }
        val c = byBoolDef[id] ?: return
        grayBool.add(id)
        val built = buildBoolSweepNode(c, id)
        finishVisit(id, built, cyclicBool, grayBool, doneBool, ::visitInt)
    }
    visitBoolRef = ::visitBool
    val intDefIds = ArrayList<Int>(byIntDef.size)
    byIntDef.forEach { id, _ -> intDefIds.add(id) }
    intDefIds.sort()
    for (id in intDefIds) visitInt(id)
    val boolDefIds = ArrayList<Int>(byBoolDef.size)
    byBoolDef.forEach { id, _ -> boolDefIds.add(id) }
    boolDefIds.sort()
    for (id in boolDefIds) visitBool(id)
    if (nodes.isEmpty()) return null
    return DefinitionalSweep(nodes)
}

private fun FlatZincCompiler.boolIdOrNull(e: FznExpr): Int? = try {
    val lit = resolveBoolLit(e)
    if (Lit.isPositive(lit)) Lit.variable(lit) else null
} catch (_: Exception) {
    null
}

private fun FlatZincCompiler.buildIntSweepNode(c: FznConstraint, definedId: Int): DefinitionalSweep.SweepNode? {
    when (c.name) {
        "bool2int" -> {
            val b = boolIdOrNull(c.args[0]) ?: return null
            return DefinitionalSweep.SweepNode.Bool2Int(definedId, b)
        }

        "array_int_element", "array_var_int_element" -> {
            val idx = varIdOrNull(c.args[0]) ?: return null
            return if (c.name == "array_int_element") {
                val consts = try {
                    evalIntConstArray(c.args[1])
                } catch (_: Exception) {
                    return null
                }
                DefinitionalSweep.SweepNode.ElementDef(
                    definedId,
                    idx,
                    null,
                    LongArray(consts.size) { consts[it].toLong() },
                    offset = 1,
                )
            } else {
                val arr = try {
                    evalIntVarArray(c.args[1])
                } catch (_: Exception) {
                    return null
                }
                DefinitionalSweep.SweepNode.ElementDef(definedId, idx, arr, null, offset = 1)
            }
        }

        else -> {
            val node = buildObjNode(c, definedId) ?: return null
            return DefinitionalSweep.SweepNode.IntDef(node, nodeInputVarIds(node).toIntArray())
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun FlatZincCompiler.buildBoolSweepNode(c: FznConstraint, definedId: Int): DefinitionalSweep.SweepNode? {
    fun cmp(opName: String, lin: Boolean): DefinitionalSweep.SweepNode? {
        val coeffs = LongArrayList()
        val vars = IntArrayList()
        var rhs: Long
        if (lin) {
            val cs = try {
                evalIntConstArray(c.args[0])
            } catch (_: Exception) {
                return null
            }
            val ops = arrayOperands(c.args[1]) ?: return null
            if (cs.size != ops.size) return null
            rhs = try {
                evalIntConst(c.args[2])
            } catch (_: Exception) {
                return null
            }
            for (k in ops.indices) {
                val o = ops[k]
                if (o.varId >= 0) {
                    coeffs.add(cs[k].toLong())
                    vars.add(o.varId)
                } else {
                    rhs -= cs[k].toLong() * o.const
                }
            }
        } else {
            val a = operandOf(c.args[0]) ?: return null
            val b = operandOf(c.args[1]) ?: return null
            rhs = 0L
            if (a.varId >= 0) {
                coeffs.add(1L)
                vars.add(a.varId)
            } else {
                rhs -= a.const
            }
            if (b.varId >= 0) {
                coeffs.add(-1L)
                vars.add(b.varId)
            } else {
                rhs += b.const
            }
        }
        val (op, finalRhs) = when (opName) {
            "eq" -> LinearOp.EQ to rhs
            "ne" -> LinearOp.NE to rhs
            "le" -> LinearOp.LE to rhs
            "lt" -> LinearOp.LE to rhs - 1
            "ge" -> LinearOp.GE to rhs
            "gt" -> LinearOp.GE to rhs + 1
            else -> return null
        }
        return DefinitionalSweep.SweepNode.CmpReif(definedId, coeffs.toLongArray(), vars.toIntArray(), finalRhs, op)
    }
    return when (c.name) {
        "int_eq_reif" -> cmp("eq", lin = false)

        "int_ne_reif" -> cmp("ne", lin = false)

        "int_le_reif" -> cmp("le", lin = false)

        "int_lt_reif" -> cmp("lt", lin = false)

        "int_ge_reif" -> cmp("ge", lin = false)

        "int_gt_reif" -> cmp("gt", lin = false)

        "int_lin_eq_reif" -> cmp("eq", lin = true)

        "int_lin_ne_reif" -> cmp("ne", lin = true)

        "int_lin_le_reif" -> cmp("le", lin = true)

        "set_in_reif" -> {
            val x = varIdOrNull(c.args[0]) ?: return null
            val values = try {
                resolveSetLiteral(c.args[1])
            } catch (_: Exception) {
                return null
            }
            DefinitionalSweep.SweepNode.SetInReif(definedId, x, LongArray(values.size) { values[it].toLong() })
        }

        "array_bool_and", "array_bool_or" -> {
            val ins = try {
                evalBoolVarArray(c.args[0])
            } catch (_: Exception) {
                return null
            }
            DefinitionalSweep.SweepNode.BoolFold(definedId, ins, isAnd = c.name == "array_bool_and")
        }

        else -> null
    }
}

private fun FlatZincCompiler.varIdOrNull(e: FznExpr): Int? {
    if (evalIntConstOrNull(e) != null) return null
    return try {
        resolveIntVar(e)
    } catch (_: Exception) {
        null
    }
}

private fun FlatZincCompiler.operandOf(e: FznExpr): Operand? {
    evalIntConstOrNull(e)?.let { return Operand.c(it) }
    return try {
        Operand.v(resolveIntVar(e))
    } catch (_: Exception) {
        null
    }
}

private fun FlatZincCompiler.arrayOperands(e: FznExpr): Array<Operand>? = when (e) {
    is FznExpr.ArrayLit -> {
        val out = ArrayList<Operand>(e.elements.size)
        for (el in e.elements) out.add(operandOf(el) ?: return null)
        out.toTypedArray()
    }

    else -> try {
        evalIntVarArray(e).map { Operand.v(it) }.toTypedArray()
    } catch (_: Exception) {
        null
    }
}

private fun FunctionalObjective.Node.inputs(): List<Operand> = when (this) {
    is FunctionalObjective.Abs -> listOf(a)
    is FunctionalObjective.Extreme -> ins.toList()
    is FunctionalObjective.Times -> listOf(a, b)
    is FunctionalObjective.Plus -> listOf(a, b)
    is FunctionalObjective.Lin -> ins.toList()
}

private fun nodeInputVarIds(node: FunctionalObjective.Node): List<Int> =
    node.inputs().filter { it.varId >= 0 }.map { it.varId }

// Translate one `defines_var` constraint into an evaluable node, or null on an unhandled shape.
private fun FlatZincCompiler.buildObjNode(c: FznConstraint, definedId: Int): FunctionalObjective.Node? {
    return when (c.name) {
        "int_abs" -> FunctionalObjective.Abs(definedId, operandOf(c.args[0]) ?: return null)

        "int_max", "int_min" -> {
            val a = operandOf(c.args[0]) ?: return null
            val b = operandOf(c.args[1]) ?: return null
            FunctionalObjective.Extreme(definedId, arrayOf(a, b), max = c.name == "int_max")
        }

        "array_int_maximum", "array_int_minimum" -> {
            val xs = arrayOperands(c.args[1]) ?: return null
            FunctionalObjective.Extreme(definedId, xs, max = c.name.endsWith("maximum"))
        }

        "int_times" -> FunctionalObjective.Times(
            definedId,
            operandOf(c.args[0]) ?: return null,
            operandOf(c.args[1]) ?: return null,
        )

        "int_plus" -> FunctionalObjective.Plus(
            definedId,
            operandOf(c.args[0]) ?: return null,
            operandOf(c.args[1]) ?: return null,
        )

        "int_lin_eq" -> buildLinNode(c, definedId)

        else -> null
    }
}

private fun FlatZincCompiler.buildLinNode(c: FznConstraint, definedId: Int): FunctionalObjective.Node? {
    val coeffs = try {
        evalIntConstArray(c.args[0])
    } catch (_: Exception) {
        return null
    }
    val vars = arrayOperands(c.args[1]) ?: return null
    val cval = try {
        evalIntConst(c.args[2])
    } catch (_: Exception) {
        return null
    }
    if (coeffs.size != vars.size) return null
    var slot = -1
    for (k in vars.indices) {
        if (vars[k].varId == definedId) {
            slot = k
            break
        }
    }
    if (slot < 0) return null
    val outCoeff = coeffs[slot].toLong()
    if (outCoeff == 0L) return null
    val ins = ArrayList<Operand>(vars.size - 1)
    val cf = LongArrayList(vars.size - 1)
    for (k in vars.indices) {
        if (k != slot) {
            ins.add(vars[k])
            cf.add(coeffs[k].toLong())
        }
    }
    return FunctionalObjective.Lin(definedId, outCoeff, cf.toLongArray(), ins.toTypedArray(), cval)
}

package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.FunctionalObjective.Operand

/** Build an exact [FunctionalObjective] from `defines_var` annotations when possible. */
internal fun FlatZincCompiler.buildFunctionalObjective(objName: String, minimize: Boolean): FunctionalObjective? {
    val objId = intVars[objName] ?: return null
    val byDef = HashMap<Int, FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val defId = varIdOrNull(ann.args.firstOrNull() ?: continue) ?: continue
        if (defId !in byDef) byDef[defId] = c
    }
    if (objId !in byDef) return null

    val nodes = ArrayList<FunctionalObjective.Node>()
    val visited = HashSet<Int>()
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
    val defined = nodes.map { it.out }.toHashSet()
    val leaves = LinkedHashSet<Int>()
    for (n in nodes) for (inId in nodeInputVarIds(n)) if (inId !in defined) leaves.add(inId)
    return FunctionalObjective(objId, minimize, nodes, leaves.toIntArray())
}

/** Build a [DefinitionalSweep] from evaluable `defines_var` constraints. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun FlatZincCompiler.buildDefinitionalSweep(): DefinitionalSweep? {
    val byIntDef = HashMap<Int, FznConstraint>()
    val byBoolDef = HashMap<Int, FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val arg = ann.args.firstOrNull() ?: continue
        val intId = varIdOrNull(arg)
        if (intId != null) {
            if (intId !in byIntDef) byIntDef[intId] = c
            continue
        }
        val boolId = boolIdOrNull(arg) ?: continue
        if (boolId !in byBoolDef) byBoolDef[boolId] = c
    }
    if (byIntDef.isEmpty() && byBoolDef.isEmpty()) return null

    val nodes = ArrayList<DefinitionalSweep.SweepNode>(byIntDef.size + byBoolDef.size)
    // Cycles cannot be topologically swept in one pass, so cycle members are left as searched vars.
    val grayInt = HashSet<Int>()
    val grayBool = HashSet<Int>()
    val doneInt = HashSet<Int>()
    val doneBool = HashSet<Int>()
    val cyclicInt = HashSet<Int>()
    val cyclicBool = HashSet<Int>()
    var visitBoolRef: ((Int) -> Unit)? = null
    fun finishVisit(
        id: Int,
        built: DefinitionalSweep.SweepNode?,
        cyclic: Set<Int>,
        gray: MutableSet<Int>,
        done: MutableSet<Int>,
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
    for (id in byIntDef.keys.sorted()) visitInt(id)
    for (id in byBoolDef.keys.sorted()) visitBool(id)
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
                DefinitionalSweep.SweepNode.ElementDef(definedId, idx, null, consts, offset = 1)
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
        val coeffs = ArrayList<Long>()
        val vars = ArrayList<Int>()
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
            DefinitionalSweep.SweepNode.SetInReif(definedId, x, values)
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

/** Translate one `defines_var` constraint into an evaluable node, or null on an unhandled shape. */
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
    val cf = ArrayList<Long>(vars.size - 1)
    for (k in vars.indices) {
        if (k != slot) {
            ins.add(vars[k])
            cf.add(coeffs[k].toLong())
        }
    }
    return FunctionalObjective.Lin(definedId, outCoeff, cf.toLongArray(), ins.toTypedArray(), cval)
}

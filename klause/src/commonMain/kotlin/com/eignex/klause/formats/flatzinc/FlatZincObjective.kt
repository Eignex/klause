package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.FunctionalObjective.Operand

/**
 * Build a [FunctionalObjective] for `solve minimize/maximize <objName>` from the MiniZinc
 * `:: defines_var(V)` annotations — the cone of constraints that functionally compute the
 * objective variable. Returns `null` (so the caller falls back to a plain
 * [com.eignex.klause.solver.objective.LinearObjective]) when the objective is a bare decision variable
 * (no cone) or the cone contains any node shape this evaluator can't reproduce exactly. The
 * returned objective is therefore always an *exact* mirror of the objective variable.
 *
 * See [FunctionalObjective] for why this matters: it gives CBLS a real gradient to the decision
 * variables of a decomposed objective, which a coefficient-on-V-only `LinearObjective` cannot.
 */
internal fun FlatZincCompiler.buildFunctionalObjective(objName: String, minimize: Boolean): FunctionalObjective? {
    val objId = intVars[objName] ?: return null
    // Each int var that some constraint functionally defines → that constraint.
    val byDef = HashMap<Int, FznConstraint>()
    for (c in model.constraints) {
        val ann = c.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val defId = varIdOrNull(ann.args.firstOrNull() ?: continue) ?: continue
        if (defId !in byDef) byDef[defId] = c
    }
    if (objId !in byDef) return null // bare decision-var objective — LinearObjective is already exact

    val nodes = ArrayList<FunctionalObjective.Node>()
    val visited = HashSet<Int>()
    var ok = true
    fun visit(id: Int) {
        if (!ok || id in visited) return
        val c = byDef[id] ?: return // leaf: a decision var (or otherwise not cone-defined)
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
        nodes.add(node) // post-order ⇒ inputs precede this node (topological)
    }
    visit(objId)
    if (!ok) return null
    // Leaf vars = input vars referenced by some node but not themselves cone-defined (the
    // decision variables a search should move to descend the objective).
    val defined = nodes.map { it.out }.toHashSet()
    val leaves = LinkedHashSet<Int>()
    for (n in nodes) for (inId in nodeInputVarIds(n)) if (inId !in defined) leaves.add(inId)
    return FunctionalObjective(objId, minimize, nodes, leaves.toIntArray())
}

/**
 * Build the model-wide [DefinitionalSweep] from every `defines_var` constraint whose shape a
 * sweep node can mirror. Int definitions reuse the [buildObjNode] algebra plus `bool2int` and
 * element access; bool definitions cover the comparison reifications (`int_*_reif`,
 * `int_lin_*_reif`), literal `set_in_reif`, and `array_bool_and`/`or`. Unhandled shapes are
 * skipped — their definitions simply stay ordinary searched factors. The visit is a post-order
 * walk across *both* value spaces (a bool reification reads int vars; `bool2int` feeds int
 * chains), so emitted nodes are topologically ordered. Returns null when nothing is buildable.
 */
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
    // Three-color DFS (gray = on the current path, black = finished): a definition re-entered
    // while gray sits on a definitional CYCLE (e.g. prize-collecting's `pos` defined via
    // element over `next`, feeding back). A cycle has no topological order — one-pass sweep
    // evaluation reads stale values forever and the per-move invariant network would mark its
    // members defined (search-excluded) while being unable to maintain them, starving the
    // move pool (measured on prize-collecting: pickMove starvation at cost ≈166 vs the
    // cost-1 walk without invariants). Dropping the re-entered definition breaks the cycle —
    // that var stays a searched factor input — and keeps the rest maximally defined.
    val grayInt = HashSet<Int>()
    val grayBool = HashSet<Int>()
    val doneInt = HashSet<Int>()
    val doneBool = HashSet<Int>()
    val cyclicInt = HashSet<Int>()
    val cyclicBool = HashSet<Int>()
    // Bool definitions read int vars and vice versa (bool2int), so the two visits are
    // mutually recursive; a local holder breaks the forward reference without shared state.
    var visitBoolRef: ((Int) -> Unit)? = null

    fun visitInt(id: Int) {
        if (id in doneInt) return
        if (id in grayInt) {
            cyclicInt.add(id)
            return
        }
        val c = byIntDef[id] ?: return // free var: a sweep input, not a node
        grayInt.add(id)
        // Unbuildable definitions stay searched factors; inputs of a built node are visited
        // before the node is appended so the surviving order is topological.
        val built = buildIntSweepNode(c, id)
        if (built != null) {
            for (inId in built.intInputs) visitInt(inId)
            for (inId in built.boolInputs) visitBoolRef?.invoke(inId)
            if (id !in cyclicInt) nodes.add(built)
        }
        grayInt.remove(id)
        doneInt.add(id)
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
        if (built != null) {
            for (inId in built.intInputs) visitInt(inId)
            for (inId in built.boolInputs) visitBoolRef?.invoke(inId)
            if (id !in cyclicBool) nodes.add(built)
        }
        grayBool.remove(id)
        doneBool.add(id)
    }
    visitBoolRef = ::visitBool
    for (id in byIntDef.keys.sorted()) visitInt(id)
    for (id in byBoolDef.keys.sorted()) visitBool(id)
    if (nodes.isEmpty()) return null
    return DefinitionalSweep(nodes)
}

/** Bool var id for `e` (a positive bool literal), else null. */
private fun FlatZincCompiler.boolIdOrNull(e: FznExpr): Int? = try {
    val lit = resolveBoolLit(e)
    if (Lit.isPositive(lit)) Lit.variable(lit) else null
} catch (_: Exception) {
    null
}

/** Int-defined sweep node: the [buildObjNode] algebra, `bool2int`, and element access. */
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

/** Bool-defined sweep node: comparison reifications, literal set membership, bool folds. */
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

/** Int var id for `e`, or null if it's a constant / bool / float / unresolvable. */
private fun FlatZincCompiler.varIdOrNull(e: FznExpr): Int? {
    if (evalIntConstOrNull(e) != null) return null
    return try {
        resolveIntVar(e)
    } catch (_: Exception) {
        null
    }
}

/** `e` as an [Operand]: a constant term or an int-var reference; null if neither. */
private fun FlatZincCompiler.operandOf(e: FznExpr): Operand? {
    evalIntConstOrNull(e)?.let { return Operand.c(it) }
    return try {
        Operand.v(resolveIntVar(e))
    } catch (_: Exception) {
        null
    }
}

/** Operands for an array argument (inline literal or named array). */
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
            // (result, array): result = max/min(array). result == definedId.
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

/** `int_lin_eq((coeffs), (vars), c)` defining [definedId]: solve `Σ coeff·var = c` for it. */
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

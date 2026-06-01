package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.FunctionalObjective
import com.eignex.klause.solver.FunctionalObjective.Operand

/**
 * Build a [FunctionalObjective] for `solve minimize/maximize <objName>` from the MiniZinc
 * `:: defines_var(V)` annotations — the cone of constraints that functionally compute the
 * objective variable. Returns `null` (so the caller falls back to a plain
 * [com.eignex.klause.solver.LinearObjective]) when the objective is a bare decision variable
 * (no cone) or the cone contains any node shape this evaluator can't reproduce exactly. The
 * returned objective is therefore always an *exact* mirror of the objective variable.
 *
 * See [FunctionalObjective] for why this matters: it gives CBLS a real gradient to the decision
 * variables of a decomposed objective, which a coefficient-on-V-only [LinearObjective] cannot.
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

/** Int var id for [e], or null if it's a constant / bool / float / unresolvable. */
private fun FlatZincCompiler.varIdOrNull(e: FznExpr): Int? {
    if (evalIntConstOrNull(e) != null) return null
    return try { resolveIntVar(e) } catch (_: Exception) { null }
}

/** [e] as an [Operand]: a constant term or an int-var reference; null if neither. */
private fun FlatZincCompiler.operandOf(e: FznExpr): Operand? {
    evalIntConstOrNull(e)?.let { return Operand.c(it) }
    return try { Operand.v(resolveIntVar(e)) } catch (_: Exception) { null }
}

/** Operands for an array argument (inline literal or named array). */
private fun FlatZincCompiler.arrayOperands(e: FznExpr): Array<Operand>? = when (e) {
    is FznExpr.ArrayLit -> {
        val out = ArrayList<Operand>(e.elements.size)
        for (el in e.elements) out.add(operandOf(el) ?: return null)
        out.toTypedArray()
    }
    else -> try { evalIntVarArray(e).map { Operand.v(it) }.toTypedArray() } catch (_: Exception) { null }
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
            operandOf(c.args[1]) ?: return null
        )
        "int_plus" -> FunctionalObjective.Plus(
            definedId,
            operandOf(c.args[0]) ?: return null,
            operandOf(c.args[1]) ?: return null
        )
        "int_lin_eq" -> buildLinNode(c, definedId)
        else -> null
    }
}

/** `int_lin_eq([coeffs], [vars], c)` defining [definedId]: solve `Σ coeff·var = c` for it. */
private fun FlatZincCompiler.buildLinNode(c: FznConstraint, definedId: Int): FunctionalObjective.Node? {
    val coeffs = try { evalIntConstArray(c.args[0]) } catch (_: Exception) { return null }
    val vars = arrayOperands(c.args[1]) ?: return null
    val cval = try { evalIntConst(c.args[2]) } catch (_: Exception) { return null }
    if (coeffs.size != vars.size) return null
    var slot = -1
    for (k in vars.indices) if (vars[k].varId == definedId) {
        slot = k
        break
    }
    if (slot < 0) return null
    val outCoeff = coeffs[slot].toLong()
    if (outCoeff == 0L) return null
    val ins = ArrayList<Operand>(vars.size - 1)
    val cf = ArrayList<Long>(vars.size - 1)
    for (k in vars.indices) if (k != slot) {
        ins.add(vars[k])
        cf.add(coeffs[k].toLong())
    }
    return FunctionalObjective.Lin(definedId, outCoeff, cf.toLongArray(), ins.toTypedArray(), cval)
}

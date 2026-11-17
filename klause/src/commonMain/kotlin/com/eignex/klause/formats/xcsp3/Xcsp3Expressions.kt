package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.xcsp3.Compiler.Builder.Companion.BOOL_FNS
import com.eignex.klause.formats.xcsp3.Compiler.Builder.Companion.REL
import com.eignex.klause.formats.xcsp3.Compiler.Builder.RelParts
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.lowering.constRelationHolds
import com.eignex.klause.lowering.intCombDiff
import com.eignex.klause.lowering.newIntProduct
import com.eignex.klause.lowering.productBounds
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.scaleIntComb
import com.eignex.klause.lowering.sumIntCombs
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinIff
import com.eignex.klause.lowering.tseitinOr
import com.eignex.klause.lowering.wideConstHolds
import com.eignex.util.collections.IntArrayList

internal fun Compiler.Builder.sum(e: XmlElement) {
    // Terms may be plain variables or expressions (e.g. `ne(x,0)` counting occurrences);
    // each expression is reified/linearized into an int var carrying its value.
    val tokens = requireNotNull(e.child("list")).textContent.splitWs()
    val termVars = tokens.flatMap { tok -> expandNames(tok).map { termVar(it, shareIndicators = true) } }
        .toIntArray()
    if (termVars.isEmpty()) throw UnsupportedXcsp3Exception("sum: empty <list>")
    val condText = requireNotNull(e.child("condition")).textContent.trim()
    val coeffsText = e.child("coeffs")?.textContent
    val constCoeffs = if (coeffsText == null) IntArray(termVars.size) { 1 } else parseInts(coeffsText)
    if (constCoeffs != null) {
        require(constCoeffs.size == termVars.size) { "sum: <coeffs> length != term count" }
        postCondition(constCoeffs, termVars, condText)
        return
    }
    // Variable coefficients (constCoeffs is null only when <coeffs> is present but not constant):
    // Σ coeff_i·term_i where each product is materialized via [Product].
    val coeffVars = requireNotNull(coeffsText).splitWs()
        .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
    require(coeffVars.size == termVars.size) { "sum: <coeffs> length != term count" }
    val products = IntArray(termVars.size) { i ->
        problemBuilder.newIntProduct(coeffVars[i], termVars[i], productBounds(coeffVars[i], termVars[i]))
    }
    postCondition(IntArray(products.size) { 1 }, products, condText)
}

/** Resolve a `<sum>` term to an int var: a declared variable, a constant, a reified
 *  relation (0/1), or an arithmetic expression bound to a fresh auxiliary. With [shareIndicators],
 *  a unit equality reads the [eqValue01] indicator of its pair; callers that keep positions distinct
 *  (`allDifferent`, `circuit`, channels) leave it off. */
internal fun Compiler.Builder.termVar(tok: String, shareIndicators: Boolean = false): Int {
    varIds[tok]?.let { return it }
    val node = FExpr.parse(tok)
    return when {
        node is FExpr.Ref -> ref(node.name)

        node is FExpr.Num -> constVar(node.value.toLong())

        node is FExpr.Call && node.fn == "eq" && node.args.size > 2 ->
            // n-ary eq as a sum term: reify the all-equal conjunction to a 0/1 int.
            litTo01(reifyRel(node))

        node is FExpr.Call && node.fn in REL -> {
            val r = relationParts(node)
            val rel = r.rel
            val noVars = when (rel) {
                is LinRelation.LongRel -> rel.vars.isEmpty()
                is LinRelation.WideRel -> rel.vars.isEmpty()
            }
            if (noVars) {
                // Constant relation: a fixed 0/1 term.
                val holds = when (rel) {
                    is LinRelation.LongRel -> constRelationHolds(r.op, rel.bound)
                    is LinRelation.WideRel -> wideConstHolds(r.op, rel.bound)
                }
                val v = if (holds) 1L else 0L
                newAuxVar(v, v)
            } else {
                // Reify the relation (wide or narrow) onto a fresh bool, then channel it to a 0/1 int.
                val shared = if (shareIndicators) unitEquality(r) else null
                if (shared != null) eqValue01(shared.first, shared.second) else litTo01(reifyRelationFactor(r))
            }
        }

        else -> {
            val lin = linear(node)
            val narrow = (lin as? IntComb.Narrow)?.lin
                ?: throw UnsupportedXcsp3Exception("cannot use an over-64-bit term as a variable")
            if (narrow.coeffs.isEmpty()) {
                newAuxVar(narrow.constant, narrow.constant)
            } else {
                materializeVar(lin)
            }
        }
    }
}

/** Parse a `<condition>` `(op, rhs)` where rhs is a constant or a variable. Returns the
 *  operator, a constant bound, and the rhs var id when the right-hand side is a variable. */
internal fun Compiler.Builder.sumCondition(text: String): Triple<LinearOp, Long, Int?> {
    val (opTok, rhs) = splitCondition(text)
    val (op, delta) = relOp(opTok) ?: throw UnsupportedXcsp3Exception("condition op '$opTok'")
    val k = rhs.toIntOrNull()
    return if (k != null) Triple(op, k.toLong() + delta, null) else Triple(op, delta.toLong(), ref(rhs))
}

/** Constrain the linear expression `Σ coeffs·vars` by a `<condition>`: a simple `(op, k|var)`
 *  relation, an `(in, lo..hi)` interval (two bounds), or an `(in, {set})` membership. */
internal fun Compiler.Builder.postCondition(coeffs: IntArray, vars: IntArray, condText: String) {
    val (opTok, operand) = splitCondition(condText)
    if (opTok == "in") {
        if (operand.startsWith("{")) {
            val m = if (coeffs.size == 1 && coeffs[0] == 1) {
                vars[0]
            } else {
                materializeVar(IntComb.Narrow(LinComb(linMap(coeffs, vars), 0L)))
            }
            val members = parseSetMembers(operand.removeSurrounding("{", "}"))
            factors.add(
                Clause(members.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) }.toIntArray()),
            )
            return
        }
        val dd = operand.indexOf("..")
        if (dd >= 0) {
            factors.add(Linear(coeffs, vars, LinearOp.GE, operand.substring(0, dd).trim().toInt()))
            factors.add(Linear(coeffs, vars, LinearOp.LE, operand.substring(dd + 2).trim().toInt()))
            return
        }
        throw UnsupportedXcsp3Exception("condition '$condText'")
    }
    val (op, k, rhsVar) = sumCondition(condText)
    if (rhsVar == null) {
        factors.add(Linear(LongArray(coeffs.size) { coeffs[it].toLong() }, vars, op, k))
    } else {
        factors.add(Linear(LongArray(coeffs.size + 1) { if (it < coeffs.size) coeffs[it].toLong() else -1L },
            vars + rhsVar, op, k))
        // `(eq, v)` makes v the sum's value: v = Σ coeffs·vars. Orienting a bare equality could
        // pick a decision var as the output, so the front-end records the definition itself.
        if (op == LinearOp.EQ) definedVars.add(rhsVar)
    }
}

/** Parse a `{...}` set body into its members, expanding `lo..hi` ranges. */
internal fun Compiler.Builder.parseSetMembers(body: String): List<Int> {
    val members = ArrayList<Int>()
    for (raw in body.split(',')) {
        val tok = raw.trim()
        if (tok.isEmpty()) continue
        val separator = tok.indexOf("..")
        if (separator < 0) {
            members.add(tok.toInt())
            continue
        }
        require(tok.indexOf("..", separator + 2) < 0) { "invalid set range '$tok'" }
        val lo = tok.substring(0, separator).trim().toInt()
        val hi = tok.substring(separator + 2).trim().toInt()
        require(lo <= hi) { "invalid set range '$tok'" }
        for (v in lo..hi) members.add(v)
    }
    return members
}

/** Coalesce parallel `coeffs`/`vars` arrays into a single variable-keyed linear map. */
internal fun Compiler.Builder.linMap(coeffs: IntArray, vars: IntArray): Map<Int, Long> {
    val m = HashMap<Int, Long>()
    for (i in vars.indices) m[vars[i]] = (m[vars[i]] ?: 0L) + coeffs[i]
    return m
}

internal fun Compiler.Builder.intension(expr: String) = intension(FExpr.parse(expr))

internal fun Compiler.Builder.intension(node: FExpr) {
    if (node is FExpr.Call && node.fn == "ne" && node.args.size == 2 &&
        (postDistanceDisequality(node.args[0], node.args[1]) ||
            postDistanceDisequality(node.args[1], node.args[0]))
    ) return
    if (node is FExpr.Call && node.fn == "eq" && node.args.size == 2) {
        // A direct Product keeps its declared output in the maintained definition cone.
        (directProduct(node.args[0], node.args[1]) ?: directProduct(node.args[1], node.args[0]))
            ?.let {
                factors.add(it)
                return
            }
        if (postDeclaredExtreme(node.args[0], node.args[1]) ||
            postDeclaredExtreme(node.args[1], node.args[0])
        ) return
    }
    // n-ary eq (x = y = z = …) is the conjunction of its consecutive pairwise equalities, posted
    // directly as top-level factors (cf. allEqual) rather than reified onto an aux literal.
    if (node is FExpr.Call && node.fn == "eq" && node.args.size > 2) {
        for (i in 0 until node.args.size - 1) {
            postRel(relationParts(node.args[i], node.args[i + 1], LinearOp.EQ, 0))
        }
        return
    }
    if (node is FExpr.Call && node.fn in REL && node.args.size == 2) {
        if (node.fn == "eq") {
            val output = node.args.firstOrNull { it is FExpr.Ref } as? FExpr.Ref
            if (output != null) definedVars.add(ref(output.name))
        }
        postRel(relationParts(node))
    } else {
        // A Boolean combination that is a plain disjunction/implication of single-variable
        // comparisons is the constraint itself (a [ComparisonClause]) — lowering it directly
        // avoids a reifying indicator + [ReifiedLinear] per comparison. Anything else keeps the
        // general Tseitin path.
        val cc = tryComparisonClause(node)
        if (cc != null) factors.add(cc) else factors.add(Clause(intArrayOf(compileBool(node))))
    }
}

private fun Compiler.Builder.postDistanceDisequality(distance: FExpr, constant: FExpr): Boolean {
    if (distance !is FExpr.Call || constant !is FExpr.Num) return false
    val operands = when {
        distance.fn == "dist" && distance.args.size == 2 -> distance.args
        distance.fn == "abs" && distance.args.size == 1 -> {
            val difference = distance.args[0] as? FExpr.Call ?: return false
            if (difference.fn != "sub" || difference.args.size != 2) return false
            difference.args
        }
        else -> return false
    }
    val arguments = operands.map { linear(it) }
    if (constant.value < 0) return true
    val difference = sumIntCombs(arguments, negateTail = true)
    val bound = IntComb.Narrow(LinComb(emptyMap(), constant.value.toLong()))
    postRel(RelParts(intCombDiff(difference, bound, 0L), LinearOp.NE))
    if (constant.value > 0) {
        postRel(RelParts(intCombDiff(scaleIntComb(difference, -1L), bound, 0L), LinearOp.NE))
    }
    return true
}

/** The `(variable, value)` of a unit equality `x = value`, whose indicator [eqValue01] shares. */
private fun Compiler.Builder.unitEquality(r: RelParts): Pair<Int, Long>? {
    val rel = r.rel as? LinRelation.LongRel ?: return null
    if (r.op != LinearOp.EQ || rel.vars.size != 1) return null
    return when (rel.coeffs[0]) {
        1L -> rel.vars[0] to rel.bound
        -1L -> if (rel.bound == Long.MIN_VALUE) null else rel.vars[0] to -rel.bound
        else -> null
    }
}

/** The reifying literal for a lowered relation, wide or narrow. */
private fun Compiler.Builder.reifyRelationFactor(r: RelParts): Int = when (val rel = r.rel) {
    is LinRelation.LongRel -> reifyLinear(rel.coeffs, rel.vars, r.op, rel.bound)
    is LinRelation.WideRel -> reifyLinear(rel.coeffs, rel.vars, r.op, rel.bound)
}

/**
 * [node] as a [ComparisonClause] when it is a disjunction (directly, or via `imp`/`not`) of at
 * least two single-variable comparisons against constants; `null` otherwise (the caller keeps the
 * Tseitin path). A single comparison stays on the cheaper [Linear] path, and a negated disjunction
 * (`¬(a ∨ b)` is a conjunction) is declined since it is not a clause.
 */
private fun Compiler.Builder.tryComparisonClause(node: FExpr): ComparisonClause? {
    val vars = IntArrayList()
    val ops = ArrayList<LinearOp>()
    val consts = ArrayList<Long>()
    if (!collectClauseLiterals(node, negated = false, vars, ops, consts)) return null
    if (vars.size < 2) return null
    return ComparisonClause(vars.toIntArray(), ops.toTypedArray(), consts.toLongArray())
}

private fun Compiler.Builder.collectClauseLiterals(
    node: FExpr,
    negated: Boolean,
    vars: IntArrayList,
    ops: MutableList<LinearOp>,
    consts: MutableList<Long>,
): Boolean {
    if (node !is FExpr.Call) return false
    when (node.fn) {
        "not" -> return node.args.size == 1 && collectClauseLiterals(node.args[0], !negated, vars, ops, consts)

        // ¬(a ∨ b) is a conjunction, not a clause.
        "or" -> return !negated && node.args.all { collectClauseLiterals(it, false, vars, ops, consts) }

        // a ⇒ b ≡ ¬a ∨ b; ¬(a ⇒ b) is a conjunction.
        "imp" ->
            return !negated && node.args.size == 2 &&
                collectClauseLiterals(node.args[0], true, vars, ops, consts) &&
                collectClauseLiterals(node.args[1], false, vars, ops, consts)

        in REL -> {
            if (node.args.size != 2) return false
            val lit = singleVarComparison(node) ?: return false
            val (v, op, c) = if (negated) negateComparison(lit) else lit
            vars.add(v)
            ops.add(op)
            consts.add(c)
            return true
        }

        else -> return false
    }
}

/** `node` as a single-variable comparison `(var, op, const)` with unit coefficient (a `-1`
 *  coefficient is folded by flipping the operator and negating the bound), or `null`. */
private fun Compiler.Builder.singleVarComparison(node: FExpr.Call): Triple<Int, LinearOp, Long>? {
    val r = relationParts(node)
    // A wide relation is not a single-variable unit comparison we can fold to a ComparisonClause;
    // decline (the caller keeps the reify path), which stays sound.
    val rel = r.rel as? LinRelation.LongRel ?: return null
    if (rel.vars.size != 1) return null
    return when (rel.coeffs[0]) {
        1L -> Triple(rel.vars[0], r.op, rel.bound)
        -1L -> Triple(rel.vars[0], r.op.flipSign(), -rel.bound)
        else -> null
    }
}

private fun LinearOp.flipSign(): LinearOp = when (this) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.EQ -> LinearOp.EQ
    LinearOp.NE -> LinearOp.NE
}

/** The complement of a single-variable comparison: `¬(x ≤ c) = x ≥ c+1`, `¬(x ≥ c) = x ≤ c−1`,
 *  `¬(x = c) = x ≠ c`, `¬(x ≠ c) = x = c`. */
private fun Compiler.Builder.negateComparison(lit: Triple<Int, LinearOp, Long>): Triple<Int, LinearOp, Long> {
    val (v, op, c) = lit
    return when (op) {
        LinearOp.LE -> Triple(v, LinearOp.GE, c + 1)
        LinearOp.GE -> Triple(v, LinearOp.LE, c - 1)
        LinearOp.EQ -> Triple(v, LinearOp.NE, c)
        LinearOp.NE -> Triple(v, LinearOp.EQ, c)
    }
}

/** The var id when [e] is a plain variable reference, else null. Emits no factors either way. */
private fun Compiler.Builder.plainVar(e: FExpr): Int? = if (e is FExpr.Ref) ref(e.name) else null

private fun Compiler.Builder.postDeclaredExtreme(output: FExpr, expression: FExpr): Boolean {
    if (output !is FExpr.Ref || expression !is FExpr.Call ||
        (expression.fn != "min" && expression.fn != "max") || expression.args.isEmpty() ||
        readsVariable(expression, output.name)
    ) return false
    val result = ref(output.name)
    val inputs = expression.args.map { materializeVar(linear(it)) }.toIntArray()
    val maximum = expression.fn == "max"
    factors.add(ArrayMinMax(result, inputs, max = maximum))
    definedVars.add(result)
    defineExtreme(result, inputs, maximum)
    return true
}

private fun Compiler.Builder.readsVariable(expression: FExpr, name: String): Boolean = when (expression) {
    is FExpr.Ref -> expression.name == name
    is FExpr.Call -> expression.args.any { readsVariable(it, name) }
    else -> false
}

private fun Compiler.Builder.directProduct(vSide: FExpr, mulSide: FExpr): Product? {
    if (mulSide !is FExpr.Call || mulSide.fn != "mul" || mulSide.args.size != 2) return null
    val v = plainVar(vSide) ?: return null
    val a = plainVar(mulSide.args[0])
    val b = plainVar(mulSide.args[1])
    if (a != null && b != null) return Product(a, b, v)
    val boolean = mulSide.args.indexOfFirst {
        it is FExpr.Call && (it.fn in REL || it.fn in BOOL_FNS || it.fn == "in" || it.fn == "notin")
    }
    if (boolean < 0) return null
    val other = when (val operand = mulSide.args[1 - boolean]) {
        is FExpr.Ref -> ref(operand.name)
        is FExpr.Num -> if (operand.value > 0) constVar(operand.value.toLong()) else return null
        else -> return null
    }
    val channel = litTo01(compileBool(mulSide.args[boolean]))
    return if (boolean == 0) Product(channel, other, v) else Product(other, channel, v)
}

internal fun Compiler.Builder.compileBool(e: FExpr): Int = when (e) {
    is FExpr.Num -> if (e.value != 0) trueLit() else Lit.negate(trueLit())

    is FExpr.Ref -> reifyRel(FExpr.Call("ge", listOf(e, FExpr.Num(1))))

    is FExpr.SetLit -> throw UnsupportedXcsp3Exception("set literal outside 'in'")

    is FExpr.Call -> when (e.fn) {
        "not" -> Lit.negate(compileBool(e.args[0]))
        "and" -> tseitinAnd(e.args.map { compileBool(it) })
        "or" -> tseitinOr(e.args.map { compileBool(it) })
        "imp" -> tseitinOr(listOf(Lit.negate(compileBool(e.args[0])), compileBool(e.args[1])))
        "iff" -> e.args.map { compileBool(it) }.reduce { a, b -> tseitinIff(a, b) }
        "xor" -> e.args.map { compileBool(it) }.reduce { a, b -> Lit.negate(tseitinIff(a, b)) }
        "in" -> memberLit(e)
        "notin" -> Lit.negate(memberLit(e))
        in REL -> reifyRel(e)
        else -> throw UnsupportedXcsp3Exception("non-boolean intension op '${e.fn}'")
    }
}

/** `in(expr, {set})`: a literal true iff the expression takes one of the set's values. */
internal fun Compiler.Builder.memberLit(e: FExpr.Call): Int {
    val values = setValues(e.args[1]) ?: throw UnsupportedXcsp3Exception("in: right side is not a set")
    val m = materializeVar(linear(e.args[0]))
    return tseitinOr(values.map { reifyLinear(intArrayOf(1), intArrayOf(m), LinearOp.EQ, it) })
}

/** The integer members of a set operand: a `{v, …}` literal or a `set(v, …)` call. Null when
 *  the operand is not a constant set (e.g. a set variable), which the caller rejects. */
internal fun Compiler.Builder.setValues(node: FExpr): List<Int>? = when {
    node is FExpr.SetLit -> node.values
    node is FExpr.Call && node.fn == "set" -> node.args.map { (it as? FExpr.Num)?.value ?: return null }
    else -> null
}

internal fun Compiler.Builder.reifyRel(node: FExpr.Call): Int {
    // n-ary eq reifies to the conjunction of its consecutive pairwise equalities.
    if (node.fn == "eq" && node.args.size > 2) {
        return tseitinAnd(
            (0 until node.args.size - 1).map {
                reifyRelationFactor(relationParts(node.args[it], node.args[it + 1], LinearOp.EQ, 0))
            },
        )
    }
    return reifyRelationFactor(relationParts(node))
}

/** Map XCSP3 relation names to linear operators and strictness deltas. */
internal fun Compiler.Builder.relOp(fn: String): Pair<LinearOp, Int>? = when (fn) {
    "le" -> LinearOp.LE to 0
    "lt" -> LinearOp.LE to -1
    "ge" -> LinearOp.GE to 0
    "gt" -> LinearOp.GE to 1
    "eq" -> LinearOp.EQ to 0
    "ne" -> LinearOp.NE to 0
    else -> null
}

/** Lower a binary relation call `rel(lhs, rhs)` to its coalesced linear components. */
internal fun Compiler.Builder.relationParts(node: FExpr.Call): RelParts {
    val (op, delta) = relOp(node.fn) ?: throw UnsupportedXcsp3Exception("relation '${node.fn}'")
    // Only eq is n-ary in XCSP3, and its callers fold it pairwise before reaching here.
    require(node.args.size == 2) { "relation '${node.fn}' expects 2 operands, got ${node.args.size}" }
    return relationParts(node.args[0], node.args[1], op, delta)
}

/** Lower `lhs op rhs` (with strictness [delta]) to a [LinRelation] over the folded difference of the
 *  two sides — a [LinRelation.WideRel] when a coefficient or bound overflows the 64-bit range, else a
 *  [LinRelation.LongRel]. When both sides share the same variable terms they cancel to an empty var
 *  list, leaving the constant relation `0 op bound`. */
internal fun Compiler.Builder.relationParts(lhs: FExpr, rhs: FExpr, op: LinearOp, delta: Int): RelParts =
    RelParts(intCombDiff(linear(lhs), linear(rhs), delta.toLong()), op)

package com.eignex.klause.formats.minizinc

/**
 * Evaluates a parsed `.ozn` program against a binding map from the FZN solver. Produces
 * the human-readable solution text MiniZinc would have produced via `solns2out`.
 *
 * Usage:
 *  1. Lex + parse the `.ozn` source into a list of [OznItem].
 *  2. Construct an [OznEvaluator] with the parsed items.
 *  3. Per solution, call [render] with the variable bindings (name → value) from the
 *     solver's FZN-format output. The returned string is the contents of one MiniZinc
 *     solution block, terminated by `----------\n`.
 *
 * Supported expression constructs: int / float / bool / string literals, identifiers,
 * ranges, array & set literals, calls (`show`, `show2d`, `show3d`, `show_int`, `fix`,
 * `array1d` / `array2d` / `array3d`, `bool2int`, `int2float`, `min`, `max`, `abs`,
 * `sum`, `product`), array subscripts, arithmetic, comparisons, conjunctions, `if-then-
 * elseif-else-endif`, `let`-bindings, and array / set comprehensions with optional
 * `where` filters. Unknown function calls throw [OznEvalException].
 */
internal class OznEvaluator(items: List<OznItem>) {
    /** Top-level bindings: from `name` to its [OznItem.VarDecl] (with optional initializer). */
    private val decls: Map<String, OznItem.VarDecl> = items
        .filterIsInstance<OznItem.VarDecl>()
        .associateBy { it.name }

    /** The single output item — there should be at most one in a well-formed .ozn. */
    private val output: OznItem.Output? = items.filterIsInstance<OznItem.Output>().singleOrNull()

    fun render(bindings: Map<String, OznValue>): String {
        val ctx = Context(bindings, HashMap())
        val sb = StringBuilder()
        if (output == null) {
            // No explicit output — render all top-level decls as `name = value;\n`.
            for ((name, decl) in decls) {
                val v = resolveDecl(decl, ctx) ?: continue
                sb.append("$name = ").append(formatValue(v)).append(";\n")
            }
        } else {
            for (e in output.items) {
                val v = eval(e, ctx)
                sb.append(stringifyForOutput(v))
            }
        }
        if (!sb.endsWith("\n")) sb.append("\n")
        sb.append("----------\n")
        return sb.toString()
    }

    /** Frame the evaluator carries around: top-level bindings + a comprehension /
     *  let-binding scope stack. Lookups consult the local scope first, then bindings. */
    private inner class Context(val bindings: Map<String, OznValue>, val locals: HashMap<String, OznValue>)

    private fun resolveDecl(decl: OznItem.VarDecl, ctx: Context): OznValue? {
        // Initializer wins; else look in the FZN bindings.
        decl.initializer?.let { return eval(it, ctx) }
        return ctx.bindings[decl.name]
    }

    private fun resolveIdent(name: String, ctx: Context): OznValue {
        ctx.locals[name]?.let { return it }
        decls[name]?.let {
            return resolveDecl(
                it,
                ctx,
            ) ?: error("decl `$name` has no value (no initializer, not in bindings)")
        }
        ctx.bindings[name]?.let { return it }
        throw OznEvalException("unresolved identifier `$name`")
    }

    private fun eval(e: OznExpr, ctx: Context): OznValue = when (e) {
        is OznExpr.IntLit -> OznValue.IntV(e.value)

        is OznExpr.FloatLit -> OznValue.FloatV(e.value)

        is OznExpr.BoolLit -> OznValue.BoolV(e.value)

        is OznExpr.StringLit -> OznValue.StringV(e.value)

        is OznExpr.Ident -> resolveIdent(e.name, ctx)

        is OznExpr.Range -> {
            val lo = (eval(e.lo, ctx) as OznValue.IntV).value.toInt()
            val hi = (eval(e.hi, ctx) as OznValue.IntV).value.toInt()
            OznValue.RangeV(lo, hi)
        }

        is OznExpr.ArrayLit -> OznValue.ArrayV(e.elements.map { eval(it, ctx) })

        is OznExpr.SetLit -> {
            // Sets in MZN-output are MZN sets — print as { a, b, c }. Internally hold
            // the unioned integer values (only ints supported in output context today).
            val vals = e.elements.flatMap { elem ->
                when (val v = eval(elem, ctx)) {
                    is OznValue.IntV -> listOf(v.value.toInt())
                    is OznValue.RangeV -> (v.lo..v.hi).toList()
                    is OznValue.SetV -> v.values.toList()
                    else -> throw OznEvalException("non-int element in set literal: $v")
                }
            }
            OznValue.SetV(vals.distinct().sorted().toIntArray())
        }

        is OznExpr.Comprehension -> evalComprehension(e, ctx)

        is OznExpr.Call -> evalCall(e, ctx)

        is OznExpr.Subscript -> evalSubscript(e, ctx)

        is OznExpr.Unary -> evalUnary(e, ctx)

        is OznExpr.Binary -> evalBinary(e, ctx)

        is OznExpr.If -> {
            var taken: OznValue? = null
            for ((cond, body) in e.branches) {
                if ((eval(cond, ctx) as OznValue.BoolV).value) {
                    taken = eval(body, ctx)
                    break
                }
            }
            taken ?: eval(e.elseExpr, ctx)
        }

        is OznExpr.Let -> {
            // Local frame: each decl's initializer (or binding) shadows. Sequential.
            val saved = ctx.locals.toMap()
            for (d in e.decls) {
                val v = resolveDecl(d, ctx) ?: throw OznEvalException("let-binding `${d.name}` has no value")
                ctx.locals[d.name] = v
            }
            val result = eval(e.body, ctx)
            ctx.locals.clear()
            ctx.locals.putAll(saved)
            result
        }
    }

    private fun evalComprehension(c: OznExpr.Comprehension, ctx: Context): OznValue {
        val out = ArrayList<OznValue>()
        fun recurse(genIdx: Int) {
            if (genIdx == c.generators.size) {
                if (c.body !is OznExpr.StringLit) {
                    // Optimisation only — fall through.
                }
                out.add(eval(c.body, ctx))
                return
            }
            val gen = c.generators[genIdx]
            val src = eval(gen.source, ctx)
            val values: List<Int> = when (src) {
                is OznValue.RangeV -> (src.lo..src.hi).toList()

                is OznValue.SetV -> src.values.toList()

                is OznValue.ArrayV -> {
                    // Iterate by element (rare but legal for `i in array`).
                    src.elements.forEachIndexed { _, v ->
                        for (name in gen.names) ctx.locals[name] = v
                        val whereOk = gen.where?.let { (eval(it, ctx) as OznValue.BoolV).value } ?: true
                        if (whereOk) recurse(genIdx + 1)
                    }
                    return
                }

                else -> throw OznEvalException("comprehension source is not iterable: $src")
            }
            // For multi-binding generators (`i, j in 1..n`), MZN gives the Cartesian
            // product over the same source. Emulate by nested loops over `names.size`.
            val name = gen.names.first()
            fun loopOver(remaining: List<String>) {
                if (remaining.isEmpty()) {
                    val whereOk = gen.where?.let { (eval(it, ctx) as OznValue.BoolV).value } ?: true
                    if (whereOk) recurse(genIdx + 1)
                    return
                }
                val n = remaining.first()
                for (v in values) {
                    ctx.locals[n] = OznValue.IntV(v.toLong())
                    loopOver(remaining.drop(1))
                }
            }
            if (gen.names.size == 1) {
                for (v in values) {
                    ctx.locals[name] = OznValue.IntV(v.toLong())
                    val whereOk = gen.where?.let { (eval(it, ctx) as OznValue.BoolV).value } ?: true
                    if (whereOk) recurse(genIdx + 1)
                }
            } else {
                loopOver(gen.names)
            }
        }
        recurse(0)
        if (c.isSet) {
            val ints = out.map { (it as OznValue.IntV).value.toInt() }.distinct().sorted().toIntArray()
            return OznValue.SetV(ints)
        }
        return OznValue.ArrayV(out)
    }

    @Suppress("ThrowsCount") // one guarded throw per malformed-call shape; splitting would obscure the dispatch
    private fun evalCall(c: OznExpr.Call, ctx: Context): OznValue {
        val args = c.args.map { eval(it, ctx) }
        return when (c.name) {
            "show", "showInt", "showFloat", "show_int", "show_float",
            "format", "format_justify_string", "showDzn", "show_dzn", "show2dDzn",
            "showJSON", "show_json",
            ->
                OznValue.StringV(stringifyForShow(args.last()))

            // `fix(x)` strips var-ness in MZN — for output evaluation it's an identity
            // function: the value is already fully concrete here.
            "fix" -> args[0]

            "show2d" -> OznValue.StringV(stringify2d(args[0]))

            "show3d" -> OznValue.StringV(stringify3d(args[0]))

            "array1d" -> {
                // array1d(range, xs): flatten xs into a 1D MZN array indexed by `range`.
                val xs = args.last() as OznValue.ArrayV
                OznValue.ArrayV(xs.elements)
            }

            "array2d" -> {
                val xs = args.last() as OznValue.ArrayV
                val r1 = args[0] as OznValue.RangeV
                val r2 = args[1] as OznValue.RangeV
                val rows = r1.size
                val cols = r2.size
                if (rows * cols != xs.elements.size) {
                    throw OznEvalException("array2d size mismatch: ${rows}x$cols != ${xs.elements.size}")
                }
                OznValue.Array2dV(xs.elements, r1, r2)
            }

            "array3d" -> {
                val xs = args.last() as OznValue.ArrayV
                OznValue.Array3dV(
                    xs.elements,
                    args[0] as OznValue.RangeV,
                    args[1] as OznValue.RangeV,
                    args[2] as OznValue.RangeV,
                )
            }

            "array4d", "array5d", "array6d" -> {
                // Higher-dim arrays — stringify as a flat MZN array. Display fidelity is
                // a stretch goal; for output rendering, treat them as 1D.
                args.last() as OznValue.ArrayV
            }

            "bool2int" -> OznValue.IntV(if ((args[0] as OznValue.BoolV).value) 1 else 0)

            "int2float" -> OznValue.FloatV((args[0] as OznValue.IntV).value.toDouble())

            "abs" -> when (val v = args[0]) {
                is OznValue.IntV -> OznValue.IntV(if (v.value < 0) -v.value else v.value)
                is OznValue.FloatV -> OznValue.FloatV(kotlin.math.abs(v.value))
                else -> throw OznEvalException("abs: unsupported arg $v")
            }

            "min" -> reduceNumeric(args, takeMin = true)

            "max" -> reduceNumeric(args, takeMin = false)

            "sum" -> {
                val list = when (val a = args[0]) {
                    is OznValue.ArrayV -> a.elements
                    is OznValue.Array2dV -> a.elements
                    is OznValue.Array3dV -> a.elements
                    else -> throw OznEvalException("sum: expected array, got $a")
                }
                if (list.isEmpty()) {
                    OznValue.IntV(0)
                } else if (list[0] is OznValue.FloatV) {
                    OznValue.FloatV(list.sumOf { (it as OznValue.FloatV).value })
                } else {
                    OznValue.IntV(list.sumOf { (it as OznValue.IntV).value })
                }
            }

            "product" -> {
                val list = (args[0] as OznValue.ArrayV).elements
                if (list.isEmpty()) {
                    OznValue.IntV(1)
                } else {
                    list.fold(OznValue.IntV(1) as OznValue) { acc, v ->
                        when {
                            acc is OznValue.IntV && v is OznValue.IntV -> OznValue.IntV(acc.value * v.value)

                            else -> {
                                val a = (acc as? OznValue.IntV)?.value?.toDouble() ?: (acc as OznValue.FloatV).value
                                val b = (v as? OznValue.IntV)?.value?.toDouble() ?: (v as OznValue.FloatV).value
                                OznValue.FloatV(a * b)
                            }
                        }
                    }
                }
            }

            "concat" -> {
                val list = (args[0] as OznValue.ArrayV).elements
                OznValue.StringV(list.joinToString("") { (it as OznValue.StringV).value })
            }

            "join" -> {
                val sep = (args[0] as OznValue.StringV).value
                val list = (args[1] as OznValue.ArrayV).elements
                OznValue.StringV(list.joinToString(sep) { stringifyForShow(it) })
            }

            else -> throw OznEvalException("unknown function `${c.name}` in .ozn output")
        }
    }

    private fun reduceNumeric(args: List<OznValue>, takeMin: Boolean): OznValue {
        val list = if (args.size == 1) {
            when (val a = args[0]) {
                is OznValue.ArrayV -> a.elements
                is OznValue.Array2dV -> a.elements
                is OznValue.Array3dV -> a.elements
                else -> args
            }
        } else {
            args
        }
        if (list.isEmpty()) throw OznEvalException("min/max: empty")
        if (list.all { it is OznValue.IntV }) {
            val v = if (takeMin) {
                list.minOf { (it as OznValue.IntV).value }
            } else {
                list.maxOf { (it as OznValue.IntV).value }
            }
            return OznValue.IntV(v)
        }
        val v = if (takeMin) {
            list.minOf { (it as? OznValue.FloatV)?.value ?: (it as OznValue.IntV).value.toDouble() }
        } else {
            list.maxOf { (it as? OznValue.FloatV)?.value ?: (it as OznValue.IntV).value.toDouble() }
        }
        return OznValue.FloatV(v)
    }

    private fun evalSubscript(e: OznExpr.Subscript, ctx: Context): OznValue {
        val tgt = eval(e.target, ctx)
        val idx = e.indices.map { (eval(it, ctx) as OznValue.IntV).value.toInt() }
        return when (tgt) {
            is OznValue.ArrayV -> tgt.elements[idx.first() - 1]

            is OznValue.Array2dV -> {
                val i = idx[0] - tgt.r1.lo
                val j = idx[1] - tgt.r2.lo
                tgt.elements[i * tgt.r2.size + j]
            }

            is OznValue.Array3dV -> {
                val i = idx[0] - tgt.r1.lo
                val j = idx[1] - tgt.r2.lo
                val k = idx[2] - tgt.r3.lo
                tgt.elements[i * tgt.r2.size * tgt.r3.size + j * tgt.r3.size + k]
            }

            else -> throw OznEvalException("subscript on non-array: $tgt")
        }
    }

    private fun evalUnary(e: OznExpr.Unary, ctx: Context): OznValue {
        val v = eval(e.operand, ctx)
        return when (e.op) {
            "-" -> when (v) {
                is OznValue.IntV -> OznValue.IntV(-v.value)
                is OznValue.FloatV -> OznValue.FloatV(-v.value)
                else -> throw OznEvalException("unary -: $v")
            }

            "+" -> v

            "not" -> OznValue.BoolV(!(v as OznValue.BoolV).value)

            else -> throw OznEvalException("unary `${e.op}`")
        }
    }

    private fun evalBinary(e: OznExpr.Binary, ctx: Context): OznValue {
        // Short-circuit logicals.
        if (e.op == "/\\" || e.op == "\\/" || e.op == "->" || e.op == "<-") {
            val l = (eval(e.left, ctx) as OznValue.BoolV).value
            return when (e.op) {
                "/\\" -> OznValue.BoolV(l && (eval(e.right, ctx) as OznValue.BoolV).value)
                "\\/" -> OznValue.BoolV(l || (eval(e.right, ctx) as OznValue.BoolV).value)
                "->" -> OznValue.BoolV(!l || (eval(e.right, ctx) as OznValue.BoolV).value)
                "<-" -> OznValue.BoolV(l || !(eval(e.right, ctx) as OznValue.BoolV).value)
                else -> error("unreachable")
            }
        }
        val l = eval(e.left, ctx)
        val r = eval(e.right, ctx)
        if (e.op == "++") {
            // String concat on strings, otherwise array concat.
            if (l is OznValue.StringV && r is OznValue.StringV) {
                return OznValue.StringV(l.value + r.value)
            }
            if (l is OznValue.ArrayV && r is OznValue.ArrayV) {
                return OznValue.ArrayV(l.elements + r.elements)
            }
            // Mixed: stringify both.
            return OznValue.StringV(stringifyForShow(l) + stringifyForShow(r))
        }
        if (e.op == "in") {
            val i = (l as OznValue.IntV).value.toInt()
            return when (r) {
                is OznValue.RangeV -> OznValue.BoolV(i in r.lo..r.hi)
                is OznValue.SetV -> OznValue.BoolV(i in r.values)
                else -> throw OznEvalException("in: rhs not a set/range")
            }
        }
        // Numeric/comparison.
        val (li, lf, isFloat) = numeric(l)
        val (ri, rf, isFloatR) = numeric(r)
        val asFloat = isFloat || isFloatR
        return when (e.op) {
            "+" -> if (asFloat) OznValue.FloatV(lf + rf) else OznValue.IntV(li + ri)
            "-" -> if (asFloat) OznValue.FloatV(lf - rf) else OznValue.IntV(li - ri)
            "*" -> if (asFloat) OznValue.FloatV(lf * rf) else OznValue.IntV(li * ri)
            "/" -> if (asFloat) OznValue.FloatV(lf / rf) else OznValue.IntV(li / ri)
            "div" -> OznValue.IntV(li / ri)
            "mod" -> OznValue.IntV(li % ri)
            "<" -> OznValue.BoolV(if (asFloat) lf < rf else li < ri)
            "<=" -> OznValue.BoolV(if (asFloat) lf <= rf else li <= ri)
            ">" -> OznValue.BoolV(if (asFloat) lf > rf else li > ri)
            ">=" -> OznValue.BoolV(if (asFloat) lf >= rf else li >= ri)
            "=" -> OznValue.BoolV(if (asFloat) lf == rf else li == ri)
            "!=" -> OznValue.BoolV(if (asFloat) lf != rf else li != ri)
            else -> throw OznEvalException("binary `${e.op}`")
        }
    }

    private data class Numeric(val long: Long, val double: Double, val isFloat: Boolean)
    private fun numeric(v: OznValue): Numeric = when (v) {
        is OznValue.IntV -> Numeric(v.value, v.value.toDouble(), false)
        is OznValue.FloatV -> Numeric(v.value.toLong(), v.value, true)
        is OznValue.BoolV -> Numeric(if (v.value) 1L else 0L, if (v.value) 1.0 else 0.0, false)
        else -> throw OznEvalException("expected numeric, got $v")
    }
}

/** Result of evaluating an .ozn expression. */
internal sealed interface OznValue {
    data class IntV(val value: Long) : OznValue
    data class FloatV(val value: Double) : OznValue
    data class BoolV(val value: Boolean) : OznValue
    data class StringV(val value: String) : OznValue
    data class RangeV(val lo: Int, val hi: Int) : OznValue {
        val size: Int get() = if (hi >= lo) hi - lo + 1 else 0
    }
    data class SetV(val values: IntArray) : OznValue {
        override fun equals(other: Any?): Boolean = other is SetV && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }
    data class ArrayV(val elements: List<OznValue>) : OznValue
    data class Array2dV(val elements: List<OznValue>, val r1: RangeV, val r2: RangeV) : OznValue
    data class Array3dV(val elements: List<OznValue>, val r1: RangeV, val r2: RangeV, val r3: RangeV) : OznValue
}

/** Render a value as it appears inside `show()` — MZN's textual form. */
private fun stringifyForShow(v: OznValue): String = when (v) {
    is OznValue.IntV -> v.value.toString()

    is OznValue.FloatV -> {
        // MZN prints floats with `.0` when integral, like `3.0`, else default.
        if (v.value == v.value.toLong().toDouble()) "${v.value.toLong()}.0" else v.value.toString()
    }

    is OznValue.BoolV -> v.value.toString()

    is OznValue.StringV -> v.value

    is OznValue.RangeV -> "${v.lo}..${v.hi}"

    is OznValue.SetV -> v.values.joinToString(", ", "{", "}")

    is OznValue.ArrayV -> v.elements.joinToString(", ", "[", "]") { stringifyForShow(it) }

    is OznValue.Array2dV -> stringify2d(v)

    is OznValue.Array3dV -> stringify3d(v)
}

private fun stringify2d(v: OznValue): String {
    val a = v as? OznValue.Array2dV
        ?: throw OznEvalException("show2d: expected 2D array, got $v")
    val sb = StringBuilder("[|")
    for (i in 0 until a.r1.size) {
        for (j in 0 until a.r2.size) {
            sb.append(stringifyForShow(a.elements[i * a.r2.size + j]))
            if (j < a.r2.size - 1) sb.append(", ")
        }
        sb.append("|")
    }
    sb.append("]")
    return sb.toString()
}

private fun stringify3d(v: OznValue): String {
    val a = v as? OznValue.Array3dV
        ?: throw OznEvalException("show3d: expected 3D array, got $v")
    return a.elements.joinToString(", ", "[", "]") { stringifyForShow(it) }
}

/** Render a value as it appears at the top level of `output [...]` — strings inline,
 *  everything else stringified via [stringifyForShow]. */
private fun stringifyForOutput(v: OznValue): String = when (v) {
    is OznValue.StringV -> v.value
    is OznValue.ArrayV -> v.elements.joinToString("") { stringifyForOutput(it) }
    else -> stringifyForShow(v)
}

private fun formatValue(v: OznValue): String = stringifyForShow(v)

class OznEvalException(message: String) : RuntimeException(message)

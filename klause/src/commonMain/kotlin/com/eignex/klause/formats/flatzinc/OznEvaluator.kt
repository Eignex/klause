package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.minizinc.*
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact
import kotlin.math.abs

/** Evaluates `.ozn` expressions against solver bindings and renders output text. */
internal class OznEvaluator(items: List<OznItem>) {
    private val decls: Map<String, OznItem.VarDecl> = items
        .filterIsInstance<OznItem.VarDecl>()
        .associateBy { it.name }

    private val output: OznItem.Output? = items.filterIsInstance<OznItem.Output>().singleOrNull()

    fun render(bindings: Map<String, OznValue>): String {
        return try {
            renderValues(bindings)
        } catch (e: CheckedLongOverflowException) {
            throw OznParseException("integer overflow in .ozn output: ${e.message}", e)
        }
    }

    private fun renderValues(bindings: Map<String, OznValue>): String {
        val ctx = Context(bindings, HashMap())
        val sb = StringBuilder()
        if (output == null) {
            for ((name, decl) in decls) {
                val v = resolveDecl(decl, ctx) ?: continue
                sb.append("$name = ").append(stringifyForShow(v)).append(";\n")
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

    private class Context(val bindings: Map<String, OznValue>, val locals: HashMap<String, OznValue>)

    private fun resolveDecl(decl: OznItem.VarDecl, ctx: Context): OznValue? {
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
        throw OznParseException("unresolved identifier `$name`")
    }

    private fun eval(e: OznExpr, ctx: Context): OznValue = when (e) {
        is OznExpr.IntLit -> OznValue.IntV(e.value)

        is OznExpr.FloatLit -> OznValue.FloatV(e.value)

        is OznExpr.BoolLit -> OznValue.BoolV(e.value)

        is OznExpr.StringLit -> OznValue.StringV(e.value)

        is OznExpr.Ident -> resolveIdent(e.name, ctx)

        is OznExpr.Range -> {
            val lo = integer(eval(e.lo, ctx))
            val hi = integer(eval(e.hi, ctx))
            OznValue.RangeV(lo, hi)
        }

        is OznExpr.ArrayLit -> {
            checkMaterialization(e.elements.size.toLong())
            OznValue.ArrayV(e.elements.map { eval(it, ctx) })
        }

        is OznExpr.SetLit -> {
            val vals = ArrayList<Long>()
            for (elem in e.elements) {
                val values = when (val v = eval(elem, ctx)) {
                    is OznValue.IntV -> listOf(v.value)
                    is OznValue.RangeV -> materializeRange(v)
                    is OznValue.SetV -> v.values.toList()
                    else -> throw OznParseException("non-int element in set literal: $v")
                }
                checkMaterialization(vals.size.toLong() + values.size)
                vals.addAll(values)
            }
            OznValue.SetV(vals.distinct().sorted().toLongArray())
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
            val saved = ctx.locals.toMap()
            for (d in e.decls) {
                val v = resolveDecl(d, ctx) ?: throw OznParseException("let-binding `${d.name}` has no value")
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
        val saved = ctx.locals.toMap()
        fun recurse(genIdx: Int) {
            if (genIdx == c.generators.size) {
                checkMaterialization(out.size.toLong() + 1)
                out.add(eval(c.body, ctx))
                return
            }
            val gen = c.generators[genIdx]
            val values = when (val src = eval(gen.source, ctx)) {
                is OznValue.RangeV -> materializeRange(src).map { OznValue.IntV(it) }
                is OznValue.SetV -> src.values.map { OznValue.IntV(it) }
                is OznValue.ArrayV -> src.elements
                else -> throw OznParseException("comprehension source is not iterable: $src")
            }
            var combinations = 1L
            repeat(gen.names.size) {
                combinations *= values.size
                checkMaterialization(combinations)
            }
            fun loopOver(nameIdx: Int) {
                if (nameIdx == gen.names.size) {
                    val whereOk = gen.where?.let { (eval(it, ctx) as OznValue.BoolV).value } ?: true
                    if (whereOk) recurse(genIdx + 1)
                    return
                }
                for (v in values) {
                    ctx.locals[gen.names[nameIdx]] = v
                    loopOver(nameIdx + 1)
                }
            }
            loopOver(0)
        }
        recurse(0)
        ctx.locals.clear()
        ctx.locals.putAll(saved)
        if (c.isSet) {
            val ints = out.map { integer(it) }.distinct().sorted().toLongArray()
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

            "fix" -> args[0]

            "show2d" -> OznValue.StringV(stringify2d(args[0]))

            "show3d" -> OznValue.StringV(stringify3d(args[0]))

            "array1d", "array2d", "array3d", "array4d", "array5d", "array6d" ->
                reshapeArray(c.name, args)

            "bool2int" -> OznValue.IntV(if ((args[0] as OznValue.BoolV).value) 1 else 0)

            "int2float" -> OznValue.FloatV((args[0] as OznValue.IntV).value.toDouble())

            "abs" -> when (val v = args[0]) {
                is OznValue.IntV -> OznValue.IntV(if (v.value < 0) subExact(0L, v.value) else v.value)
                is OznValue.FloatV -> OznValue.FloatV(abs(v.value))
                else -> throw OznParseException("abs: unsupported arg $v")
            }

            "min" -> reduceNumeric(args, takeMin = true)

            "max" -> reduceNumeric(args, takeMin = false)

            "sum" -> {
                val list = when (val a = args[0]) {
                    is OznValue.ArrayV -> a.elements
                    else -> throw OznParseException("sum: expected array, got $a")
                }
                if (list.isEmpty()) {
                    OznValue.IntV(0)
                } else if (list[0] is OznValue.FloatV) {
                    OznValue.FloatV(list.sumOf { (it as OznValue.FloatV).value })
                } else {
                    OznValue.IntV(list.fold(0L) { acc, v -> addExact(acc, integer(v)) })
                }
            }

            "product" -> {
                val list = (args[0] as OznValue.ArrayV).elements
                if (list.isEmpty()) {
                    OznValue.IntV(1)
                } else {
                    list.fold(OznValue.IntV(1) as OznValue) { acc, v ->
                        when {
                            acc is OznValue.IntV && v is OznValue.IntV -> OznValue.IntV(mulExact(acc.value, v.value))

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

            else -> throw OznParseException("unknown function `${c.name}` in .ozn output")
        }
    }

    private fun reduceNumeric(args: List<OznValue>, takeMin: Boolean): OznValue {
        val list = if (args.size == 1) {
            when (val a = args[0]) {
                is OznValue.ArrayV -> a.elements
                else -> args
            }
        } else {
            args
        }
        if (list.isEmpty()) throw OznParseException("min/max: empty")
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

    private fun reshapeArray(name: String, args: List<OznValue>): OznValue.ArrayV {
        val dimensions = name[5].digitToInt()
        if (name == "array1d" && args.size == 1) {
            return OznValue.ArrayV(arrayElements(args.single()))
        }
        if (args.size != dimensions + 1) {
            throw OznParseException("$name: expected ${dimensions + 1} arguments, got ${args.size}")
        }
        val elements = arrayElements(args.last())
        val axes = args.dropLast(1).map { indexRange(it) }
        val sizes = axes.map { it.size }
        val cardinality = arrayCardinality(sizes)
        if (cardinality != elements.size.toLong()) {
            throw OznParseException("$name size mismatch: $cardinality != ${elements.size}")
        }
        return OznValue.ArrayV(elements, axes)
    }

    private fun arrayCardinality(sizes: List<Int>): Long {
        var cardinality = if (0 in sizes) 0L else 1L
        for (size in sizes) {
            cardinality = mulExact(cardinality, size.toLong())
            if (cardinality > Int.MAX_VALUE) {
                throw OznParseException("array cardinality exceeds supported size")
            }
        }
        return cardinality
    }

    private fun indexRange(value: OznValue): OznValue.RangeV = when (value) {
        is OznValue.RangeV -> value
        is OznValue.SetV -> {
            val sorted = value.values.distinct().sorted()
            if (sorted.isEmpty()) {
                OznValue.RangeV(1, 0)
            } else {
                val range = OznValue.RangeV(sorted.first(), sorted.last())
                if (range.size != sorted.size) {
                    throw OznParseException("array index set must be contiguous")
                }
                range
            }
        }
        else -> throw OznParseException("array index set must be an integer set or range")
    }

    private fun arrayElements(value: OznValue): List<OznValue> =
        (value as? OznValue.ArrayV)?.elements ?: throw OznParseException("expected array, got $value")

    private fun integer(value: OznValue): Long =
        (value as? OznValue.IntV)?.value ?: throw OznParseException("expected integer, got $value")

    @Suppress("ThrowsCount") // Each check rejects a distinct invalid subscript before list access.
    private fun evalSubscript(e: OznExpr.Subscript, ctx: Context): OznValue {
        val target = eval(e.target, ctx) as? OznValue.ArrayV
            ?: throw OznParseException("subscript on non-array")
        if (e.indices.size != target.axes.size) {
            throw OznParseException("subscript arity mismatch: ${e.indices.size} != ${target.axes.size}")
        }
        var offset = 0L
        for ((dimension, expr) in e.indices.withIndex()) {
            val index = integer(eval(expr, ctx))
            val axis = target.axes[dimension]
            if (index !in axis.lo..axis.hi) {
                throw OznParseException("subscript $index outside axis ${dimension + 1}: ${axis.lo}..${axis.hi}")
            }
            offset = addExact(mulExact(offset, axis.size.toLong()), subExact(index, axis.lo))
        }
        if (offset !in 0L until target.elements.size.toLong()) {
            throw OznParseException("subscript offset outside array")
        }
        return target.elements[offset.toInt()]
    }

    private fun evalUnary(e: OznExpr.Unary, ctx: Context): OznValue {
        val v = eval(e.operand, ctx)
        return when (e.op) {
            "-" -> when (v) {
                is OznValue.IntV -> OznValue.IntV(subExact(0L, v.value))
                is OznValue.FloatV -> OznValue.FloatV(-v.value)
                else -> throw OznParseException("unary -: $v")
            }

            "+" -> v

            "not" -> OznValue.BoolV(!(v as OznValue.BoolV).value)

            else -> throw OznParseException("unary `${e.op}`")
        }
    }

    private fun evalBinary(e: OznExpr.Binary, ctx: Context): OznValue {
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
            if (l is OznValue.StringV && r is OznValue.StringV) {
                return OznValue.StringV(l.value + r.value)
            }
            if (l is OznValue.ArrayV && r is OznValue.ArrayV) {
                return concatenateArrays(l, r)
            }
            return OznValue.StringV(stringifyForShow(l) + stringifyForShow(r))
        }
        if (e.op == "in") {
            val i = integer(l)
            return when (r) {
                is OznValue.RangeV -> OznValue.BoolV(i in r.lo..r.hi)
                is OznValue.SetV -> OznValue.BoolV(i in r.values)
                else -> throw OznParseException("in: rhs not a set/range")
            }
        }
        val (li, lf, isFloat) = numeric(l)
        val (ri, rf, isFloatR) = numeric(r)
        val asFloat = isFloat || isFloatR
        return when (e.op) {
            "+" -> if (asFloat) OznValue.FloatV(lf + rf) else OznValue.IntV(addExact(li, ri))

            "-" -> if (asFloat) OznValue.FloatV(lf - rf) else OznValue.IntV(subExact(li, ri))

            "*" -> if (asFloat) OznValue.FloatV(lf * rf) else OznValue.IntV(mulExact(li, ri))

            // MiniZinc `/` is always float division; integer division is the separate `div` operator.
            "/" -> OznValue.FloatV(lf / rf)

            "div" -> OznValue.IntV(intDivMod(li, ri, "div"))

            "mod" -> OznValue.IntV(intDivMod(li, ri, "mod"))

            "<" -> OznValue.BoolV(if (asFloat) lf < rf else li < ri)

            "<=" -> OznValue.BoolV(if (asFloat) lf <= rf else li <= ri)

            ">" -> OznValue.BoolV(if (asFloat) lf > rf else li > ri)

            ">=" -> OznValue.BoolV(if (asFloat) lf >= rf else li >= ri)

            "=" -> OznValue.BoolV(if (asFloat) lf == rf else li == ri)

            "!=" -> OznValue.BoolV(if (asFloat) lf != rf else li != ri)

            else -> throw OznParseException("binary `${e.op}`")
        }
    }

    private fun concatenateArrays(left: OznValue.ArrayV, right: OznValue.ArrayV): OznValue.ArrayV {
        if (left.axes.size != 1 || right.axes.size != 1) {
            throw OznParseException("++: expected one-dimensional arrays")
        }
        checkMaterialization(left.elements.size.toLong() + right.elements.size)
        return OznValue.ArrayV(left.elements + right.elements)
    }

    private fun intDivMod(a: Long, b: Long, op: String): Long {
        if (b == 0L) throw OznParseException("$op by zero")
        if (op == "div" && a == Long.MIN_VALUE && b == -1L) {
            throw OznParseException("div overflows 64-bit integer")
        }
        return if (op == "div") a / b else a % b
    }

    private data class Numeric(val long: Long, val double: Double, val isFloat: Boolean)
    private fun numeric(v: OznValue): Numeric = when (v) {
        is OznValue.IntV -> Numeric(v.value, v.value.toDouble(), false)
        is OznValue.FloatV -> Numeric(v.value.toLong(), v.value, true)
        is OznValue.BoolV -> Numeric(if (v.value) 1L else 0L, if (v.value) 1.0 else 0.0, false)
        else -> throw OznParseException("expected numeric, got $v")
    }
}

/** Result of evaluating an .ozn expression. */
internal sealed interface OznValue {
    data class IntV(val value: Long) : OznValue
    data class FloatV(val value: Double) : OznValue
    data class BoolV(val value: Boolean) : OznValue
    data class StringV(val value: String) : OznValue
    data class RangeV(val lo: Long, val hi: Long) : OznValue {
        val size: Int
            get() {
                if (hi < lo) return 0
                val distance = subExact(hi, lo)
                if (distance >= Int.MAX_VALUE) {
                    throw OznParseException("range cardinality exceeds supported size")
                }
                return (distance + 1).toInt()
            }
    }
    data class SetV(val values: LongArray) : OznValue {
        override fun equals(other: Any?): Boolean = other is SetV && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }
    data class ArrayV(
        val elements: List<OznValue>,
        val axes: List<RangeV> = listOf(RangeV(1, elements.size.toLong())),
    ) : OznValue
}

// Render a value as it appears inside `show()` — MZN's textual form.
private fun stringifyForShow(v: OznValue): String = when (v) {
    is OznValue.IntV -> v.value.toString()

    is OznValue.FloatV -> {
        if (v.value == v.value.toLong().toDouble()) "${v.value.toLong()}.0" else v.value.toString()
    }

    is OznValue.BoolV -> v.value.toString()

    is OznValue.StringV -> v.value

    is OznValue.RangeV -> "${v.lo}..${v.hi}"

    is OznValue.SetV -> v.values.joinToString(", ", "{", "}")

    is OznValue.ArrayV -> if (v.axes.size == 2) {
        stringify2d(v)
    } else {
        v.elements.joinToString(", ", "[", "]") { stringifyForShow(it) }
    }
}

private fun stringify2d(v: OznValue): String {
    val a = (v as? OznValue.ArrayV)?.takeIf { it.axes.size == 2 }
        ?: throw OznParseException("show2d: expected 2D array, got $v")
    checkMaterialization(a.axes[0].size.toLong())
    val sb = StringBuilder("[|")
    for (i in 0 until a.axes[0].size) {
        for (j in 0 until a.axes[1].size) {
            sb.append(stringifyForShow(a.elements[i * a.axes[1].size + j]))
            if (j < a.axes[1].size - 1) sb.append(", ")
        }
        sb.append("|")
    }
    sb.append("]")
    return sb.toString()
}

private fun stringify3d(v: OznValue): String {
    val a = (v as? OznValue.ArrayV)?.takeIf { it.axes.size == 3 }
        ?: throw OznParseException("show3d: expected 3D array, got $v")
    return a.elements.joinToString(", ", "[", "]") { stringifyForShow(it) }
}

// Render a value in top-level `output [...]` context.
private fun stringifyForOutput(v: OznValue): String = when (v) {
    is OznValue.StringV -> v.value
    is OznValue.ArrayV -> if (v.axes.size == 1) {
        v.elements.joinToString("") { stringifyForOutput(it) }
    } else {
        stringifyForShow(v)
    }
    else -> stringifyForShow(v)
}

// Bounds eager output expansion independently of solver finite-domain storage.
private const val MAX_OZN_ELEMENTS = 1_000_000

private fun checkMaterialization(size: Long) {
    if (size > MAX_OZN_ELEMENTS) {
        throw OznParseException(".ozn materialization exceeds $MAX_OZN_ELEMENTS elements")
    }
}

private fun materializeRange(range: OznValue.RangeV): List<Long> {
    val size = range.size
    checkMaterialization(size.toLong())
    return List(size) { range.lo + it }
}

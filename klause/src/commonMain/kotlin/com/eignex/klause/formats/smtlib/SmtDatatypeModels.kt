package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.rem
import com.eignex.util.numbers.times

internal fun finishSmtGroundDatatypes(input: SmtReduction, cancellation: ExecutionControl): SmtReduction {
    val state = input.datatypes ?: return input
    val registry = state.registry.snapshot(cancellation)
    val scope = SmtLibScope(cancellation)
    val coordinates = ArrayList<SmtDatatypeCoordinate>()
    val commands = ArrayList<SExpr>()
    val constraints = ArrayList<SExpr>()
    for (expression in input.commands) {
        cancellation.polynomialSourceCheckpoint(1)
        val command = expression as SExpr.SList
        val head = command.polynomialHead()
        if (head == "declare-sort" && command.polynomialName(1) in registry.names) {
            scope.command(command)
            continue
        }
        if (head in setOf("declare-sort", "define-sort")) {
            scope.command(command)
            if (head == "declare-sort") commands += command
        } else if (head in setOf("declare-const", "declare-fun")) {
            val parameters = if (head == "declare-fun") (command.items[2] as SExpr.SList).items else emptyList()
            if (parameters.isNotEmpty()) smtUnsupported("datatype scalar erasure requires exposed ground functions")
            val sort = scope.sourceSort(command.items.last())
            val instance = registry.instance(sort)
            if (instance == null) commands += command else {
                val name = (command.items[1] as SExpr.Atom).text
                coordinates += SmtDatatypeCoordinate(name, instance.sort)
                commands += form("declare-const", SExpr.Atom(name), SExpr.Atom("Int"))
            }
        } else commands += command
    }
    val cardinalities = SmtDatatypeCardinalities(registry, parseBigInt(coordinates.size.toString())) {
        cancellation.polynomialSourceCheckpoint(1)
    }
    for ((name, sort) in coordinates) {
        cancellation.polynomialSourceCheckpoint(1)
        val cardinality = cardinalities.of(sort)
        if (cardinality != null) {
            constraints += form("assert", form("and", form("<=", SExpr.Atom("0"), SExpr.Atom(name)),
                form("<", SExpr.Atom(name), SExpr.Atom(cardinality.toString()))))
        }
    }
    val query = commands.indexOfFirst { (it as? SExpr.SList)?.polynomialHead() in
        setOf("check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
    commands.addAll(if (query < 0) commands.size else query, constraints)
    val context = SmtDatatypeModelContext(state, coordinates.toList())
    val recipes = coordinates.associate { coordinate ->
        coordinate.name to SmtReconstruction { values -> context.reconstruct(coordinate, values) }
    }
    val bindings = input.bindings.map { binding ->
        recipes[binding.declaration.name]?.let { binding.copy(reconstruction = it) } ?: binding
    }
    val check = SmtSourceCheck { values -> context.check(values) }
    return input.copy(commands = commands.toList(), bindings = bindings,
        privateBindings = input.privateBindings + recipes, checks = input.checks + check, datatypes = null)
}

internal class SmtDatatypeCardinalities(private val registry: SmtDatatypes, private val limit: BigInt? = null,
    private val step: () -> Unit) {
    private val cache = HashMap<SmtSort, BigInt?>()
    fun of(sort: SmtSort): BigInt? {
        step()
        if (sort in cache) return cache[sort]
        val cardinality = when (sort) {
            SmtSort.Bool -> parseBigInt("2")
            SmtSort.Int, SmtSort.Real -> null
            is SmtSort.BitVector -> power(parseBigInt("2"), parseBigInt(sort.width.toString()))
            is SmtSort.Array -> {
                val element = of(sort.element)
                if (element == BIG_ONE) BIG_ONE else {
                    val index = of(sort.index)
                    if (element == null || index == null) null else power(element, index)
                }
            }
            is SmtSort.Uninterpreted -> {
                val instance = registry.instance(sort)
                if (instance == null || registry.recursive(sort)) null else {
                    var sum = BIG_ZERO
                    var infinite = false
                    for (constructor in instance.constructors) {
                        var product = BIG_ONE
                        for (field in constructor.fields) {
                            val fieldCount = of(field.sort)
                            if (fieldCount == null) { infinite = true; break }
                            step()
                            product = multiply(product, fieldCount)
                        }
                        if (infinite) break
                        sum = cap(sum + product)
                    }
                    if (infinite) null else sum
                }
            }
        }
        cache[sort] = cardinality
        return cardinality
    }

    private fun power(base: BigInt, exponent: BigInt): BigInt {
        var result = BIG_ONE
        var factor = base
        var remaining = exponent
        val two = parseBigInt("2")
        while (remaining > BIG_ZERO) {
            step()
            if (remaining % two != BIG_ZERO) result = multiply(result, factor)
            remaining /= two
            if (remaining > BIG_ZERO) factor = multiply(factor, factor)
        }
        return result
    }

    private fun cap(value: BigInt): BigInt = if (limit != null && value >= limit) limit else value

    private fun multiply(left: BigInt, right: BigInt): BigInt {
        step()
        if (limit != null && right > BIG_ZERO && left > limit / right) return limit
        return cap(left * right)
    }
}

private class SmtDatatypeModelContext(private val state: SmtDatatypeReduction,
    private val coordinates: List<SmtDatatypeCoordinate>) {
    private data class Key(val sort: SmtSort.Uninterpreted, val identity: String)
    private data class Shape(val constructor: Int, val fields: List<String>)

    fun reconstruct(coordinate: SmtDatatypeCoordinate, values: SmtModelValues): SmtValue {
        val registry = state.registry.snapshot(values.cancellation)
        val group = registry.group(coordinate.sort)
        val relevant = coordinates.filter { polynomialSymbol(it.sort.name) in group }
        val keys = relevant.associate { it.name to Key(it.sort, values.integer(it.name)) }
        val shapes = LinkedHashMap<Key, Shape>()
        for (record in state.shapes) {
            values.checkpoint()
            if (polynomialSymbol(record.coordinate.sort.name) !in group) continue
            val key = keys.getValue(record.coordinate.name)
            val tag = values.integer(record.tag).toIntOrNull() ?: error("invalid datatype constructor tag")
            val alternative = record.alternatives.singleOrNull { it.constructor == tag }
                ?: error("datatype constructor tag has no matching alternative")
            val shape = Shape(tag, alternative.fields)
            val previous = shapes[key]
            if (previous == null) shapes[key] = shape
            if (previous != null) check(previous.constructor == shape.constructor) {
                "inconsistent datatype constructor tags"
            }
        }
        val reconstructed = LinkedHashMap<Key, SmtValue>()
        val generator = SmtDatatypeTermGenerator(registry, relevant.size.coerceAtLeast(1), values)
        val pending = keys.values.distinct().toMutableSet()
        while (pending.isNotEmpty()) {
            values.checkpoint()
            val ready = pending.firstOrNull { key ->
                val shape = shapes[key] ?: return@firstOrNull false
                val instance = registry.instance(key.sort) ?: error("missing datatype definition")
                instance.constructors[shape.constructor].fields.indices.all { index ->
                    val fieldSort = instance.constructors[shape.constructor].fields[index].sort
                    val fieldInstance = registry.instance(fieldSort)
                    fieldInstance == null || polynomialSymbol(fieldInstance.sort.name) !in group ||
                        keys.getValue(shape.fields[index]) in reconstructed
                }
            }
            if (ready != null) {
                val shape = shapes.getValue(ready)
                val instance = registry.instance(ready.sort) ?: error("missing datatype definition")
                val arguments = instance.constructors[shape.constructor].fields.mapIndexed { index, field ->
                    values.checkpoint()
                    val child = registry.instance(field.sort)
                    when {
                        child == null -> values.value(shape.fields[index])
                        polynomialSymbol(child.sort.name) in group ->
                            reconstructed.getValue(keys.getValue(shape.fields[index]))
                        else -> reconstruct(SmtDatatypeCoordinate(shape.fields[index], child.sort), values)
                    }
                }
                val term = SmtValue.Application(registry.constructorName(instance, shape.constructor), arguments)
                check(reconstructed.none { (key, value) ->
                    key.sort == ready.sort && key != ready && values.equivalent(value, term)
                }) {
                    "datatype identity encoding is not injective"
                }
                reconstructed[ready] = term
                pending.remove(ready)
            } else {
                val open = pending.filter { it !in shapes }
                check(open.isNotEmpty()) { "cyclic datatype reconstruction" }
                var selected: Pair<Key, SmtValue>? = null
                var selectedDepth = Int.MAX_VALUE
                for (key in open) {
                    values.checkpoint()
                    val used = reconstructed.filterKeys { it.sort == key.sort }.values
                    val term = generator.values(key.sort).firstOrNull { candidate ->
                        used.none { values.equivalent(it, candidate) }
                    }
                        ?: error("datatype cardinality exhausted")
                    val depth = depth(term)
                    if (depth < selectedDepth) { selected = key to term; selectedDepth = depth }
                }
                val (key, term) = checkNotNull(selected)
                reconstructed[key] = term
                pending.remove(key)
            }
        }
        return reconstructed.getValue(keys.getValue(coordinate.name))
    }

    fun check(values: SmtModelValues): Boolean {
        val registry = state.registry.snapshot(values.cancellation)
        for (record in state.shapes) {
            values.checkpoint()
            val value = values.value(record.coordinate.name) as? SmtValue.Application ?: return false
            val instance = registry.instance(record.coordinate.sort) ?: return false
            val tag = values.integer(record.tag).toIntOrNull() ?: return false
            val alternative = record.alternatives.singleOrNull { it.constructor == tag } ?: return false
            if (value.constructor != registry.constructorName(instance, tag)) return false
            if (value.arguments.size != alternative.fields.size) return false
            for ((index, name) in alternative.fields.withIndex()) {
                if (!values.equivalent(value.arguments[index], values.value(name))) return false
            }
        }
        for ((index, selector) in state.selectors.withIndex()) {
            values.checkpoint()
            val instance = registry.instance(selector.coordinate.sort) ?: return false
            val argument = values.value(selector.coordinate.name) as? SmtValue.Application ?: return false
            val result = values.value(selector.result)
            if (argument.constructor == registry.constructorName(instance, selector.constructor) &&
                !values.equivalent(argument.arguments[selector.field], result)) return false
            for (previous in state.selectors.take(index)) {
                values.checkpoint()
                if (previous.coordinate.sort == selector.coordinate.sort &&
                    previous.constructor == selector.constructor && previous.field == selector.field &&
                    values.equivalent(values.value(previous.coordinate.name), argument) &&
                    !values.equivalent(values.value(previous.result), result)) return false
            }
        }
        return true
    }

    private fun depth(value: SmtValue): Int = when (value) {
        is SmtValue.Application -> 1 + (value.arguments.maxOfOrNull(::depth) ?: 0)
        is SmtValue.Array -> maxOf(depth(value.default), value.entries.maxOfOrNull {
            maxOf(depth(it.first), depth(it.second))
        } ?: 0)
        else -> 0
    }
}

private class SmtDatatypeTermGenerator(private val registry: SmtDatatypes, private val limit: Int,
    private val values: SmtModelValues) {
    private val cache = HashMap<Pair<SmtSort, Int>, List<SmtValue>>()

    fun values(sort: SmtSort): List<SmtValue> {
        var depth = 0
        val finite = SmtDatatypeCardinalities(registry, parseBigInt(limit.toString())) { values.checkpoint() }.of(sort)
        while (true) {
            values.checkpoint()
            val terms = atDepth(sort, depth)
            if (terms.size >= limit || finite != null && parseBigInt(terms.size.toString()) == finite) return terms
            depth++
            if (depth == Int.MAX_VALUE) error("datatype reconstruction depth is not representable")
        }
    }

    private fun atDepth(sort: SmtSort, depth: Int): List<SmtValue> = cache.getOrPut(sort to depth) {
        values.checkpoint()
        when (sort) {
            SmtSort.Bool -> listOf(SmtValue.Boolean(false), SmtValue.Boolean(true)).take(limit)
            SmtSort.Int -> List(limit) { SmtValue.Integer(it.toString()) }
            SmtSort.Real -> List(limit) { SmtValue.Real("$it.0") }
            is SmtSort.BitVector -> {
                val result = ArrayList<SmtValue>()
                var bits = "0".repeat(sort.width)
                while (result.size < limit) {
                    values.checkpoint()
                    result += SmtValue.BitVector(bits)
                    val lastZero = bits.lastIndexOf('0')
                    if (lastZero < 0) break
                    bits = bits.take(lastZero) + '1' + "0".repeat(bits.length - lastZero - 1)
                }
                result
            }
            is SmtSort.Array -> arrays(sort, depth)
            is SmtSort.Uninterpreted -> {
                val instance = registry.instance(sort)
                if (instance == null) List(limit) { SmtValue.Uninterpreted(sort, "@klause.adt.free.$it") }
                else if (depth == 0) emptyList() else {
                    val result = ArrayList<SmtValue>()
                    for ((index, constructor) in instance.constructors.withIndex()) {
                        values.checkpoint()
                        val arguments = constructor.fields.map { atDepth(it.sort, depth - 1) }
                        for (tuple in tuples(arguments)) {
                            val term = SmtValue.Application(registry.constructorName(instance, index), tuple)
                            if (result.none { values.equivalent(it, term) }) result += term
                        }
                    }
                    result.sortedBy { termDepth(it) }.take(limit)
                }
            }
        }
    }

    private fun arrays(sort: SmtSort.Array, depth: Int): List<SmtValue> {
        val elements = atDepth(sort.element, depth)
        val indices = atDepth(sort.index, depth)
        if (elements.isEmpty() || indices.isEmpty()) return emptyList()
        val default = elements.first()
        val domains = indices.map { index -> elements.sortedBy { element ->
            if (values.equivalent(element, default)) 0 else maxOf(termDepth(index), termDepth(element))
        } }
        return tuples(domains) { position, value ->
            if (values.equivalent(value, default)) 0 else maxOf(termDepth(indices[position]), termDepth(value))
        }.map { tuple ->
            values.checkpoint()
            SmtValue.Array(sort, default, indices.indices.mapNotNull { index ->
                if (values.equivalent(tuple[index], default)) null else indices[index] to tuple[index]
            })
        }
    }

    private fun tuples(domains: List<List<SmtValue>>, cost: (Int, SmtValue) -> Int = { _, value ->
        termDepth(value)
    }): List<List<SmtValue>> {
        if (domains.any { it.isEmpty() }) return emptyList()
        val result = ArrayList<List<SmtValue>>()
        val frontier = ArrayList<List<Int>>()
        val seen = HashSet<List<Int>>()
        val initial = List(domains.size) { 0 }
        frontier += initial
        seen += initial
        fun depth(indices: List<Int>): Int = indices.indices.maxOfOrNull {
            cost(it, domains[it][indices[it]])
        } ?: 0
        while (frontier.isNotEmpty() && result.size < limit) {
            values.checkpoint()
            val next = frontier.indices.minBy { depth(frontier[it]) }
            val indices = frontier.removeAt(next)
            result += indices.indices.map { domains[it][indices[it]] }
            for (position in indices.indices) {
                values.checkpoint()
                if (indices[position] + 1 == domains[position].size) continue
                val neighbor = indices.toMutableList()
                neighbor[position]++
                if (seen.add(neighbor)) frontier += neighbor
            }
        }
        return result
    }

    private fun termDepth(value: SmtValue): Int = when (value) {
        is SmtValue.Application -> 1 + (value.arguments.maxOfOrNull(::termDepth) ?: 0)
        is SmtValue.Array -> maxOf(termDepth(value.default), value.entries.maxOfOrNull {
            maxOf(termDepth(it.first), termDepth(it.second))
        } ?: 0)
        else -> 0
    }
}

private fun SmtModelValues.equivalent(left: SmtValue, right: SmtValue): Boolean =
    equal(left, right) ?: throw SmtSourceCheckingUnavailable()
private fun form(head: String, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(SExpr.Atom(head)) + args)

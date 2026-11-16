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
import com.eignex.util.numbers.toLong
import kotlin.coroutines.cancellation.CancellationException

internal interface SmtSortCardinalities {
    fun cardinality(sort: SmtSort, cap: Int): Int?
}

internal fun SmtDatatypes.cardinality(sort: SmtSort, cap: Int, cancellation: ExecutionControl): Int? {
    fun checkpoint(units: Long = 1) {
        cancellation.charge(units)
        if (cancellation()) throw CancellationException("datatype cardinality check interrupted")
    }
    val current = snapshot(cancellation, ::checkpoint)
    if (current.instance(sort) == null) return null
    val count = SmtDatatypeCardinalities(current, parseBigInt(cap.toString()), { checkpoint() }).of(sort) ?: return cap
    return minOf(cap, count.toLong().toInt())
}

internal fun smtUnobservedSelectorInterpretation(registry: SmtDatatypes, name: String, argumentSort: SmtSort,
    resultSort: SmtSort, values: SmtModelValues): SmtValue.Function? {
    val current = registry.snapshot(values.cancellation, values::checkpoint)
    val operator = current.resolve(polynomialSymbol(name), listOf(argumentSort), resultSort) ?: return null
    if (operator.selector == null) return null
    val default = SmtDatatypeTermGenerator(current, 1, values).values(resultSort).first()
    return SmtValue.Function(listOf(argumentSort), emptyList(), default)
}

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
    val groups = datatypeModelGroups(registry, coordinates, cancellation)
    val context = SmtDatatypeModelContext(state, coordinates.toList(), groups)
    val usedNames = HashSet<String>()
    val names = ArrayDeque<SExpr>()
    input.commands.forEach(names::addLast)
    input.original?.commands?.forEach(names::addLast)
    while (names.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        when (val expression = names.removeLast()) {
            is SExpr.Atom -> usedNames += polynomialSymbol(expression.text)
            is SExpr.SList -> expression.items.forEach(names::addLast)
        }
    }
    val recipes = LinkedHashMap<String, SmtReconstruction>()
    var nextTable = 0
    for (group in coordinates.groupBy { groups.getValue(it.sort) }.values) {
        var name: String
        do { name = "@klause.adt.model.${nextTable++}" } while (!usedNames.add(name))
        val tableName = name
        recipes[tableName] = SmtReconstruction { values ->
            val model = context.reconstruct(group.first().sort, values)
            val entries = group.mapIndexed { index, coordinate ->
                values.checkpoint()
                listOf<SmtValue>(SmtValue.Integer(index.toString())) to model.getValue(coordinate.name)
            }
            SmtValue.Function(listOf(SmtSort.Int), entries, entries.first().second)
        }
        for ((index, coordinate) in group.withIndex()) {
            recipes[coordinate.name] = SmtReconstruction { values ->
                (values.value(tableName) as SmtValue.Function).entries[index].second
            }
        }
    }
    for (definition in state.selectorDefinitions) {
        cancellation.polynomialSourceCheckpoint(1)
        val constructor = definition.instance.constructors[definition.constructor]
        val field = constructor.fields[definition.field]
        val applications = state.selectors.filter { selector ->
            selector.coordinate.sort == definition.instance.sort && selector.constructor == definition.constructor &&
                selector.field == definition.field
        }.map { it.coordinate.name to it.result }
        val key = smtSelectorInterpretationKey(field.name, definition.instance.sort, field.sort)
        recipes[key] = SmtReconstruction { values ->
            val entries = applications.map { (argument, result) ->
                values.checkpoint()
                listOf(values.value(argument)) to values.value(result)
            }
            SmtValue.Function(listOf(definition.instance.sort), entries, values.value(definition.default))
        }
    }
    val bindings = input.bindings.map { binding ->
        recipes[binding.key]?.let { binding.copy(reconstruction = it) } ?: binding
    }
    val check = SmtSourceCheck { values -> context.check(values) }
    return input.copy(commands = commands.toList(), bindings = bindings,
        privateBindings = input.privateBindings + recipes, checks = input.checks + check, datatypes = null)
}

private fun datatypeModelGroups(registry: SmtDatatypes, coordinates: List<SmtDatatypeCoordinate>,
    cancellation: ExecutionControl): Map<SmtSort.Uninterpreted, Set<SmtSort.Uninterpreted>> {
    val sorts = coordinates.mapTo(LinkedHashSet()) { it.sort }
    val edges = sorts.associateWith { sort ->
        val dependencies = LinkedHashSet<SmtSort.Uninterpreted>()
        val pending = ArrayDeque<SmtSort>()
        for (constructor in registry.instance(sort)!!.constructors) {
            constructor.fields.forEach { pending.addLast(it.sort) }
        }
        while (pending.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            when (val field = pending.removeLast()) {
                is SmtSort.Array -> { pending.addLast(field.index); pending.addLast(field.element) }
                is SmtSort.Uninterpreted -> if (field in sorts) dependencies += field
                else -> Unit
            }
        }
        dependencies.toSet()
    }
    val reverse = sorts.associateWith { LinkedHashSet<SmtSort.Uninterpreted>() }
    for ((sort, dependencies) in edges) for (dependency in dependencies) reverse.getValue(dependency) += sort
    val seen = HashSet<SmtSort.Uninterpreted>()
    val order = ArrayList<SmtSort.Uninterpreted>()
    val work = ArrayDeque<Pair<SmtSort.Uninterpreted, Boolean>>()
    for (sort in sorts) {
        work.addLast(sort to false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val (current, finish) = work.removeLast()
            if (finish) order += current else if (seen.add(current)) {
                work.addLast(current to true)
                for (dependency in edges.getValue(current)) work.addLast(dependency to false)
            }
        }
    }
    seen.clear()
    val groups = LinkedHashMap<SmtSort.Uninterpreted, Set<SmtSort.Uninterpreted>>()
    val pending = ArrayDeque<SmtSort.Uninterpreted>()
    for (sort in order.asReversed()) {
        if (sort in seen) continue
        val group = LinkedHashSet<SmtSort.Uninterpreted>()
        pending.addLast(sort)
        while (pending.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val current = pending.removeLast()
            if (!seen.add(current)) continue
            group += current
            for (dependency in reverse.getValue(current)) pending.addLast(dependency)
        }
        val frozen = group.toSet()
        for (member in frozen) groups[member] = frozen
    }
    return groups.toMap()
}

internal class SmtDatatypeCardinalities(private val registry: SmtDatatypes, limit: BigInt? = null,
    private val step: () -> Unit) {
    private val limit = limit?.let { if (it < parseBigInt("2")) parseBigInt("2") else it }
    private val cache = HashMap<SmtSort, BigInt?>()
    fun of(sort: SmtSort): BigInt? {
        val pending = ArrayDeque<Pair<SmtSort, Boolean>>()
        pending.addLast(sort to false)
        while (pending.isNotEmpty()) {
            step()
            val (current, finish) = pending.removeLast()
            if (current in cache) continue
            val instance = registry.instance(current)
            if (!finish) {
                when (current) {
                    SmtSort.Bool -> cache[current] = cap(parseBigInt("2"))
                    SmtSort.Int, SmtSort.Real -> cache[current] = null
                    is SmtSort.BitVector -> cache[current] = power(parseBigInt("2"),
                        parseBigInt(current.width.toString()))
                    is SmtSort.Array -> {
                        pending.addLast(current to true)
                        pending.addLast(current.index to false)
                        pending.addLast(current.element to false)
                    }
                    is SmtSort.Uninterpreted -> if (instance == null || registry.recursive(current)) {
                        cache[current] = null
                    } else {
                        pending.addLast(current to true)
                        for (constructor in instance.constructors) for (field in constructor.fields) {
                            pending.addLast(field.sort to false)
                        }
                    }
                }
            } else cache[current] = when (current) {
                is SmtSort.Array -> {
                    val element = cache[current.element]
                    val index = cache[current.index]
                    if (element == BIG_ONE) BIG_ONE
                    else if (element == null || index == null) null else power(element, index)
                }
                is SmtSort.Uninterpreted -> {
                    var sum = BIG_ZERO
                    var infinite = false
                    for (constructor in checkNotNull(instance).constructors) {
                        var product = BIG_ONE
                        for (field in constructor.fields) {
                            val fieldCount = cache[field.sort]
                            if (fieldCount == null) { infinite = true; break }
                            step()
                            product = multiply(product, fieldCount)
                        }
                        if (infinite) break
                        sum = cap(sum + product)
                    }
                    if (infinite) null else sum
                }
                else -> error("a scalar cardinality is computed without dependencies")
            }
        }
        return cache[sort]
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
    private val coordinates: List<SmtDatatypeCoordinate>,
    private val groups: Map<SmtSort.Uninterpreted, Set<SmtSort.Uninterpreted>>) {
    private data class Key(val sort: SmtSort.Uninterpreted, val identity: String)
    private data class Shape(val constructor: Int, val fields: List<String>)

    fun reconstruct(sort: SmtSort.Uninterpreted, values: SmtModelValues): Map<String, SmtValue> {
        val registry = state.registry.snapshot(values.cancellation, values::checkpoint)
        val group = groups.getValue(sort)
        val relevant = coordinates.filter { it.sort in group }
        val keys = relevant.associate { it.name to Key(it.sort, values.integer(it.name)) }
        val shapes = LinkedHashMap<Key, Shape>()
        for (record in state.shapes) {
            values.checkpoint()
            if (record.coordinate.sort !in group) continue
            val key = keys.getValue(record.coordinate.name)
            val tag = values.integer(record.tag).toIntOrNull() ?: rejectWitness()
            val alternative = record.alternatives.singleOrNull { it.constructor == tag }
                ?: rejectWitness()
            val shape = Shape(tag, alternative.fields)
            val previous = shapes[key]
            if (previous == null) shapes[key] = shape
            if (previous != null && previous.constructor != shape.constructor) rejectWitness()
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
                    fieldInstance == null || fieldInstance.sort !in group ||
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
                        child.sort in group ->
                            reconstructed.getValue(keys.getValue(shape.fields[index]))
                        else -> values.value(shape.fields[index])
                    }
                }
                val term = SmtValue.Application(registry.constructorName(instance, shape.constructor), arguments)
                if (reconstructed.any { (key, value) ->
                    key.sort == ready.sort && key != ready && values.equivalent(value, term)
                }) rejectWitness()
                reconstructed[ready] = term
                pending.remove(ready)
            } else {
                val open = pending.filter { it !in shapes }
                if (open.isEmpty()) rejectWitness()
                var selected: Pair<Key, SmtValue>? = null
                var selectedDepth = Int.MAX_VALUE
                for (key in open) {
                    values.checkpoint()
                    val used = reconstructed.filterKeys { it.sort == key.sort }.values
                    val term = generator.values(key.sort).firstOrNull { candidate ->
                        used.none { values.equivalent(it, candidate) }
                    }
                        ?: throw SmtSourceCheckingUnavailable()
                    val depth = datatypeValueDepth(term) { values.checkpoint() }
                    if (depth < selectedDepth) { selected = key to term; selectedDepth = depth }
                }
                val (key, term) = checkNotNull(selected)
                reconstructed[key] = term
                pending.remove(key)
            }
        }
        return keys.mapValues { (_, key) -> reconstructed.getValue(key) }
    }

    private fun rejectWitness(): Nothing = throw SmtSourceWitnessRejected()

    fun check(values: SmtModelValues): Boolean {
        val registry = state.registry.snapshot(values.cancellation, values::checkpoint)
        for (coordinate in coordinates) {
            values.checkpoint()
            if (values.value(coordinate.name) !is SmtValue.Application) return false
        }
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
            if (terms.size >= limit || (finite != null && parseBigInt(terms.size.toString()) == finite)) return terms
            depth++
            if (depth == Int.MAX_VALUE) throw SmtSourceCheckingUnavailable()
        }
    }

    private fun atDepth(sort: SmtSort, depth: Int): List<SmtValue> {
        val root = sort to depth
        val pending = ArrayDeque<Pair<Pair<SmtSort, Int>, Boolean>>()
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            values.checkpoint()
            val (key, finish) = pending.removeLast()
            if (key in cache) continue
            val (current, level) = key
            val instance = registry.instance(current)
            if (!finish) {
                when (current) {
                    SmtSort.Bool -> cache[key] = listOf(SmtValue.Boolean(false), SmtValue.Boolean(true)).take(limit)
                    SmtSort.Int -> cache[key] = List(limit) { SmtValue.Integer(it.toString()) }
                    SmtSort.Real -> cache[key] = List(limit) { SmtValue.Real("$it.0") }
                    is SmtSort.BitVector -> {
                        val result = ArrayList<SmtValue>()
                        var bits = "0".repeat(current.width)
                        while (result.size < limit) {
                            values.checkpoint()
                            result += SmtValue.BitVector(bits)
                            val lastZero = bits.lastIndexOf('0')
                            if (lastZero < 0) break
                            bits = bits.take(lastZero) + '1' + "0".repeat(bits.length - lastZero - 1)
                        }
                        cache[key] = result
                    }
                    is SmtSort.Array -> {
                        pending.addLast(key to true)
                        pending.addLast((current.index to level) to false)
                        pending.addLast((current.element to level) to false)
                    }
                    is SmtSort.Uninterpreted -> when {
                        instance == null -> cache[key] = List(limit) {
                            SmtValue.Uninterpreted(current, "@klause.adt.free.$it")
                        }
                        level == 0 -> cache[key] = emptyList()
                        else -> {
                            pending.addLast(key to true)
                            for (constructor in instance.constructors) for (field in constructor.fields) {
                                pending.addLast((field.sort to level - 1) to false)
                            }
                        }
                    }
                }
            } else cache[key] = when (current) {
                is SmtSort.Array -> arrays(current, cache.getValue(current.element to level),
                    cache.getValue(current.index to level))
                is SmtSort.Uninterpreted -> {
                    val result = ArrayList<SmtValue>()
                    for ((index, constructor) in checkNotNull(instance).constructors.withIndex()) {
                        values.checkpoint()
                        val arguments = constructor.fields.map { cache.getValue(it.sort to level - 1) }
                        for (tuple in tuples(arguments)) {
                            val term = SmtValue.Application(registry.constructorName(instance, index), tuple)
                            if (result.none { values.equivalent(it, term) }) result += term
                        }
                    }
                    result.sortedBy { termDepth(it) }.take(limit)
                }
                else -> error("a scalar term domain is computed without dependencies")
            }
        }
        return cache.getValue(root)
    }

    private fun arrays(sort: SmtSort.Array, elements: List<SmtValue>, indices: List<SmtValue>): List<SmtValue> {
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

    private fun termDepth(value: SmtValue): Int = datatypeValueDepth(value) { values.checkpoint() }
}

private fun datatypeValueDepth(root: SmtValue, step: () -> Unit): Int {
    val work = ArrayDeque<Pair<SmtValue, Int>>()
    work.addLast(root to 0)
    var maximum = 0
    while (work.isNotEmpty()) {
        step()
        val (value, parent) = work.removeLast()
        when (value) {
            is SmtValue.Application -> {
                if (parent == Int.MAX_VALUE) throw SmtSourceCheckingUnavailable()
                val depth = parent + 1
                maximum = maxOf(maximum, depth)
                for (argument in value.arguments) work.addLast(argument to depth)
            }
            is SmtValue.Array -> {
                work.addLast(value.default to parent)
                for ((index, element) in value.entries) {
                    work.addLast(index to parent)
                    work.addLast(element to parent)
                }
            }
            else -> Unit
        }
    }
    return maximum
}

private fun SmtModelValues.equivalent(left: SmtValue, right: SmtValue): Boolean =
    equal(left, right) ?: throw SmtSourceCheckingUnavailable()
private fun form(head: String, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(SExpr.Atom(head)) + args)

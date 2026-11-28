package com.eignex.klause.util

// Label lookup and ordered-pool construction for typed arm catalogs: A is the arm type and R its recipe.
internal class ArmCatalog<A, R>(
    private val all: List<A>,
    private val label: (A) -> String,
    private val make: (A) -> R,
) {
    fun fromLabel(name: String): A = all.firstOrNull { label(it) == name }
        ?: error("unknown arm '$name' (have ${all.joinToString { label(it) }})")

    fun byLabel(name: String): R = make(fromLabel(name))

    fun labels(order: List<A>): List<String> = order.map(label)

    fun ranked(order: List<A>): List<R> = order.map(make)

    fun factories(order: List<A>): List<() -> R> = order.map { arm -> { make(arm) } }
}

package com.eignex.klause.solver.search

internal class SearchCoordinateOwnership(integers: List<Int>, reals: List<Int>) {
    private val integers = integers.toSet()
    private val reals = reals.toSet()

    fun model(target: SearchModel): SearchModel = object : SearchModel {
        override fun <V : Any> contribute(key: TypedSearchValueKey<V>, value: V) {
            require(accepts(key)) { "coordinate contribution belongs to a different source owner" }
            target.contribute(key, value)
        }
    }

    fun accepts(key: TypedSearchValueKey<*>): Boolean = when (key) {
        is SearchExactIntValue -> key.variable.index in integers
        is SearchExactRealValue -> key.variable.index in reals
        is SearchExactAlgebraicRealValue -> key.variable.index in reals
        is SearchExactRealExpressionValue -> key.variable.index in reals
        else -> true
    }
}

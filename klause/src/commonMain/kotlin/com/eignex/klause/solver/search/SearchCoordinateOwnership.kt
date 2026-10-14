package com.eignex.klause.solver.search

internal class SearchCoordinateOwnership(integers: List<Int>, reals: List<Int>) {
    private val integers = integers.toSet()
    private val reals = reals.toSet()

    fun model(target: SearchModel): SearchModel = object : SearchModel {
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun put(key: Any, value: Any) {
            require(accepts(key)) { "coordinate contribution belongs to a different source owner" }
            target.put(key, value)
        }
    }

    fun accepts(key: Any): Boolean = when (key) {
        is SearchIntValue -> key.variable in integers
        is SearchExactIntValue -> key.variable.index in integers
        is SearchRealValue -> key.variable in reals
        is SearchExactRealValue -> key.variable.index in reals
        is SearchExactAlgebraicRealValue -> key.variable.index in reals
        is SearchExactRealExpressionValue -> key.variable.index in reals
        else -> true
    }
}

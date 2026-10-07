package com.eignex.klause.formats.flatzinc

private val FLOAT_LP_ONLY_NAMES = setOf(
    "float_lin_le", "float_lin_eq", "float_lin_ne", "float_lin_lt",
    "float_lin_le_reif", "float_lin_eq_reif", "float_lin_ne_reif", "float_lin_lt_reif",
    "int2float", "float_eq", "float_le", "float_lt", "float_ne",
    "float_eq_reif", "float_le_reif", "float_lt_reif", "float_ne_reif",
    "float_abs", "float_min", "float_max", "array_float_element",
)

internal fun FlatZincCompiler.exactFloatNames(): Set<String> {
    val refs = HashMap<String, List<String>>()
    val arrayRefs = HashMap<String, List<List<String>>>()
    val floats = HashSet<String>()
    fun names(e: FznExpr): List<String> = when (e) {
        is FznExpr.Ident -> refs[e.name].orEmpty()
        is FznExpr.ArrayAccess -> arrayRefs[e.name]?.getOrNull(e.index - 1).orEmpty()
        is FznExpr.ArrayLit -> e.elements.flatMap(::names)
        else -> emptyList()
    }
    for (d in model.varDecls) {
        if (!d.isVar) continue
        when (val t = d.type) {
            FznType.FloatAny, is FznType.FloatRange -> {
                floats.add(d.name)
                refs[d.name] = listOf(d.name)
            }

            is FznType.Array -> if (t.element == FznType.FloatAny || t.element is FznType.FloatRange) {
                val initializers = (d.value as? FznExpr.ArrayLit)?.elements
                val members = List(t.length) { i ->
                    val source = initializers?.getOrNull(i)?.let(::names).orEmpty()
                    source.ifEmpty {
                        val name = "${d.name}[${i + 1}]"
                        floats.add(name)
                        listOf(name)
                    }
                }
                arrayRefs[d.name] = members
                refs[d.name] = members.flatten()
            }

            else -> Unit
        }
    }
    for (c in model.constraints) {
        val here = c.args.flatMap(::names)
        if (here.isEmpty()) continue
        val constantProduct = c.name == "float_times" && c.args.size == 3 &&
            (names(c.args[0]).isEmpty() || names(c.args[1]).isEmpty())
        val constantDivision = c.name == "float_div" && c.args.size == 3 && names(c.args[1]).isEmpty()
        if (c.name !in FLOAT_LP_ONLY_NAMES && !isIntFloatProduct(c, floats) && !constantProduct && !constantDivision) {
            throw UnsupportedFlatZincException(
                "`${c.name}` is unsupported by exact float lowering",
                c.line,
                c.col,
            )
        }
    }
    return floats
}

private fun FlatZincCompiler.isIntFloatProduct(c: FznConstraint, floats: Set<String>): Boolean {
    if (c.name != "float_times" || c.args.size != 3) return false
    val a = (c.args[0] as? FznExpr.Ident)?.name ?: return false
    val b = (c.args[1] as? FznExpr.Ident)?.name ?: return false
    val result = (c.args[2] as? FznExpr.Ident)?.name ?: return false
    if (result !in floats) return false
    val aInt = a in int2floatSource
    val bInt = b in int2floatSource
    if (aInt == bInt) return false
    return (if (aInt) b else a) in floats
}

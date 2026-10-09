package com.eignex.klause.formats.flatzinc

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlatZincAffineFloatImagesTest {
    @Test
    fun `fixed integer images propagate binary predicates in both directions`() {
        val predicates = mapOf("eq" to false, "ne" to true, "le" to true, "lt" to true)
        val variants = predicates.flatMap { (predicate, expected) ->
            listOf(false, true).map { reverse ->
                val operands = if (reverse) "0.5, image" else "image, 0.5"
                val name = "${predicate}_${if (reverse) "reverse" else "forward"}"
                val holds = if (reverse && predicate in listOf("le", "lt")) false else expected
                Triple(name, "constraint float_${predicate}_reif($operands, $name);", holds)
            }
        }
        val program = parseFlatZinc(
            """
            var {1, 2}: n;
            var float: raw;
            var float: image;
            ${variants.joinToString("\n") { "var bool: ${it.first};" }}
            ${variants.joinToString("\n") { it.second }}
            constraint float_lin_eq([-0.125, -1.0], [raw, image], 0.0);
            constraint int2float(n, raw);
            constraint int_eq(n, 2);
            solve satisfy;
            """.trimIndent(),
        )

        val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)

        for ((name, _, expected) in variants) {
            assertEquals(expected, deductions.boolValueOrNull(program.boolVarsByName.getValue(name)), name)
        }
    }

    @Test
    fun `affine sums propagate strict linear predicates through aliases and forward definitions`() {
        for (predicate in listOf("eq", "ne", "le", "lt")) {
            val program = parseFlatZinc(
                """
                var 1..4: n;
                var float: raw;
                var float: scaled;
                var float: sum;
                var float: alias = sum;
                array[1..2] of var float: values = [alias, scaled];
                var bool: result;
                constraint float_lin_${predicate}_reif([1.0, -1.0], values, 0.75, result);
                constraint float_lin_eq([2.0, -1.0, -1.0], [sum, scaled, raw], 0.5);
                constraint float_lin_eq([1.0, -0.5], [scaled, raw], 0.0);
                constraint int2float(n, raw);
                constraint int_eq(n, 2);
                solve satisfy;
                """.trimIndent(),
            )

            val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)

            assertEquals(
                predicate != "ne" && predicate != "lt",
                deductions.boolValueOrNull(program.boolVarsByName.getValue("result")),
            )
        }
    }

    @Test
    fun `integer substitution retains exact binary64 coefficients`() {
        val program = parseFlatZinc(
            """
            var 0..4: n;
            var float: raw;
            var float: scaled;
            var bool: result;
            constraint float_eq_reif(scaled, 0.3, result);
            constraint float_lin_eq([0.1, -1.0], [raw, scaled], 0.0);
            constraint int2float(n, raw);
            constraint int_eq(n, 3);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)

        assertEquals(false, deductions.boolValueOrNull(program.boolVarsByName.getValue("result")))
    }

    @Test
    fun `an inconsistent hard float predicate rejects fixed integer images`() {
        val program = parseFlatZinc(
            """
            var -2..2: n;
            var float: raw;
            constraint float_lt(raw, 1.0);
            constraint int2float(n, raw);
            constraint int_eq(n, 1);
            solve satisfy;
            """.trimIndent(),
        )

        assertIs<PropagationResult.Unsat>(program.problem.baked)
    }

    @Test
    fun `chained finite products propagate reified comparisons from fixed integers`() {
        val program = parseFlatZinc(
            """
            var -3..3: n;
            var float: raw;
            var float: scaled;
            var float: square;
            var float: cube;
            var bool: result;
            constraint float_eq_reif(cube, -3.375, result);
            constraint float_times(square, scaled, cube);
            constraint float_times(scaled, scaled, square);
            constraint float_lin_eq([0.5, -1.0], [raw, scaled], 0.0);
            constraint int2float(n, raw);
            constraint int_eq(n, -3);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)

        assertEquals(true, deductions.boolValueOrNull(program.boolVarsByName.getValue("result")))
    }

    @Test
    fun `constant products and float equalities preserve integer images`() {
        val program = parseFlatZinc(
            """
            var 1..3: n;
            var float: raw;
            var float: scaled;
            var float: equal;
            var bool: result;
            constraint float_lt_reif(equal, 2.0, result);
            constraint float_eq(scaled, equal);
            constraint float_times(raw, 0.5, scaled);
            constraint int2float(n, raw);
            constraint int_eq(n, 3);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)

        assertEquals(true, deductions.boolValueOrNull(program.boolVarsByName.getValue("result")))
    }
}

package com.eignex.klause.bench.metric

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareRecordsTest {
    @Test
    fun `saved record tools compare exact objectives in either direction`() {
        val tools = File("tools").takeIf { it.isDirectory } ?: File("klause-bench/tools")
        val script = """
            import sys
            sys.path.insert(0, sys.argv[1])
            import compare_records as scores
            import analyze_lab as lab
            pairs = [('9007199254740992', '9007199254740993'),
                     ('9223372036854775808', '9223372036854775809'),
                     ('1/3', '1000000000000000001/3000000000000000000')]
            for lower, higher in pairs:
                for maximize in (False, True):
                    a = dict(feasible=True, proven=False, kind='optimize', objective=1.0,
                             exactObjective=higher if maximize else lower, maximize=maximize, elapsedMs=10)
                    b = dict(a, exactObjective=lower if maximize else higher)
                    assert scores.compare(a, b, True)[:3] == (1, 1, 0)
                    assert lab.quality(a, b) == -1
            witness = dict(feasible=True, kind='optimize', exactObjective='1/3',
                           finalWitness='_objective = 0.3333333333333333;\n% klause-exact: _objective = 1/3;')
            assert lab.objective_support(witness) == 'matched'
            witness['finalWitness'] = witness['finalWitness'].replace('= 1/3;', '= 1/2;')
            assert lab.objective_support(witness) == 'mismatch'
            legacy = dict(feasible=True, proven=False, kind='optimize', objective=1.25, elapsedMs=10)
            exact = dict(legacy, exactObjective='5/4')
            assert scores.compare(legacy, exact, True)[:3] == (0, 0.5, 0.5)
            assert lab.quality(legacy, exact) == 0
        """.trimIndent()
        val process = ProcessBuilder("python3", "-c", script, tools.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
    }
}

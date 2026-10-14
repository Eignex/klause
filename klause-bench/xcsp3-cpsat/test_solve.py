import tempfile
import unittest
from pathlib import Path

from solve import VERSION, solve


class SolveTest(unittest.TestCase):
    def test_matrix_selection_uses_row_major_coordinates(self):
        for rows, columns in [(2, 3), (3, 2), (2, 2)]:
            for variable_cells in [False, True]:
                with self.subTest(rows=rows, columns=columns, variable_cells=variable_cells):
                    matrix = "".join(
                        "(" + ",".join(
                            f"cells[{r}][{c}]" if variable_cells else str(r * columns + c)
                            for c in range(columns)
                        ) + ")" for r in range(rows)
                    )
                    cells = f'<array id="cells" size="[{rows}][{columns}]">' + "".join(
                        f'<domain for="cells[{r}][{c}]"> {r * columns + c} </domain>'
                        for r in range(rows) for c in range(columns)
                    ) + "</array>" if variable_cells else ""
                    xml = f"""<instance format="XCSP3" type="CSP">
                      <variables>{cells}<var id="r">{rows - 1}</var><var id="c">{columns - 1}</var></variables>
                      <constraints><element><matrix>{matrix}</matrix><index>r c</index>
                        <value>{rows * columns - 1}</value></element></constraints>
                    </instance>"""
                    with tempfile.TemporaryDirectory() as directory:
                        path = Path(directory) / "matrix.xml"
                        path.write_text(xml)
                        result = solve(path, 1, 1)
                    self.assertEqual("FEASIBLE", result["exit"])
                    self.assertEqual(VERSION, result["version"])

    def test_matrix_selection_keeps_axis_bounds(self):
        for row, column in [(-1, 2), (0, 3), (2, 0)]:
            with self.subTest(row=row, column=column):
                xml = f"""<instance format="XCSP3" type="CSP">
                  <variables><var id="r">{row}</var><var id="c">{column}</var></variables>
                  <constraints><element><matrix>(0,0,0)(0,0,0)</matrix><index>r c</index>
                    <value>0</value></element></constraints>
                </instance>"""
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "matrix.xml"
                    path.write_text(xml)
                    result = solve(path, 1, 1)
                self.assertEqual("UNSATISFIABLE", result["exit"])

    def test_grouped_matrix_selection_preserves_conditions_and_objective(self):
        xml = """<instance format="XCSP3" type="COP">
          <variables><var id="r">1</var><var id="c">0..2</var><var id="v">0..5</var></variables>
          <constraints><group><element><matrix>(0,1,2)(3,4,5)</matrix><index>%0 %1</index>
            <condition>(eq,%2)</condition></element><args>r c v</args></group>
            <element><matrix>(0,1,2)(3,4,5)</matrix><index>r c</index><condition>(ne,4)</condition></element>
          </constraints><objectives><maximize>v</maximize></objectives>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("OPTIMAL", result["exit"])
        self.assertEqual(5, result["objective"])
        self.assertTrue(result["maximize"])

    def test_negative_tables_preserve_pairs_after_source_domain_exclusions(self):
        xml = """<instance format="XCSP3" type="COP">
          <variables><var id="x">0..5</var><var id="y">0..5</var></variables>
          <constraints>
            <extension><list>x</list><conflicts>1 3 4</conflicts></extension>
            <extension><list>y</list><conflicts>0 2 3</conflicts></extension>
            <extension><list>x y</list><conflicts>(0,0)(0,2)(1,1)(2,4)(3,3)(4,4)</conflicts></extension>
          </constraints><objectives><maximize type="sum"><list>x y</list></maximize></objectives>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "domain-conflicts.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("OPTIMAL", result["exit"])
        self.assertEqual({"x": 5, "y": 5}, result["witness"])
        self.assertEqual("10", result["exactObjective"])

    def test_negative_table_wildcards_exclude_only_matching_coordinates(self):
        xml = """<instance format="XCSP3" type="CSP">
          <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
          <constraints><extension><list>x y</list><conflicts>(0,*)(1,1)</conflicts></extension></constraints>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "wildcard-conflicts.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("FEASIBLE", result["exit"])
        self.assertEqual({"x": 1, "y": 0}, result["witness"])

    def test_all_wildcard_conflicts_remain_infeasible(self):
        xml = """<instance format="XCSP3" type="CSP">
          <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
          <constraints><extension><list>x y</list><conflicts>(*,*)</conflicts></extension></constraints>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "all-wildcards.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("UNSATISFIABLE", result["exit"])

    def test_repeated_negative_table_coordinates_preserve_source_aliases(self):
        for conflicts, expected in [("(0,1)(1,0)", "FEASIBLE"), ("(0,0)(1,1)", "UNSATISFIABLE")]:
            with self.subTest(conflicts=conflicts):
                xml = f"""<instance format="XCSP3" type="CSP">
                  <variables><var id="x">0..1</var></variables>
                  <constraints><extension><list>x x</list><conflicts>{conflicts}</conflicts></extension></constraints>
                </instance>"""
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "repeated-coordinates.xml"
                    path.write_text(xml)
                    result = solve(path, 1, 1)
                self.assertEqual(expected, result["exit"])

    def test_expression_sums_preserve_the_source_objective_and_witness(self):
        xml = """<instance format="XCSP3" type="COP">
          <variables><array id="x" size="[2]">0..2</array></variables>
          <constraints><extension><list>x[]</list><conflicts>(0,1)(1,0)</conflicts></extension>
            <sum><list>eq(x[0],0) eq(x[1],0)</list><coeffs>2 3</coeffs><condition>(ge,2)</condition></sum>
          </constraints><objectives><maximize type="sum"><list>eq(x[0],2) eq(x[1],2)</list>
            <coeffs>5 7</coeffs></maximize></objectives>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "expression-sum.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("OPTIMAL", result["exit"])
        self.assertEqual("7", result["exactObjective"])
        self.assertEqual({"x[0]": 0, "x[1]": 2}, result["witness"])

    def test_contradictory_expression_sums_remain_infeasible(self):
        xml = """<instance format="XCSP3" type="CSP">
          <variables><var id="x">0..1</var></variables>
          <constraints><sum><list>eq(x,0)</list><condition>(eq,1)</condition></sum>
            <sum><list>eq(x,1)</list><condition>(eq,1)</condition></sum></constraints>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contradictory.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("UNSATISFIABLE", result["exit"])
        self.assertIsNone(result["witness"])

    def test_witnesses_include_unconstrained_source_variables(self):
        xml = """<instance format="XCSP3" type="CSP">
          <variables><var id="x">1</var><var id="unused">4 9</var></variables>
          <constraints><intension>eq(x,1)</intension></constraints>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unconstrained.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("FEASIBLE", result["exit"])
        self.assertEqual(1, result["witness"]["x"])
        self.assertIn(result["witness"]["unused"], [4, 9])

    def test_objective_retains_integers_beyond_binary64_precision(self):
        xml = """<instance format="XCSP3" type="COP">
          <variables><var id="x">0..1</var></variables>
          <objectives><maximize type="sum"><list>x</list><coeffs>9007199254740993</coeffs></maximize></objectives>
        </instance>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "wide-objective.xml"
            path.write_text(xml)
            result = solve(path, 1, 1)
        self.assertEqual("OPTIMAL", result["exit"])
        self.assertEqual("9007199254740993", result["exactObjective"])


if __name__ == "__main__":
    unittest.main()

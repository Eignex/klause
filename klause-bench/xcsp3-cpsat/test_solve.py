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


if __name__ == "__main__":
    unittest.main()

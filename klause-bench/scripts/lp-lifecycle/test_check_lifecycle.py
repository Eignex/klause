import tempfile
import unittest
from pathlib import Path

from check_lifecycle import optimum, parse_record, summarize


def record():
    fields = ["LP_LIFECYCLE", "retained", "0", "0", "-3", "7", "-3", "7", "model",
              "ATTAINED_OPTIMUM", "-2", "-3", "4", "-2", "1", "1",
              "1", "NA", "NA", "NA", "1", "1", "1", "1", "1", "NA",
              "1", "1", "1", "1", "1", "NA", "1", "1", "0"]
    return fields


class CheckLifecycleTest(unittest.TestCase):
    def test_vertex_oracle_checks_original_bounds(self):
        self.assertEqual(optimum((-3, 7, -3, 7)), 3)
        self.assertEqual(optimum((0, 2, -1, 7)), 6)

    def test_source_witness_and_bound_are_accepted(self):
        self.assertEqual(parse_record("|".join(record()))[3], (-3, 7, -3, 7))

    def test_invalid_source_bound_is_rejected(self):
        fields = record()
        fields[10] = "-3"
        with self.assertRaises(AssertionError):
            parse_record("|".join(fields))

    def test_incomplete_campaign_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "incomplete.log"
            path.write_text("|".join(record()) + "\n")
            with self.assertRaises(AssertionError):
                summarize(path)

    def test_negative_disposal_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "invalid.log"
            path.write_text("LP_LIFECYCLE_DISPOSAL|retained|0|-1|1\n")
            with self.assertRaises(AssertionError):
                summarize(path)


if __name__ == "__main__":
    unittest.main()

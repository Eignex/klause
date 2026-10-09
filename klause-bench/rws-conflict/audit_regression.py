"""Check the archived CI control and candidate reports for the lifetime regression."""
from pathlib import Path
import xml.etree.ElementTree as ET

directory = Path(__file__).parent
name = "a late mixed clause implication survives an asserting backjump[jvm]"
for arm, expected_failure in (("control", True), ("candidate", False)):
    report = ET.parse(directory / f"ci-{arm}-PropagationSessionTest.xml").getroot()
    tests = [test for test in report.findall("testcase") if test.attrib["name"] == name]
    assert len(tests) == 1, "missing or duplicate regression"
    test = tests[0]
    assert float(test.attrib["time"]) < 0.300, "JVM regression exceeds 300 ms"
    assert (test.find("failure") is not None) == expected_failure, "regression outcome mismatch"
    assert test.find("error") is None and test.find("skipped") is None, "regression did not execute"
    if expected_failure:
        assert "expected:<false> but was:<null>" in test.find("failure").attrib["message"]
    else:
        assert report.attrib["failures"] == "0" and report.attrib["errors"] == "0"
    print(arm, "expected failure" if expected_failure else "passed", test.attrib["time"])

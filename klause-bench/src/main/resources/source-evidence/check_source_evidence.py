"""Check emitted real-box-square witnesses and bounds against saved source bytes."""
import hashlib
import json
import pathlib
import sys
from fractions import Fraction

SOURCE_KEYS = {"semantics", "kind", "lower", "upper", "coefficient", "constant", "sense"}

def inspect(source_bytes, payload):
    source = json.loads(source_bytes)
    if set(source) != SOURCE_KEYS:
        raise ValueError("unsupported source declarations")
    if source["semantics"] != "klause-ir-exact-v1" or source["kind"] != "real-box-square":
        raise ValueError("unsupported source semantics")
    if any(type(source[key]) is not int for key in ("lower", "upper", "coefficient", "constant")):
        raise ValueError("source values must be exact integers")
    if payload["sourceHash"] != hashlib.sha256(source_bytes).hexdigest():
        raise ValueError("source identity mismatch")
    if payload["sense"] != source["sense"] or source["sense"] not in {"MINIMIZE", "MAXIMIZE"}:
        raise ValueError("source objective direction mismatch")
    if payload["result"] != "Optimal" or payload["capture"] != "Checked":
        return {"status": "unavailable", "reason": "no supported captured completed claim"}
    if payload["rule"] != "original-quadratic-box" or payload["strict"]:
        raise ValueError("unsupported bound derivation")
    lower, upper = Fraction(source["lower"]), Fraction(source["upper"])
    coefficient, constant = Fraction(source["coefficient"]), Fraction(source["constant"])
    if lower > upper or coefficient <= 0:
        raise ValueError("unsupported source objective or domain")
    point, value, bound = Fraction(payload["witness"]), Fraction(payload["value"]), Fraction(payload["bound"])
    if not lower <= point <= upper:
        raise ValueError("original declaration rejects witness")
    if value != coefficient * point * point + constant:
        raise ValueError("original objective rejects value")
    square_minimum = 0 if lower <= 0 <= upper else min(lower * lower, upper * upper)
    square_maximum = max(lower * lower, upper * upper)
    extremum = square_minimum if source["sense"] == "MINIMIZE" else square_maximum
    expected = coefficient * extremum + constant
    if bound != expected or value != bound:
        raise ValueError("original box bound or attainment is false")
    return {"status": "checked", "claim": "original-witness-bound-and-attainment", "arithmetic": "python-fractions"}


def main():
    source_path, payload_path = map(pathlib.Path, sys.argv[1:3])
    source_bytes, payload_bytes = source_path.read_bytes(), payload_path.read_bytes()
    result = inspect(source_bytes, json.loads(payload_bytes))
    result.update(sourceHash=hashlib.sha256(source_bytes).hexdigest(),
                  payloadHash=hashlib.sha256(payload_bytes).hexdigest(),
                  checkerHash=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                  proofFileExport=False)
    text = json.dumps(result, sort_keys=True)
    if len(sys.argv) == 4:
        pathlib.Path(sys.argv[3]).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()

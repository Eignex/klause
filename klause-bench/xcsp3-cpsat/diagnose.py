"""Replay source-pinned witnesses and isolate reference conversion and presolve outcomes."""
import hashlib
import importlib.metadata
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

out = pathlib.Path(sys.argv[1])
sys.argv = ["diagnose"]
import cpmpy as cp
from cpmpy.solvers import CPM_ortools

namespace = {"__name__": "reference_importer_diagnostic"}
code = pathlib.Path("/solve.py").read_text()
exec(compile(code.replace("return callbacks.cpm_model", "return callbacks"), "/solve.py", "exec"), namespace)
(out / "installed-solve.py").write_text(code)
(out / "runtime.json").write_text(json.dumps({
    "python": sys.version,
    "packages": {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()},
    "importerSha256": hashlib.sha256(code.encode()).hexdigest(),
    "workers": 1, "seed": 1, "timeLimitSeconds": 10,
}, indent=2))
results = []
for index, row in enumerate(json.loads((out / "request.json").read_text())):
    source = out / f"{index}.xml"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == row["sourceSha256"]
    witness = ET.fromstring(row["witness"].removeprefix("v "))
    names = witness.findtext("list").split()
    values = [int(value) for value in witness.findtext("values").split()]
    assert len(names) == len(values) and len(set(names)) == len(names)
    assignment = dict(zip(names, values))
    for mode in ["default", "source-pins", "native-pins", "no-sat-presolve", "expanded-negative-tables"]:
        callbacks = namespace["read_model"](str(source))
        model = callbacks.cpm_model
        assert {var.id for var in callbacks.cpm_variables} == set(assignment)
        for var, cpm in callbacks.cpm_variables.items():
            cpm._value = assignment[var.id]
        violations = [{"index": i, "constraint": str(c)} for i, c in enumerate(model.constraints) if c.value() != True]
        objective = None if model.objective_ is None else int(model.objective_.value())
        if mode == "source-pins":
            model += [cpm == assignment[var.id] for var, cpm in callbacks.cpm_variables.items()]
        if mode == "expanded-negative-tables":
            constraints = []
            for constraint in model.constraints:
                if constraint.name == "negative_table":
                    variables, tuples = constraint.args
                    constraints += [cp.any([var != value for var, value in zip(variables, entry)]) for entry in tuples]
                else:
                    constraints.append(constraint)
            model.constraints = constraints
        solver = CPM_ortools(model)
        if mode == "native-pins":
            for var, native in solver._varmap.items():
                if var.name in assignment:
                    solver.ort_model.add(native == assignment[var.name])
        (out / f"{index}-{mode}.cpmodel").write_text(str(solver.ort_model.proto))
        options = {"cp_model_use_sat_presolve": False} if mode == "no-sat-presolve" else {}
        solved = solver.solve(time_limit=10, num_workers=1, random_seed=1, log_search_progress=True, **options)
        receipt = {
            "index": index, "problem": row["problem"], "sourceSha256": row["sourceSha256"], "mode": mode,
            "witnessViolations": violations, "convertedWitnessObjective": objective,
            "reportedObjective": row["objective"], "solved": bool(solved), "status": str(solver.status()),
            "solverObjective": solver.objective_value(),
            "independentProofValidation": False,
        }
        results.append(receipt)
        (out / "results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps(receipt), flush=True)

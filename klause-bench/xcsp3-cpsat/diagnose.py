"""Replay source-pinned witnesses and isolate reference conversion and presolve outcomes."""
import hashlib
import importlib.metadata
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

out = pathlib.Path(sys.argv[1])
reduce_model = len(sys.argv) == 3 and sys.argv[2] == "reduce"
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
    if reduce_model:
        callbacks = namespace["read_model"](str(source))
        original = callbacks.cpm_model
        constraints = original.constraints
        selected = list(range(len(constraints)))
        attempts = []
        def refuted(indices):
            if len(attempts) >= 120:
                return False
            model = cp.Model([constraints[i] for i in indices])
            model.solve(solver="ortools", time_limit=1, num_workers=1, random_seed=1)
            status = str(model.status())
            attempts.append({"constraints": indices, "status": status})
            (out / "reduction-attempts.json").write_text(json.dumps(attempts))
            return "UNSATISFIABLE" in status
        assert refuted(selected), "the input model is not falsely refuted without an objective"
        partitions = 2
        while len(selected) > 1 and len(attempts) < 120:
            size = (len(selected) + partitions - 1) // partitions
            changed = False
            for start in range(0, len(selected), size):
                candidate = selected[:start] + selected[start + size:]
                if refuted(candidate):
                    selected = candidate
                    partitions = max(2, partitions - 1)
                    changed = True
                    break
            if not changed:
                if partitions >= len(selected):
                    break
                partitions = min(len(selected), partitions * 2)
        pins = [cpm == assignment[var.id] for var, cpm in callbacks.cpm_variables.items()]
        for var, cpm in callbacks.cpm_variables.items():
            cpm._value = assignment[var.id]
        violations = [i for i in selected if constraints[i].value() != True]
        pinned = cp.Model([constraints[i] for i in selected] + pins)
        feasible = pinned.solve(solver="ortools", time_limit=10, num_workers=1, random_seed=1)
        receipt = {"selected": selected, "constraints": [str(constraints[i]) for i in selected],
                   "witnessViolations": violations, "pinnedFeasible": bool(feasible),
                   "pinnedStatus": str(pinned.status()), "attempts": len(attempts),
                   "sourceSha256": row["sourceSha256"], "derivedDiagnosticModel": True}
        (out / "reduction.json").write_text(json.dumps(receipt, indent=2))
        print(json.dumps(receipt), flush=True)
        continue
    for mode in ["default", "source-pins", "no-model-presolve", "keep-all", "no-probing", "no-substitution"]:
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
                if var in assignment:
                    solver.ort_model.add(native == assignment[var])
        (out / f"{index}-{mode}.cpmodel").write_text(str(solver.ort_model.proto))
        options = {
            "no-model-presolve": {"cp_model_presolve": False},
            "keep-all": {"keep_all_feasible_solutions_in_presolve": True},
            "no-probing": {"cp_model_probing_level": 0},
            "no-substitution": {"presolve_substitution_level": 0},
        }.get(mode, {})
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

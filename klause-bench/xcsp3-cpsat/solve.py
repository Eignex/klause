"""Solve one XCSP3 instance with OR-Tools cp-sat via CPMpy and print a one-line JSON verdict.

Python lives only inside this container (mirroring the vizier one); the JVM bench runs it per instance
and parses the JSON off stdout. CPMpy reads the XCSP3 `.xml` and solves it with cp-sat directly — the
same OR-Tools engine used for MiniZinc — so no XCSP3->FlatZinc conversion and nothing is written to
disk. Usage: solve.py <instance.xml> <time_limit_seconds>.

JSON fields: exit (OPTIMAL|FEASIBLE|UNSATISFIABLE|UNKNOWN|ERROR), runtime (seconds), objective (number
or null for a CSP / no incumbent), maximize (bool or null). The bench maps these to a ReferenceEntry.
"""
import json
import sys


def solve(path, time_limit, workers):
    from cpmpy.tools.xcsp3 import read_xcsp3
    model = read_xcsp3(path)
    obj = getattr(model, "objective_", None)
    maximize = None if obj is None else not bool(getattr(model, "objective_is_min", True))
    # Pin cp-sat's own workers: the bench parallelizes across instances, so each container stays
    # single-worker and N concurrent solves can't fan out to every core (memory/CPU blow-up).
    model.solve(solver="ortools", time_limit=time_limit, num_workers=workers)
    status = model.status()
    exitstatus = str(status.exitstatus).rsplit(".", 1)[-1]
    # objective_value() errors on a CSP (no objective) or when there is no incumbent yet.
    objective = None
    if obj is not None and exitstatus in ("OPTIMAL", "FEASIBLE"):
        try:
            objective = model.objective_value()
        except Exception:  # noqa: BLE001
            objective = None
    return {
        "exit": exitstatus,
        "runtime": status.runtime,
        "objective": objective,
        "maximize": maximize,
    }


def main():
    path = sys.argv[1]
    time_limit = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    # Keep the real stdout for the JSON verdict only; CPMpy/parser chatter goes to stderr.
    out = sys.stdout
    sys.stdout = sys.stderr
    try:
        result = solve(path, time_limit, workers)
    except Exception as e:  # noqa: BLE001 — a parse/solve failure is a per-instance verdict, not a crash
        result = {"exit": "ERROR", "error": f"{type(e).__name__}: {e}"}
    print(json.dumps(result), file=out)


if __name__ == "__main__":
    main()

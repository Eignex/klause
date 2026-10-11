"""Solve one XCSP3 instance with OR-Tools cp-sat via CPMpy and print a one-line JSON verdict.

Python lives only inside this container; the JVM bench runs it per instance
and parses the JSON off stdout. CPMpy reads the XCSP3 `.xml` and solves it with cp-sat directly — the
same OR-Tools engine used for MiniZinc — so no XCSP3->FlatZinc conversion and nothing is written to
disk. Usage: solve.py <instance.xml> <time_limit_seconds>.

JSON fields: exit (OPTIMAL|FEASIBLE|UNSATISFIABLE|UNKNOWN|ERROR), runtime (seconds), objective (number
or null for a CSP / no incumbent), maximize (bool or null). The bench maps these to a ReferenceEntry.
"""
import json
import sys

VERSION = "xcsp3-matrix-row-major-v1"


def read_model(path):
    import cpmpy as cp
    from cpmpy.tools.xcsp3.parser_callbacks import CallbacksCPMPy
    from pycsp3.parser.xparser import CallbackerXCSP3, ParserXCSP3

    class RowMajorCallbacks(CallbacksCPMPy):
        def ctr_element_matrix(self, matrix, i, j, condition):
            cells = cp.cpm_array([self.get_cpm_vars(row) for row in matrix])
            rows, columns = cells.shape
            row, column = self.get_cpm_vars([i, j])
            value = self.get_cpm_var(condition.right_operand())
            self.cpm_model += [row >= 0, row < rows, column >= 0, column < columns]
            # The importer uses the row count as a stride; rectangular matrices need the column count.
            selected = cp.Element(cells.flatten(), columns * row + column)
            self.cpm_model += self.eval_cpm_comp(selected, condition.operator, value)

    callbacks = RowMajorCallbacks()
    callbacks.force_exit = True
    CallbackerXCSP3(ParserXCSP3(path), callbacks).load_instance()
    return callbacks.cpm_model


def solve(path, time_limit, workers):
    model = read_model(path)
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
        "version": VERSION,
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
        result = {"exit": "ERROR", "error": f"{type(e).__name__}: {e}", "version": VERSION}
    print(json.dumps(result), file=out)


if __name__ == "__main__":
    main()

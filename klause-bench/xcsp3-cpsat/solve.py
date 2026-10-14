"""Solve one XCSP3 instance with OR-Tools cp-sat via CPMpy and emit source witnesses plus a JSON verdict.

Python lives only inside this container; the JVM bench runs it per instance
and parses the JSON off stdout. CPMpy reads the XCSP3 `.xml` and solves it with cp-sat directly — the
same OR-Tools engine used for MiniZinc — so no XCSP3->FlatZinc conversion and nothing is written to
disk. Usage: solve.py <instance.xml> <time_limit_seconds> [workers] [seed].

JSON fields: exit (OPTIMAL|FEASIBLE|UNSATISFIABLE|UNKNOWN|ERROR), runtime (seconds), objective (number
or null for a CSP / no incumbent), maximize (bool or null). The bench maps these to a ReferenceEntry.
"""
import json
import sys
from xml.sax.saxutils import escape

VERSION = "xcsp3-source-preserving-v2"


def read_model(path):
    import cpmpy as cp
    from cpmpy.tools.xcsp3.parser_callbacks import CallbacksCPMPy
    from pycsp3.parser.xparser import CallbackerXCSP3, ParserXCSP3
    from pycsp3.tools.utilities import _Star

    class SourceCallbacks(CallbacksCPMPy):
        def obj_minimize_special(self, obj_type, terms, coefficients):
            weights = [1] * len(terms) if coefficients is None else coefficients
            super().obj_minimize_special(obj_type, terms, weights)

        def obj_maximize_special(self, obj_type, terms, coefficients):
            weights = [1] * len(terms) if coefficients is None else coefficients
            super().obj_maximize_special(obj_type, terms, weights)

        def ctr_extension(self, scope, tuples, positive, flags):
            if positive:
                super().ctr_extension(scope, tuples, positive, flags)
                return
            variables = self.vars_from_node(scope)
            # A conflict excludes only its matching tuple, including wildcard coordinates.
            # Negated native tables can lose permitted pairs after source-domain exclusions.
            for entry in tuples:
                if len(entry) != len(variables):
                    raise ValueError("conflict tuple arity differs from its source scope")
                self.cpm_model += cp.any([
                    variable != value for variable, value in zip(variables, entry)
                    if not isinstance(value, _Star)
                ])

        def ctr_element_matrix(self, matrix, i, j, condition):
            cells = cp.cpm_array([self.get_cpm_vars(row) for row in matrix])
            rows, columns = cells.shape
            row, column = self.get_cpm_vars([i, j])
            value = self.get_cpm_var(condition.right_operand())
            self.cpm_model += [row >= 0, row < rows, column >= 0, column < columns]
            # The importer uses the row count as a stride; rectangular matrices need the column count.
            selected = cp.Element(cells.flatten(), columns * row + column)
            self.cpm_model += self.eval_cpm_comp(selected, condition.operator, value)

    callbacks = SourceCallbacks()
    callbacks.force_exit = True
    CallbackerXCSP3(ParserXCSP3(path), callbacks).load_instance()
    callbacks.cpm_model.source_variables = callbacks.cpm_variables
    return callbacks.cpm_model


def solve(path, time_limit, workers, seed=3):
    from cpmpy.solvers import CPM_ortools

    model = read_model(path)
    obj = getattr(model, "objective_", None)
    maximize = None if obj is None else not bool(getattr(model, "objective_is_min", True))
    # Pin cp-sat's own workers: the bench parallelizes across instances, so each container stays
    # single-worker and N concurrent solves can't fan out to every core (memory/CPU blow-up).
    solver = CPM_ortools(model)
    solver.user_vars.update(model.source_variables.values())
    solver.solver_vars(list(model.source_variables.values()))
    solver.solve(time_limit=time_limit, num_workers=workers, random_seed=seed, cp_model_presolve=True)
    status = solver.status()
    exitstatus = str(status.exitstatus).rsplit(".", 1)[-1]
    objective = None
    witness = None
    if obj is not None and exitstatus in ("OPTIMAL", "FEASIBLE"):
        objective = int(obj.value())
    if exitstatus in ("OPTIMAL", "FEASIBLE"):
        witness = {var.id: int(cpm.value()) for var, cpm in model.source_variables.items()}
    return {
        "version": VERSION,
        "exit": exitstatus,
        "runtime": status.runtime,
        "objective": objective,
        "maximize": maximize,
        "exactObjective": None if objective is None else str(objective),
        "witness": witness,
        "seed": seed,
        "workers": workers,
        "cpModelPresolve": True,
    }


def main():
    path = sys.argv[1]
    time_limit = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    seed = int(sys.argv[4]) if len(sys.argv) > 4 else 3
    # Parser chatter stays separate from the source witness and JSON verdict.
    out = sys.stdout
    sys.stdout = sys.stderr
    try:
        result = solve(path, time_limit, workers, seed)
    except Exception as e:  # noqa: BLE001 — a parse/solve failure is a per-instance verdict, not a crash
        result = {"exit": "ERROR", "error": f"{type(e).__name__}: {e}", "version": VERSION}
    if result.get("witness") is not None:
        cost = result.get("exactObjective")
        if cost is not None:
            print("o " + cost, file=out)
        names = " ".join(escape(name) for name in result["witness"])
        values = " ".join(str(value) for value in result["witness"].values())
        attribute = "" if cost is None else f' cost="{cost}"'
        print(f"v <instantiation{attribute}><list>{names}</list><values>{values}</values></instantiation>", file=out)
    print(json.dumps(result), file=out)


if __name__ == "__main__":
    main()

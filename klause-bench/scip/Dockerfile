# Reference solver for MPS (MIP). SCIP (Apache-2.0 since v8) is a strong branch-and-cut MIP solver that
# reads MPS natively, proves optima, and reports primal/dual bounds — the MIP oracle alongside cp-sat
# (MiniZinc/XCSP3), clasp (DIMACS/OPB) and z3 (SMT-LIB). The JVM bench runs it per instance
# (memory-capped, like the clasp/xcsp3 containers) and parses its status + primal bound off stdout.
# No host install: SCIP comes from conda-forge inside the image.
FROM condaforge/miniforge3:latest
RUN mamba install -y -c conda-forge scip && mamba clean -afy
# SCIP reads the instance from /dev/stdin, so the JVM pipes the .mps in (`docker run -i … -c "read
# /dev/stdin mps" -c optimize …`); no bind mount.
ENTRYPOINT ["scip"]

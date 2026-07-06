# XCSP3 cp-sat reference solver. Python lives only inside this container (mirroring the vizier one):
# CPMpy — the OR-Tools cp-sat modelling lib that won the XCSP3 2024 cp-sat track — reads the XCSP3
# `.xml` and solves it with cp-sat directly, so there is no XCSP3->FlatZinc conversion and nothing is
# written to disk. The JVM bench runs this per instance and parses the JSON verdict off stdout.
FROM python:3.12-slim
RUN pip install --no-cache-dir "cpmpy[xcsp3]"
COPY solve.py /solve.py
# Args: <instance.xml> <time_limit_seconds>. Mount the instance dir read-only; prints a JSON verdict.
ENTRYPOINT ["python", "/solve.py"]

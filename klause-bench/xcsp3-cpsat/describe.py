"""Capture the installed reference importer's code and runtime without solving a model."""
import hashlib
import importlib.metadata
import json
import pathlib
import platform
import sys

out = pathlib.Path(sys.argv[1])
sys.argv = ["describe"]
files = {}
for name in ["cpmpy.tools.xcsp3.parser_callbacks", "pycsp3.parser.xparser", "pycsp3.classes.nodes"]:
    module = __import__(name, fromlist=["x"])
    path = pathlib.Path(module.__file__)
    data = path.read_bytes()
    (out / (name + ".py")).write_bytes(data)
    files[name] = {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}
source = pathlib.Path("/solve.py")
if source.is_file():
    data = source.read_bytes()
    (out / "installed-solve.py").write_bytes(data)
    files["solve.py"] = {"sha256": hashlib.sha256(data).hexdigest()}
result = {
    "python": sys.version,
    "platform": platform.platform(),
    "packages": {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()},
    "files": files,
}
(out / "runtime.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))

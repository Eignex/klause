# OSS Vizier gRPC service — Python lives only inside this container; the JVM bench talks to it over
# gRPC (task #23). No prebuilt upstream image exists, so we build one. `jax<0.5` is required: the
# server pulls the pythia policy / jnp converters, and google-vizier 0.1.24's equinox (0.11.7) uses
# `jax.core.Primitive`, which jax >= 0.5 removed — an unconstrained install breaks the server.
FROM python:3.12-slim
RUN pip install --no-cache-dir "google-vizier[jax]" "jax<0.5" "jaxlib<0.5"
EXPOSE 6789
# Start the gRPC server on 0.0.0.0:6789 and block. The server runs on construction (background
# thread); DefaultVizierServer.endpoint reports host:port. sqlite study store under /tmp (writable).
CMD ["python", "-c", "from vizier.service import servers; import threading; s = servers.DefaultVizierServer(host='0.0.0.0', database_url='sqlite:////tmp/vizier.db', port=6789); print('vizier listening on', s.endpoint, flush=True); threading.Event().wait()"]

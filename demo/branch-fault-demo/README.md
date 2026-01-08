# Branch fault web demo

This module wires together the `branch-fault-support` fork of `powsybl-open-loadflow`
and the IEC 60909 short-circuit provider from `powsybl-open-sc`. It exposes a
tiny Spring Boot UI that:

- loads the IEEE‑14 benchmark using `IeeeCdfNetworkFactory`,
- runs `OpenLoadFlowProvider` to compute the steady-state snapshot,
- executes balanced or unbalanced branch faults via `OpenShortCircuitProvider`,
- displays the resulting currents alongside the reference bus-fault currents.

## Prerequisites

1. Install the patched short-circuit implementation so the demo can depend on it:

   ```bash
   cd ../powsybl-open-sc
   ./mvnw install -DskipTests
   ```

2. Install this fork of `powsybl-open-loadflow` (needed because the demo depends
   on the unpublished `2.2.0-SNAPSHOT`):

   ```bash
   cd ../powsybl-open-loadflow
   ./mvnw install -DskipTests
   ```

## Running the demo

From the root of `powsybl-open-loadflow`, start the Spring Boot app:

```bash
./mvnw -f demo/branch-fault-demo/pom.xml spring-boot:run
```

The UI will be available at http://localhost:8080. Select a branch, choose the
fault position `α`, set the fault type (balanced or single-phase), and run the
study. The page shows:

- the load-flow bus voltages/angles coming from open-loadflow,
- short-circuit currents for the requested branch fault plus the IEEE‑14 bus
  faults (toggleable),
- any diagnostics reported by the short-circuit provider (for example when a
  request is redirected to a bus fault).

The REST API underneath the UI exposes:

- `GET /api/network` – bus and branch metadata for the IEEE‑14 case,
- `POST /api/studies` – accepts a list of bus/branch faults and returns the
  load-flow snapshot + short-circuit currents.

## Docker usage

The repository root contains a multi-stage `Dockerfile` and a ready-to-run
`docker-compose.yml`. Building the container automatically:

1. clones `powsybl-open-sc` (override the repo/branch via build args if needed);
2. installs it so the demo can depend on the `0.1.0-SNAPSHOT`;
3. compiles this module and exposes it on port 8080.

From the root directory:

```bash
docker compose up --build
```

Or customize the short-circuit fork:

```bash
docker build \
  -t branch-fault-demo \
  --build-arg OPEN_SC_REPO=https://github.com/myfork/powsybl-open-sc.git \
  --build-arg OPEN_SC_REF=my-feature-branch \
  .
docker run --rm -p 8080:8080 branch-fault-demo
```

Once the container is running, head to http://localhost:8080 and use the UI.

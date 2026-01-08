# syntax=docker/dockerfile:1.7

ARG OPEN_SC_REPO=https://github.com/rte-i/powsybl-open-sc.git
ARG OPEN_SC_REF=COUR-6-US-T1-E1-US1-Explicit-IEC-60909-calculation-mode

FROM maven:3.9.6-eclipse-temurin-21 AS builder

ARG OPEN_SC_REPO
ARG OPEN_SC_REF

WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# 1. Clone and install the open-short-circuit fork so the demo can depend on it.
RUN git clone --depth=1 --branch "${OPEN_SC_REF}" "${OPEN_SC_REPO}" powsybl-open-sc
WORKDIR /workspace/powsybl-open-sc
RUN mvn -B -DskipTests install

# 2. Copy the current repository (open-loadflow fork + demo) and build the UI jar.
WORKDIR /workspace
COPY . /workspace/powsybl-open-loadflow
WORKDIR /workspace/powsybl-open-loadflow
RUN mvn -B -f demo/branch-fault-demo/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=builder /workspace/powsybl-open-loadflow/demo/branch-fault-demo/target/branch-fault-demo-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

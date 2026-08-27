# Pronto backend — production container image.
#
# Build context is `backend/`, not the repository root:
#     docker build -t pronto-backend:dev backend/
#
# Two stages so the shipped image contains a JRE and one jar, and none of the Maven
# toolchain, source tree, or ~200 MB of downloaded dependencies that produced it.

# ==================================================================================================
# Stage 1 — build
# ==================================================================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# pom.xml alone first: this layer only invalidates when dependencies change, so editing a Java
# file does not re-resolve the tree. The BuildKit cache mount below makes the resolve itself
# incremental across builds as well — the two mechanisms cover different cases (layer cache is
# keyed on the pom's content, the mount survives even when it changes).
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src

# -DskipTests is deliberate and is NOT "tests are optional". The image build is one job in a
# pipeline whose *previous* job already ran `mvn clean verify` against a real PostgreSQL service
# (.github/workflows/deploy-production.yml -> verify). Re-running the suite here would double CI
# time to re-prove a fact the pipeline already refuses to proceed without.
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests clean package

# Fixed name so the runtime stage does not depend on the project version string in pom.xml.
RUN cp target/*.jar /build/app.jar


# ==================================================================================================
# Stage 2 — runtime
# ==================================================================================================
# Temurin's Ubuntu (glibc) JRE rather than the Alpine/musl variant. Nothing in this application
# needs glibc specifically — every dependency (PostgreSQL JDBC, AWS SDK v2, libphonenumber, jjwt)
# is pure Java — but the ~90 MB saved by musl is not worth carrying a second libc's failure modes
# on a service with one running task and no capacity to absorb a surprise.
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is here for exactly one reason: the ECS task definition's container health check runs
# INSIDE this container, and it is what makes the liveness/readiness split real rather than
# decorative. Without it the only available signal would be the ALB's external check, which
# cannot distinguish "the process is wedged" from "RDS is briefly unreachable".
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root. A fixed high uid/gid rather than a name lookup so the numeric identity is stable
# across base-image rebuilds.
RUN groupadd --gid 10001 pronto \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin pronto

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

# One writable directory, and only one. /app itself stays root-owned and read-only to the runtime
# user, so nothing in the container can overwrite app.jar.
#
# This exists because `pronto.storage.mode` defaults to `local`, and LocalDiskStorageClient creates
# its base directory in its constructor -- so without a writable ./data the image cannot start under
# its own default configuration, as a non-root user. Production is unaffected either way
# (STORAGE_MODE=s3 is required there by StorageModeStartupGuard, and LocalDiskStorageClient is never
# instantiated), but an image that cannot run its own defaults cannot be smoke-tested before it is
# deployed, which is exactly what this milestone needs it for. Uploads written here are still lost
# on task replacement -- that is the defect MS4's storage guard exists to prevent reaching
# Production, and this directory does not weaken it.
RUN mkdir -p /app/data && chown 10001:10001 /app/data

USER 10001:10001

EXPOSE 8080

# ---- JVM sizing for the Fargate task in infra/terraform/compute.tf (0.5 vCPU / 1024 MB) --------
#
# MaxRAMPercentage=65, not the 75 that is usually quoted. 75% of 1024 MB is a 768 MB heap, and the
# JVM's non-heap footprint here is not small: Spring Boot + Hibernate + the AWS SDK land around
# 120 MB of metaspace, ~50 MB of code cache, plus thread stacks and the SDK's direct byte buffers.
# 768 + ~250 exceeds the task limit, and the failure mode is the kernel OOM-killing the container
# rather than the JVM throwing something diagnosable. 65% leaves ~360 MB of headroom.
#
# ExitOnOutOfMemoryError matters more here than it normally would. The service runs a single task
# (deliberately — see the deployment runbook), so a JVM that survives an OutOfMemoryError in a
# degraded state is the whole platform in a degraded state. Dying fast lets ECS replace it.
#
# Everything is overridable via JAVA_OPTS without rebuilding the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=40.0 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM is PID 1 and receives ECS's SIGTERM directly. Without it the shell holds PID 1,
# the signal is never forwarded, and every deploy ends in a 30-second SIGKILL instead of the
# graceful shutdown configured by `server.shutdown: graceful` in application.yml.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

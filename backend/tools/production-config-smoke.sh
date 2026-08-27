#!/usr/bin/env bash
#
# Boot the PACKAGED application with a production-shaped configuration, and prove two things:
#
#   1. a structurally valid production configuration STARTS, and
#   2. breaking any one required variable REFUSES, before the port is bound.
#
# ==================================================================================================
# Why this exists
# ==================================================================================================
#
# Production MS4 built eight startup guards and left one gap, recorded as its own Known Limitation 7:
#
#     "Backend CI still has no production-configuration smoke run. The guard tests are unit tests;
#      there is no CI job that boots the application with a production-shaped environment. MS5 owns
#      the deployment pipeline where that belongs."
#
# The distinction is not pedantic. ProductionStartupValidationTest constructs each guard directly
# with resolved values, so it proves the guards are individually correct and jointly satisfiable. It
# cannot prove that the assembled Spring context reaches them, that the property names in
# application.yml still bind, that a real bean graph with AI_MODE=openai and STORAGE_MODE=s3 can be
# constructed at all, or that the artifact CI actually ships behaves like the classes CI tested.
# Every one of those is a way for production to fail on a configuration that passes every unit test.
#
# ==================================================================================================
# The database this needs, and why it cannot be the ordinary development one
# ==================================================================================================
#
# Two of MS4's guards are specifically about the local-development database, so satisfying them
# requires a database that is not it:
#
#   * DB_HOST must not be localhost / 127.0.0.1 / ::1. DatabaseConfigStartupGuard refuses those in a
#     production-like environment, with no escape hatch, by deliberate design (MS4 report, Known
#     Limitation 3). This script does not weaken that -- it needs a non-loopback address.
#   * DB_PASSWORD must not be "pronto", the value application.yml and docker-compose.yml share and
#     that is published in this repository.
#
# So point SMOKE_DB_HOST/SMOKE_DB_PASSWORD at a PostgreSQL 16 reachable on a non-loopback address
# with a password of its own. Both CI and the local recipe below create a throwaway one:
#
#   CI      .github/workflows/deploy-production.yml runs a postgres:16 service whose
#           POSTGRES_PASSWORD is not "pronto", and passes the runner's own IP as SMOKE_DB_HOST.
#
#   Local   docker run -d --name pronto-smoke-db -p 5434:5432 \
#             -e POSTGRES_DB=pronto -e POSTGRES_USER=pronto \
#             -e POSTGRES_PASSWORD=smoke-only-placeholder-db-password-not-real postgres:16
#           SMOKE_DB_HOST=<your machine's LAN IP> SMOKE_DB_PORT=5434 \
#             backend/tools/production-config-smoke.sh
#
# No psql client is required: reachability is checked over a TCP socket, and the schema is created
# by the application's own Flyway run, which is itself part of what this proves.
#
# ==================================================================================================
# EVERY VALUE BELOW IS A PLACEHOLDER
# ==================================================================================================
#
# Structurally valid, obviously fake, on reserved example domains, and never used to reach any real
# provider -- the application never contacts OpenAI, Google, SES, SNS or S3 during startup, so a
# fake key is indistinguishable from a real one here. No real secret may ever be added to this file.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SMOKE_DB_HOST="${SMOKE_DB_HOST:-}"
SMOKE_DB_PORT="${SMOKE_DB_PORT:-5432}"
SMOKE_DB_NAME="${SMOKE_DB_NAME:-pronto}"
SMOKE_DB_USER="${SMOKE_DB_USER:-pronto}"

# Must differ from the committed local-development password "pronto", or DatabaseConfigStartupGuard
# refuses it -- which is the whole point of that guard.
SMOKE_DB_PASSWORD="${SMOKE_DB_PASSWORD:-smoke-only-placeholder-db-password-not-real}"

PORT="${SMOKE_PORT:-18099}"
BOOT_TIMEOUT_SECONDS="${SMOKE_BOOT_TIMEOUT:-120}"
WORK_DIR="$(mktemp -d)"
FAILURES=0

cleanup() {
  [[ -n "${APP_PID:-}" ]] && kill "${APP_PID}" 2>/dev/null
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

# --------------------------------------------------------------------------------------------------
# A complete, structurally valid production configuration.
#
# This function is the executable specification of the variable table in
# docs/production-roadmap/reports/prod-MS4-report.md section 4 -- and unlike the table, it is
# checked. Adding a required variable without adding it here makes the positive case fail.
# --------------------------------------------------------------------------------------------------
production_env() {
  cat <<-ENV
	PRONTO_ENVIRONMENT=production
	DB_HOST=${SMOKE_DB_HOST}
	DB_PORT=${SMOKE_DB_PORT}
	DB_NAME=${SMOKE_DB_NAME}
	DB_USER=${SMOKE_DB_USER}
	DB_PASSWORD=${SMOKE_DB_PASSWORD}
	DEMO_DATA_MODE=off
	JWT_SECRET=smoke-only-placeholder-jwt-signing-key-not-a-real-secret
	OTP_PEPPER=smoke-only-placeholder-otp-pepper-different-from-the-jwt-secret
	AI_MODE=openai
	OPENAI_API_KEY=sk-smoke-only-placeholder-not-a-real-key
	OPENAI_MODEL=gpt-4o-mini
	EMAIL_MODE=ses
	EMAIL_FROM=noreply@pronto.example
	EMAIL_SES_REGION=us-east-1
	SMS_MODE=aws
	AWS_SMS_REGION=us-east-1
	MAPS_MODE=google
	MAPS_API_KEY=smoke-only-placeholder-maps-key-not-real
	STORAGE_MODE=s3
	STORAGE_S3_BUCKET=pronto-smoke-placeholder-bucket
	STORAGE_S3_REGION=us-east-1
	CORS_ALLOWED_ORIGINS=https://app.pronto.example
	BEHIND_PROXY=true
	TRUSTED_PROXIES=10.0.0.0/16
	SERVER_PORT=${PORT}
	ENV
}

# Runs the jar with the production environment plus any KEY=VALUE overrides given as arguments, and
# writes the log to $1. Returns the application's exit code; 0 means it started (and was then
# stopped by us), non-zero means it refused.
run_with() {
  local logfile="$1"; shift
  local -a env_args=()
  local line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && env_args+=("${line}")
  done < <(production_env)
  for override in "$@"; do env_args+=("${override}"); done

  # `env KEY=V ...` rather than `env -i`: clearing the whole environment would strip PATH,
  # SystemRoot and the JVM's own variables, which breaks portability for no benefit. Every variable
  # the guards read is set explicitly above, so an inherited one cannot silently change the result.
  env "${env_args[@]}" java -jar "${JAR}" > "${logfile}" 2>&1 &
  APP_PID=$!

  local waited=0
  while kill -0 "${APP_PID}" 2>/dev/null; do
    if grep -q "Tomcat started on port" "${logfile}" 2>/dev/null; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
    if (( waited >= BOOT_TIMEOUT_SECONDS )); then
      kill "${APP_PID}" 2>/dev/null
      wait "${APP_PID}" 2>/dev/null
      APP_PID=""
      return 124
    fi
  done

  wait "${APP_PID}" 2>/dev/null
  local code=$?
  APP_PID=""
  return "${code}"
}

# --------------------------------------------------------------------------------------------------
log "0. Preconditions"

if [[ -z "${SMOKE_DB_HOST}" ]]; then
  printf 'FATAL  SMOKE_DB_HOST is not set, and it has no safe default.\n'
  printf '       It must be a NON-LOOPBACK address reaching a PostgreSQL 16 whose password is not\n'
  printf '       the committed "pronto" -- DatabaseConfigStartupGuard refuses both, deliberately.\n'
  printf '       See this file'"'"'s header for a two-line local recipe.\n'
  exit 2
fi

for loopback in localhost 127.0.0.1 0.0.0.0 ::1 "[::1]"; do
  if [[ "${SMOKE_DB_HOST}" == "${loopback}" ]]; then
    printf 'FATAL  SMOKE_DB_HOST=%s is a loopback address.\n' "${SMOKE_DB_HOST}"
    printf '       The application is SUPPOSED to refuse this, so the run would prove nothing except\n'
    printf '       that a guard nobody doubted still works. Use a real interface address.\n'
    exit 2
  fi
done

# A plain TCP connect, so no PostgreSQL client is required. It deliberately does not authenticate:
# the credentials are exercised by the application itself in the positive case below, which is the
# thing actually under test.
if timeout 5 bash -c "exec 3<>/dev/tcp/${SMOKE_DB_HOST}/${SMOKE_DB_PORT}" 2>/dev/null; then
  pass "PostgreSQL reachable at ${SMOKE_DB_HOST}:${SMOKE_DB_PORT}"
else
  printf 'FATAL  Nothing is listening on %s:%s\n' "${SMOKE_DB_HOST}" "${SMOKE_DB_PORT}"
  exit 2
fi

JAR="$(ls -1 "${BACKEND_DIR}"/target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc\|\.original' | head -1)"
if [[ -z "${JAR}" ]]; then
  printf 'FATAL  No packaged jar in %s/target. Run: mvn -B -DskipTests package\n' "${BACKEND_DIR}"
  exit 2
fi
pass "packaged artifact $(basename "${JAR}")"

# --------------------------------------------------------------------------------------------------
log "1. A valid production configuration STARTS"

if run_with "${WORK_DIR}/positive.log"; then
  pass "application started and bound port ${PORT}"

  # Only meaningful once it is actually up: prove the probe endpoints the ALB and the ECS agent
  # depend on answer 200 WITHOUT authentication, in a production-like environment. This is the
  # combination that no unit test covers -- production security config plus production actuator
  # config plus a real HTTP request.
  for probe in health health/liveness health/readiness; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/${probe}" || echo 000)"
    if [[ "${code}" == "200" ]]; then
      pass "/actuator/${probe} -> 200"
    else
      fail "/actuator/${probe} -> ${code} (expected 200; the ALB health check would fail)"
    fi
  done

  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/users/me" || echo 000)"
  [[ "${code}" == "401" ]] && pass "a protected route still refuses an unauthenticated caller (401)" \
                           || fail "/api/users/me -> ${code} (expected 401)"

  # The startup summary states what the application believes it is running as. If this says
  # productionLike=false, every guard silently did nothing and the whole run proved nothing.
  if grep -q "productionLike=true" "${WORK_DIR}/positive.log"; then
    pass "startup summary confirms productionLike=true"
  else
    fail "startup summary does not report productionLike=true -- the guards did not apply"
  fi

  if grep -q "ai=openai" "${WORK_DIR}/positive.log" && grep -q "storage=s3" "${WORK_DIR}/positive.log" \
     && grep -q "maps=google" "${WORK_DIR}/positive.log" && grep -q "email=ses" "${WORK_DIR}/positive.log"; then
    pass "real provider transports selected (openai / s3 / google / ses)"
  else
    fail "provider modes are not the production ones"
  fi

  kill "${APP_PID}" 2>/dev/null; wait "${APP_PID}" 2>/dev/null; APP_PID=""
else
  fail "application refused a configuration that should be valid"
  echo "  ---- last 30 log lines ----"
  tail -30 "${WORK_DIR}/positive.log" | sed 's/^/  /'
fi

# --------------------------------------------------------------------------------------------------
log "2. Breaking ONE required variable REFUSES"
#
# One case per guard. Each asserts the process exited non-zero, that the message names the offending
# environment variable, and -- the property that actually matters -- that Tomcat never bound a port.
# A guard that fires after the server is accepting connections has already served traffic it should
# not have.

assert_refuses() {
  local label="$1" expected="$2"; shift 2
  local logfile="${WORK_DIR}/negative-$(echo "${label}" | tr -cd '[:alnum:]').log"

  if run_with "${logfile}" "$@"; then
    fail "${label}: application STARTED -- it should have refused"
    kill "${APP_PID}" 2>/dev/null; wait "${APP_PID}" 2>/dev/null; APP_PID=""
    return
  fi

  if ! grep -q "${expected}" "${logfile}"; then
    fail "${label}: refused, but the message never mentions '${expected}'"
    tail -15 "${logfile}" | sed 's/^/      /'
    return
  fi

  if grep -q "Tomcat started on port" "${logfile}"; then
    fail "${label}: refused only AFTER binding a port -- it served traffic first"
    return
  fi

  pass "${label}: refused before binding a port, message names ${expected}"
}

assert_refuses "mock AI"                  "AI_MODE"              "AI_MODE=mock"
assert_refuses "local disk storage"       "STORAGE_MODE"         "STORAGE_MODE=local"
assert_refuses "development CORS origin"  "CORS_ALLOWED_ORIGINS" "CORS_ALLOWED_ORIGINS=http://localhost:5173"
assert_refuses "logging email transport"  "EMAIL_MODE"           "EMAIL_MODE=log"
assert_refuses "fake maps provider"       "MAPS_MODE"            "MAPS_MODE=fake"
assert_refuses "empty TRUSTED_PROXIES"    "TRUSTED_PROXIES"      "TRUSTED_PROXIES="

# The committed placeholder JWT secret -- the value application.yml falls back to, published in this
# repository. Long enough that jjwt accepts it, so JwtSecretStartupGuard is genuinely the component
# that refuses and the message names the variable. Contrast with the too-short case below.
assert_refuses "committed JWT placeholder" "JWT_SECRET" \
  "JWT_SECRET=local-dev-only-insecure-jwt-secret-key-please-override-via-JWT_SECRET-env-var-before-any-real-deployment"

# The one that is not merely "a variable is missing": a syntactically valid CIDR that happens to
# cover the public internet. It passes the presence check and is exactly the value that disables
# auth rate limiting entirely, so this is the highest-value negative case in the file.
assert_refuses "internet-wide TRUSTED_PROXIES" "private address space" "TRUSTED_PROXIES=0.0.0.0/0"

# Demo data in production would seed synthetic professionals with invented phone numbers that may
# belong to real people, while SMS_MODE=aws is live.
assert_refuses "demo dataset enabled"     "demo"                 "DEMO_DATA_MODE=seed"

# --------------------------------------------------------------------------------------------------
log "3. The two cases MS4 recorded as a known diagnostic gap"
#
# MS4's report, Known Limitation 2:
#
#     "Guard failures can be preceded by a database connection failure. The guards are
#      @PostConstruct, and Spring may instantiate Flyway/JPA infrastructure beans first. [...] The
#      security property is unaffected -- the application refuses to start either way, and the port
#      is never bound -- but the diagnostic ordering is not controllable without moving to an
#      EnvironmentPostProcessor, which would be a larger architectural change than the benefit
#      justifies."
#
# These two cases are that limitation, and they are asserted rather than skipped so it is a MEASURED
# property of the build instead of a paragraph somebody has to remember. Both still prove the thing
# that matters -- refused, and no port ever bound. What they do not get is a message naming the
# variable, and the expected substrings below say exactly which component wins the race instead.
#
# If somebody later moves the guards to an EnvironmentPostProcessor, these two will start failing,
# and the correct response is to promote them into section 2 above.

assert_refuses "committed DB password (pre-empted by Flyway's connection attempt)" \
  "password authentication failed" "DB_PASSWORD=pronto"

assert_refuses "under-32-character JWT secret (pre-empted by jjwt's own key check)" \
  "WeakKeyException" "JWT_SECRET=too-short"

# --------------------------------------------------------------------------------------------------
log "Result"
if (( FAILURES == 0 )); then
  printf '\033[32mProduction-shaped smoke: PASS\033[0m\n'
  exit 0
fi
printf '\033[31mProduction-shaped smoke: %d FAILURE(S)\033[0m\n' "${FAILURES}"
exit 1

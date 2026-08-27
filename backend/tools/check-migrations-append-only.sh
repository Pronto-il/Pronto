#!/usr/bin/env bash
#
# Fail if a Flyway migration that already exists on the base branch has been modified or deleted.
#
# ==================================================================================================
# Why this is not redundant with Flyway's own checksum check
# ==================================================================================================
#
# Flyway records a checksum for every migration it applies and refuses to start when a file it has
# already run has changed. That check is real and it is not going away — but it fires in the
# ENVIRONMENT, at startup, against a database that has already applied the original. So the first
# place an edited migration is noticed is a deployment that will not start: on the production path
# that is after the image is built, after the approval, and after ECS has already stopped the only
# running task. The application is down and the fix is a code change.
#
# The mistake itself is easy to make and looks harmless in review — correcting a typo in a WHERE
# clause, adding a missing index to the migration that "should have had it". Migrations here are
# forward-only (52 of them, V1..V52, all applied), so fixing an applied migration means writing the
# next one. This says so at the point the edit is made.
#
# Usage:  backend/tools/check-migrations-append-only.sh [base-ref]
#         base-ref defaults to origin/main.

set -euo pipefail

MIGRATIONS='backend/src/main/resources/db/migration/V*.sql'
BASE_REF="${1:-${GITHUB_BASE_REF:-main}}"

# --------------------------------------------------------------------------------------------------
# Choosing what to compare against.
#
# The obvious `git diff origin/main HEAD` is wrong in the case that matters most: on a PUSH TO MAIN,
# origin/main IS HEAD, the diff is empty, and the check passes on every input — including a commit
# that rewrote V1. It has to be the merge base for branch work, and the previous commit for a push
# to the base branch itself.
# --------------------------------------------------------------------------------------------------
resolve_base() {
  local ref="$1" remote="origin/$1"

  if ! git rev-parse --verify --quiet "${remote}" >/dev/null; then
    git fetch --no-tags --depth=50 origin "${ref}" >/dev/null 2>&1 && remote="FETCH_HEAD" || remote=""
  fi

  if [ -n "${remote}" ]; then
    local merge_base
    merge_base="$(git merge-base "${remote}" HEAD 2>/dev/null || true)"
    # A merge base that is HEAD itself means we are ON the base branch (a push to main), so there is
    # no branch to compare — fall through to the parent commit.
    if [ -n "${merge_base}" ] && [ "${merge_base}" != "$(git rev-parse HEAD)" ]; then
      echo "${merge_base}"
      return
    fi
  fi

  git rev-parse --verify --quiet HEAD^ 2>/dev/null || true
}

BASE="$(resolve_base "${BASE_REF}")"
if [ -z "${BASE}" ]; then
  echo "No base commit to compare against (root commit?). Nothing to check."
  exit 0
fi

# Diffing BASE against the WORKING TREE, not against HEAD. In CI the tree is clean at HEAD so the
# two are identical; run by hand it additionally catches an edit that has not been committed yet,
# which is the moment it is cheapest to fix.
#
# --diff-filter=MD: Modified or Deleted only. Added is the entire normal case — writing a new
# migration is exactly what is supposed to happen — and must never be flagged.
#
# The pathspec is QUOTED so git does the wildcard matching, not the shell. Unquoted, the shell
# expands the glob against the working tree first — which means a DELETED migration produces no
# match, is never passed to git, and the deletion goes unreported. That is the more destructive half
# of what this script exists to catch, and it silently did not work until the quotes were added.
CHANGED="$(git diff --name-only --diff-filter=MD "${BASE}" -- "${MIGRATIONS}" || true)"

if [ -n "${CHANGED}" ]; then
  echo "ERROR: existing Flyway migrations were modified or deleted."
  echo
  echo "${CHANGED}" | sed 's/^/  /'
  echo
  echo "Migrations are forward-only. Every environment that already applied these files recorded a"
  echo "checksum, and Flyway will refuse to start against the new contents — including Production,"
  echo "where that failure arrives mid-deploy with the previous task already stopped."
  echo
  echo "Write a new V<n>__description.sql instead."
  exit 1
fi

echo "OK: no existing migration was modified (base ${BASE})."

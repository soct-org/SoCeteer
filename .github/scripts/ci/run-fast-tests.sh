#!/usr/bin/env bash
set -euo pipefail

# Shared test entrypoint for CI and docker.
# Expects:
#   - SOCT_CHISEL_VERSION (optional; build.sbt will fallback if unset)
#   - SOCT_TEST_RUN_DIR (optional; used by the codebase/tests)

echo "SOCT_CHISEL_VERSION=${SOCT_CHISEL_VERSION:-<unset>}"
echo "SOCT_TEST_RUN_DIR=${SOCT_TEST_RUN_DIR:-<unset>}"
# Echo PATH for debugging purposes
echo "PATH=${PATH}"

sbt 'testOnly soct.tests.SimulationSpec -- -t "Fast test should run without errors"'
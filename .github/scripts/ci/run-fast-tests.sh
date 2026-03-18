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
# Print contents of /home/soct/.local/share/coursier/bin as sbt is not found in PATH
echo "Contents of /home/soct/.local/share/coursier/bin:"
ls -la /home/soct/.local/share/coursier/bin

/home/soct/.local/share/coursier/bin/sbt 'testOnly soct.tests.SimulationSpec -- -t "Fast test should run without errors"'
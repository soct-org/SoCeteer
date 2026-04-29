#!/usr/bin/env bash
set -euo pipefail

# Placeholder "full" suite.
# For now it's identical to the fast test.

echo "SOCT_CHISEL_VERSION=${SOCT_CHISEL_VERSION:-<unset>}"
echo "SOCT_TEST_RUN_DIR=${SOCT_TEST_RUN_DIR:-<unset>}"

sbt -J-Xmx8G 'testOnly soct.tests.SimulationSpec -- -t "Fast test should run without errors"'

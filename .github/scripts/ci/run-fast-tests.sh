#!/usr/bin/env bash
set -euo pipefail

echo "SOCT_CHISEL_VERSION=${SOCT_CHISEL_VERSION:-<unset>}"
echo "SOCT_TEST_RUN_DIR=${SOCT_TEST_RUN_DIR:-<unset>}"

# Run the fast test suite, which includes tests that are designed to execute quickly and validate basic functionality.
sbt -J-Xmx16G 'testOnly soct.tests.SimulationSpec -- -t "Fast test should run without errors"'
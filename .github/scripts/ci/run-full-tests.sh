#!/usr/bin/env bash
set -euo pipefail

echo "SOCT_CHISEL_VERSION=${SOCT_CHISEL_VERSION:-<unset>}"
echo "SOCT_TEST_RUN_DIR=${SOCT_TEST_RUN_DIR:-<unset>}"

sbt -J-Xmx8G 'testOnly soct.tests.SimulationSpec -- -t "Full test should run without errors"'

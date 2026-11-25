#!/usr/bin/env bash
# Runs every unit test with coverage and fails if line coverage is under 95%.
# HTML report: app/build/reports/coverage/test/debug/index.html
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew createDebugUnitTestCoverageReport
python3 scripts/coverage.py "$@"

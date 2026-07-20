#!/usr/bin/env bash
# Run Android Lint on the debug variant and point at the HTML report.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew lintDebug "$@"
echo
echo "Report: app/build/reports/lint-results-debug.html"

#!/usr/bin/env bash
# Run the unit test for this item and report pass/fail.
set -euo pipefail
cd "$(dirname "$0")"
CLASS="xx.biketracker.DurationPaceFormatTest"
./gradlew testDebugUnitTest --tests "$CLASS" --rerun-tasks "$@"
report=$(find app/build/test-results -name "*${CLASS##*.}.xml" | head -1)
echo
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$report" | head -1

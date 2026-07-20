#!/usr/bin/env bash
# Run the JVM unit tests and print a text summary per class.
# All tests:      ./06-Test.sh
# One class:      ./06-Test.sh --tests 'xx.biketracker.SmoothRouteTest'
# Matching set:   ./06-Test.sh --tests 'xx.biketracker.*FormatTest'
set -uo pipefail
cd "$(dirname "$0")"
# Don't abort on failing tests — the summary below is exactly what we want to see then.
./gradlew testDebugUnitTest --rerun-tasks "$@" || status=$?

echo
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
files = sorted(glob.glob('app/build/test-results/testDebugUnitTest/*.xml'))
tot = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for path in files:
    r = ET.parse(path).getroot()
    n = {k: int(r.get(k, 0)) for k in tot}
    for k in tot:
        tot[k] += n[k]
    mark = 'OK  ' if n['failures'] == n['errors'] == 0 else 'FAIL'
    print(f"{mark} {r.get('name')}: tests={n['tests']} failures={n['failures']} "
          f"errors={n['errors']} skipped={n['skipped']}")
print(f"\nTotal: tests={tot['tests']} failures={tot['failures']} "
      f"errors={tot['errors']} skipped={tot['skipped']}")
PY
exit "${status:-0}"

#!/usr/bin/env bash
# Run the JVM unit tests and print a text summary per class.
# All tests:      ./06-Test.sh
# One class:      ./06-Test.sh --tests 'xx.biketracker.SmoothRouteTest'
# Matching set:   ./06-Test.sh --tests 'xx.biketracker.*FormatTest'
set -uo pipefail
cd "$(dirname "$0")"

RESULTS=app/build/test-results/testDebugUnitTest

# Last run's XML is cleared before this one starts. A build that does not compile writes no results
# at all, and the summary below would otherwise read the previous run's files and report a clean
# pass over code that never ran. The same holds for --tests: without this, a filtered run summarises
# every class the last full run left behind.
rm -rf "$RESULTS"

# Don't abort on failing tests — the summary below is exactly what we want to see then.
./gradlew testDebugUnitTest --rerun-tasks "$@" || status=$?

echo
python3 - "$RESULTS" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
files = sorted(glob.glob(f'{sys.argv[1]}/*.xml'))
if not files:
    print('No results: the tests did not run. Compilation failed, or the filter matched nothing.')
    raise SystemExit(0)
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

# Said after the summary, where the eye already is: a green Total under a failed build is the one
# reading this script must never leave behind.
if [ -n "${status:-}" ]; then
    echo
    echo "Gradle exited $status — the run above is not a pass."
fi

sleep 3

exit "${status:-0}"



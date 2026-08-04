#!/usr/bin/env bash
# Run Android Lint on the debug variant and print the findings as plain text.
set -uo pipefail
cd "$(dirname "$0")"
# Don't abort on lint errors — the report below is exactly what we want to see then. Lint aborts
# the Gradle task on any error-severity issue, and that is the run whose report matters most.
./gradlew lintDebug "$@" || status=$?

xml=app/build/reports/lint-results-debug.xml
echo
if [ ! -f "$xml" ]; then
    echo "Lint: no report at $xml — the run failed before writing one."
    exit "${status:-1}"
fi
python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
issues = ET.parse(sys.argv[1]).getroot().findall('issue')
if not issues:
    print("Lint: no issues.")
for i in issues:
    loc = i.find('location')
    where = ""
    if loc is not None:
        where = loc.get('file', '')
        if loc.get('line'):
            where += f":{loc.get('line')}"
    print(f"[{i.get('severity')}] {i.get('id')} — {i.get('message')}")
    if where:
        print(f"    {where}")
print(f"\n{len(issues)} issue(s). HTML: {sys.argv[1].replace('.xml', '.html')}")
PY

exit "${status:-0}"

#!/usr/bin/env bash
# Run Android Lint on the debug variant and print the findings as plain text.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew lintDebug "$@"

xml=app/build/reports/lint-results-debug.xml
echo
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

"""Report actual Android JVM test totals from Gradle XML results."""
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

folder = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest")
reports = sorted(folder.glob("TEST-*.xml"))
counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, "0"))
print(json.dumps({"suites": len(reports), **counts}))
if not reports or counts["tests"] == 0 or counts["failures"] or counts["errors"]:
    raise SystemExit("Missing or failing Android unit-test reports")

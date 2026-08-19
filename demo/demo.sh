#!/bin/sh
# AgentLens one-command demo: run the 20-scenario dataset, compare two agent
# releases with the pass^k gate, then emit the self-contained HTML report.
set -e
M2=/root/.m2/repository
JARS=$(find "$M2/com/fasterxml/jackson" "$M2/org/xerial" "$M2/org/slf4j" -name '*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | grep -E '2\.17\.2|sqlite-jdbc-3|slf4j-api' | tr '\n' ':')
CP="/app/cli.jar:/app/lib/core.jar:/app/lib/adapters.jar:/app/lib/eval.jar:$JARS"

echo "=== AgentLens demo: 20-scenario run (pass^k, 3 repeats) ==="
java -cp "$CP" com.agentlens.cli.AgentLensApp run \
    --scenarios /app/data/scenarios/ops-scenarios.json --repeats 3 \
    --db /app/output/runs.db || echo "(gate rejected unstable scenarios - expected)"

echo ""
echo "=== Release comparison: good candidate ==="
java -cp "$CP" com.agentlens.cli.AgentLensApp compare \
    --baseline correct --candidate correct --repeats 3 --db /app/output/runs.db \
    || exit 1

echo ""
echo "=== Release comparison: flaky candidate (gate must reject) ==="
java -cp "$CP" com.agentlens.cli.AgentLensApp compare \
    --baseline correct --candidate flaky --repeats 3 --db /app/output/runs.db \
    && { echo "ERROR: flaky candidate unexpectedly passed the gate"; exit 1; } \
    || echo "(gate rejected flaky candidate - correct)"

echo ""
echo "=== HTML report ==="
java -cp "$CP" com.agentlens.cli.AgentLensApp report \
    --db /app/output/runs.db --out /app/output/report.html

echo ""
echo "Demo complete. Artifacts in /app/output:"
ls -la /app/output

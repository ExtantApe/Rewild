#!/bin/bash
# Runs the full acceptance suite on each supported Paper version, one server at a time.
cd "$(dirname "$0")"
for v in 26.3 26.2 26.1.2; do
  perl -e 'alarm 5400; exec @ARGV' node acceptance.mjs "$v" > "../build/acc-$v.log" 2>&1
  echo "EXIT $?" >> "../build/acc-$v.log"
  for pid in $(lsof -t -nP -iTCP:25616 -sTCP:LISTEN); do lsof -p "$pid" | grep -q "cwd.*rewild-test" && kill "$pid"; done
  sleep 3
  for d in "${TMPDIR:-/tmp}"/rewild-test-*; do [ -d "$d" ] && rm -rf "$d"; done
done

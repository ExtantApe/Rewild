#!/bin/bash
# Starts a throwaway Paper server in $1, waits for it to finish loading, runs the commands
# given on stdin (one per line, "sleep N" pauses), then stops it. Never point this at a real server.
set -euo pipefail
dir="$1"; java="${JAVA:-java}"
case "$dir" in /private/tmp/*|/tmp/*|/var/folders/*) ;; *) echo "refusing: $dir isn't a temp folder" >&2; exit 2;; esac
grep -q '^server-ip=127.0.0.1$' "$dir/server.properties" || { echo "refusing: server-ip must be 127.0.0.1" >&2; exit 2; }
cd "$dir"; rm -f in.fifo; mkfifo in.fifo
"$java" -Xmx2G -Dterminal.jline=false -Dterminal.ansi=false -jar paper.jar nogui < in.fifo > server.log 2>&1 &
pid=$!; exec 3>in.fifo
for i in $(seq 1 600); do grep -q 'Done (' server.log && break; kill -0 $pid 2>/dev/null || { echo "server exited early"; tail -30 server.log; exit 1; }; sleep 0.5; done
grep -q 'Done (' server.log || { echo "no Done after 300s"; kill $pid; exit 1; }
while IFS= read -r line; do
  case "$line" in sleep\ *) sleep "${line#sleep }";; "") ;; *) echo "$line" >&3;; esac
done
echo stop >&3
for i in $(seq 1 240); do kill -0 $pid 2>/dev/null || break; sleep 0.5; done
kill -0 $pid 2>/dev/null && { echo "server didn't stop, killing"; kill -9 $pid; }
exec 3>&-; rm -f in.fifo

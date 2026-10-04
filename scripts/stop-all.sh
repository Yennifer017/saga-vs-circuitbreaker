#!/usr/bin/env bash
cd "$(dirname "$0")/.."
for f in logs/*.pid; do
  [ -e "$f" ] || continue
  pid=$(cat "$f")
  kill "$pid" 2>/dev/null && echo "  detenido $(basename "$f" .pid) (pid $pid)"
  rm -f "$f"
done
echo "Todos los servicios detenidos."

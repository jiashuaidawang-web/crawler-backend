#!/bin/bash
# Run a ClickHouse SQL statement via HTTP API.
# GET for readonly, POST (body) for writes so readonly mode allows DDL/DML.
# Usage: echo "SQL" | ./ch_run.sh [get|post]
CH="http://100.97.74.45:8123"
AUTH="user=default&password=pamirs%40123"
DB="crawler"
mode="${1:-get}"
cd "$(dirname "$0")"

sql=$(cat)

if [ "$mode" = "post" ]; then
  curl -s -m 120 -X POST -H "Connection: close" \
    "${CH}/?${AUTH}&database=${DB}" \
    --data-binary "$sql"
else
  enc=$(printf '%s' "$sql" | ./pctenc.sh)
  curl -s -m 30 -H "Connection: close" \
    "${CH}/?${AUTH}&database=${DB}&query=${enc}"
fi
echo

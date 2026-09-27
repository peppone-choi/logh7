#!/usr/bin/env bash
# 작성: 최병호 | evidence:guess | Ubuntu VM 안에서만 실행하는 운영 리허설
set -euo pipefail
cd "$(dirname "$0")"
compose=(sudo docker compose)

wait_database() {
  for attempt in $(seq 1 30); do
    if "${compose[@]}" exec -T postgres pg_isready -U logh7 -d logh7 >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo 'PostgreSQL readiness timed out' >&2
  return 1
}

check_http() {
  python3 - "$1" <<'PY'
import sys
import urllib.request
try:
    with urllib.request.urlopen(sys.argv[1], timeout=5) as response:
        assert response.status == 200, response.status
        print(sys.argv[1], response.status)
except (OSError, ConnectionError) as exc:
    print(f'{sys.argv[1]} pending: {exc}', file=sys.stderr)
    sys.exit(1)
PY
}

wait_http() {
  local url="$1"
  for attempt in $(seq 1 30); do
    if check_http "$url"; then return 0; fi
    sleep 2
  done
  echo "HTTP readiness timed out: $url" >&2
  return 1
}

wait_database
"${compose[@]}" exec -T postgres psql -U logh7 -d logh7 -v ON_ERROR_STOP=1 -c \
  'CREATE TABLE IF NOT EXISTS ops_rehearsal (id integer PRIMARY KEY, marker text NOT NULL);'
"${compose[@]}" exec -T postgres psql -U logh7 -d logh7 -v ON_ERROR_STOP=1 -c \
  "INSERT INTO ops_rehearsal VALUES (1, '2026-09-27-ops') ON CONFLICT (id) DO UPDATE SET marker = EXCLUDED.marker;"
wait_http 'http://127.0.0.1:9090/-/ready'
wait_http 'http://127.0.0.1:3000/api/health'

"${compose[@]}" stop
"${compose[@]}" start
wait_database
wait_http 'http://127.0.0.1:9090/-/ready'
wait_http 'http://127.0.0.1:3000/api/health'
marker="$("${compose[@]}" exec -T postgres psql -U logh7 -d logh7 -At -v ON_ERROR_STOP=1 -c 'SELECT marker FROM ops_rehearsal WHERE id = 1;')"
test "$marker" = '2026-09-27-ops'
python3 - <<'PY'
import base64
import json
import pathlib
import urllib.request
password = pathlib.Path('secrets/grafana_password.txt').read_text().strip()
token = base64.b64encode(f'admin:{password}'.encode()).decode()
request = urllib.request.Request('http://127.0.0.1:3000/api/datasources/uid/prometheus', headers={'Authorization': f'Basic {token}'})
with urllib.request.urlopen(request, timeout=5) as response:
    source = json.load(response)
assert source['url'] == 'http://prometheus:9090', source
print('Grafana Prometheus datasource OK')
PY
echo 'COMPOSE_REHEARSAL_OK'

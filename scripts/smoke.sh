#!/usr/bin/env bash
set -euo pipefail
base="${1:?Uso: bash scripts/smoke.sh https://...}"
base="${base%/}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
expect() {
  local wanted="$1"; shift
  local actual
  actual="$(curl -sS -o "$tmp/response" -w '%{http_code}' "$@")"
  if [ "$actual" != "$wanted" ]; then echo "Esperado HTTP $wanted; recebido $actual" >&2; cat "$tmp/response" >&2; exit 1; fi
}
expect 200 "$base/health"
expect 201 -X POST "$base/tickets" -H 'content-type: application/json' -d '{"title":"Smoke test","description":"Dado fictício"}'
id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$tmp/response")"
expect 200 "$base/tickets/$id"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["id"]==sys.argv[2] and x["status"]=="OPEN"' "$tmp/response" "$id"
expect 200 "$base/tickets?limit=1"
python3 -c 'import json,sys; assert len(json.load(open(sys.argv[1]))["items"])<=1' "$tmp/response"
python3 - "$base" "$id" <<'PY'
import json, sys, time, urllib.parse, urllib.request
base, wanted = sys.argv[1:]
for attempt in range(5):
    token = None
    found = False
    while True:
        params = {"limit": "50"}
        if token: params["nextToken"] = token
        with urllib.request.urlopen(base + "/tickets?" + urllib.parse.urlencode(params)) as response:
            page = json.load(response)
        if any(item["id"] == wanted for item in page["items"]):
            found = True
            break
        token = page.get("nextToken")
        if not token: break
    if found: break
    time.sleep(1)
else:
    raise SystemExit("Chamado criado não apareceu na listagem")
PY
expect 200 -X PATCH "$base/tickets/$id/status" -H 'content-type: application/json' -d '{"status":"IN_PROGRESS"}'
expect 200 -X PATCH "$base/tickets/$id/status" -H 'content-type: application/json' -d '{"status":"CLOSED"}'
expect 409 -X PATCH "$base/tickets/$id/status" -H 'content-type: application/json' -d '{"status":"OPEN"}'
expect 404 "$base/tickets/00000000-0000-0000-0000-000000000000"
echo 'Smoke test concluído.'

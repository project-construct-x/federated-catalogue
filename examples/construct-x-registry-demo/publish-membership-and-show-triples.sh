#!/usr/bin/env bash
# Publish MembershipCredential as an asset and print the Fuseki RDF-star triples.
#
# Assumes the local dev stack is already running (API on :8081, Fuseki on :3330,
# Keycloak as key-server:8080). Does not start or build anything.
#
# Usage (from anywhere):
#   ./examples/construct-x-registry-demo/publish-membership-and-show-triples.sh
#
# Prerequisites: curl, jq; 127.0.0.1 key-server in /etc/hosts

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$SCRIPT_DIR/membership-credential-v2.jsonld"

API_URL="${API_URL:-http://localhost:8081}"
FUSEKI_URL="${FUSEKI_URL:-http://localhost:3330/ds}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://key-server:8080}"
REALM="${FC_REALM:-federated-catalogue-realm}"
CLIENT_ID="${FC_CLIENT_ID:-federated-catalogue}"
CLIENT_SECRET="${FC_CLIENT_SECRET:-**********}"
# Do not use USERNAME/PASSWORD — those collide with the shell login env (e.g. USERNAME=christian).
FC_USER="${FC_USER:-fc-ca-test}"
FC_PASS="${FC_PASS:-CHANGE_ME_dev_only1}"

# Catalogue asset id is the credentialSubject id (DID), not the VC id.
ASSET_ID="did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1"
SUBJECT_ID="$ASSET_ID"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: required command not found: $1" >&2
    exit 1
  }
}

need curl
need jq

if ! grep -qE '[[:space:]]key-server([[:space:]]|$)' /etc/hosts 2>/dev/null; then
  echo "error: add '127.0.0.1 key-server' to /etc/hosts (token iss must match)" >&2
  exit 1
fi

if [[ ! -f "$FIXTURE" ]]; then
  echo "error: fixture missing: $FIXTURE" >&2
  exit 1
fi

# Reachability only — aggregate /actuator/health can be 503 DOWN (e.g. Neo4j
# indicator) while the API itself is serving requests.
api_code="$(curl -s -o /dev/null -w '%{http_code}' "$API_URL/actuator/health" || true)"
if [[ -z "$api_code" || "$api_code" == "000" ]]; then
  echo "error: API not reachable at $API_URL — start the dev stack first" >&2
  echo "       (e.g. cd docker && ./dev.sh up && ./dev.sh run)" >&2
  exit 1
fi
if [[ "$api_code" != "200" ]]; then
  echo "==> API responds (HTTP $api_code on /actuator/health); continuing"
fi

fuseki_code="$(curl -s -o /dev/null -w '%{http_code}' "$FUSEKI_URL" || true)"
if [[ -z "$fuseki_code" || "$fuseki_code" == "000" ]]; then
  echo "error: Fuseki not reachable at $FUSEKI_URL (expected host port 3330)" >&2
  exit 1
fi

get_token() {
  curl -sf -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password" \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "username=$FC_USER" \
    -d "password=$FC_PASS" \
    | jq -r '.access_token'
}

urlencode() {
  jq -nr --arg v "$1" '$v|@uri'
}

sparql() {
  local query="$1"
  curl -sf -X POST "$FUSEKI_URL/sparql" \
    -H 'Accept: application/sparql-results+json' \
    -H 'Content-Type: application/sparql-query' \
    --data-binary "$query"
}

print_triples() {
  local query
  query=$(cat <<EOF
PREFIX cred: <https://www.w3.org/2018/credentials#>
SELECT ?s ?p ?o ?cs WHERE {
  <<(?s ?p ?o)>> cred:credentialSubject ?cs .
  FILTER(
    ?cs = <$SUBJECT_ID>
    || STRSTARTS(STR(?s), "$SUBJECT_ID")
    || CONTAINS(STR(?o), "MembershipCredential")
    || CONTAINS(STR(?p), "isConsumer")
    || CONTAINS(STR(?p), "isProvider")
    || CONTAINS(STR(?p), "construct-x")
  )
}
ORDER BY ?s ?p ?o
EOF
)

  echo
  echo "==> Fuseki triples for MembershipCredential (RDF-star claim graph)"
  echo "    endpoint: $FUSEKI_URL"
  echo "    credentialSubject: $SUBJECT_ID"
  echo

  local json
  json="$(sparql "$query")"
  local count
  count="$(echo "$json" | jq '.results.bindings | length')"
  if [[ "$count" -eq 0 ]]; then
    echo "(no matching triples — dumping all claim triples for debugging)"
    json="$(sparql 'PREFIX cred: <https://www.w3.org/2018/credentials#>
SELECT ?s ?p ?o ?cs WHERE {
  <<(?s ?p ?o)>> cred:credentialSubject ?cs .
} LIMIT 50')"
    count="$(echo "$json" | jq '.results.bindings | length')"
  fi

  echo "$json" | jq -r '
    def term:
      if .type == "uri" then "<\(.value)>"
      elif .datatype then "\"\(.value)\"^^<\(.datatype)>"
      else "\"\(.value)\""
      end;
    .results.bindings[]
    | "  << \(.s|term) \(.p|term) \(.o|term) >>\n"
      + "    <https://www.w3.org/2018/credentials#credentialSubject> \(.cs|term)\n"
  '

  echo "==> $count triple(s)"
}

# --- main --------------------------------------------------------------------

echo "==> Authenticating as $FC_USER…"
TOKEN="$(get_token)"
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "error: failed to obtain Keycloak access token" >&2
  exit 1
fi

ENCODED_ID="$(urlencode "$ASSET_ID")"

echo "==> Clearing prior asset (idempotent): $ASSET_ID"
curl -sf -o /dev/null -X DELETE "$API_URL/assets/by-id/$ENCODED_ID" \
  -H "Authorization: Bearer $TOKEN"

echo "==> POST /assets  (MembershipCredential VCDM 2.0)"
HTTP_CODE="$(curl -sS -o /tmp/fc-membership-post.json -w '%{http_code}' \
  -X POST "$API_URL/assets" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/ld+json' \
  --data-binary @"$FIXTURE")"

if [[ "$HTTP_CODE" != "201" && "$HTTP_CODE" != "200" ]]; then
  echo "error: POST /assets failed with HTTP $HTTP_CODE" >&2
  cat /tmp/fc-membership-post.json >&2 || true
  exit 1
fi

echo "    HTTP $HTTP_CODE"
jq . /tmp/fc-membership-post.json 2>/dev/null || cat /tmp/fc-membership-post.json
echo

sleep 1

print_triples

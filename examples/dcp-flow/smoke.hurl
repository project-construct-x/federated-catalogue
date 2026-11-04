# Every occurrence is replaced with a DIFFERENT signed SI token by prepare-hurl.
GET {{base_url}}/query/info
Authorization: Bearer {{dcp_token}}
HTTP 200

GET {{base_url}}/query/info
Authorization: Bearer {{dcp_token}}
HTTP 200

POST {{base_url}}/assets
Authorization: Bearer {{dcp_token}}
Content-Type: application/vc+jwt
file,.state/asset.jwt;
HTTP 201

POST {{base_url}}/query
Authorization: Bearer {{dcp_token}}
Content-Type: application/sparql-query
```
SELECT ?s WHERE { <<(?s <https://example.test/dcp-flow/bpn> "BPNL000000000001")>> <https://www.w3.org/2018/credentials#credentialSubject> ?s }
```
HTTP 200
[Asserts]
body contains "urn:uuid:"

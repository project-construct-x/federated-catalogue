POST http://key-server:8080/realms/federated-catalogue-realm/protocol/openid-connect/token

[Form]
grant_type:password
client_id:federated-catalogue
client_secret:**********
username:fc-ca-test
password:CHANGE_ME_dev_only1

HTTP 200

[Captures]
admin_token: jsonpath "$.access_token"




POST {{base_url}}/schemas
Authorization: Bearer {{admin_token}}
Content-Type: text/turtle

file,modeling-kit/shapes.ttl;

HTTP 201

[Captures]
schema_id: jsonpath "$.id"




GET {{base_url}}/schemas/{{schema_id}}
Authorization: Bearer {{admin_token}}

HTTP 200
[Asserts]
body contains "gx:LegalPerson"




POST {{base_url}}/query
Authorization: Bearer {{dcp_token}}
Content-Type: application/sparql-query
Accept: application/sparql-results+json

```
PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX cx:   <https://w3id.org/catenax/ontology/participant#>

SELECT DISTINCT ?did
WHERE {
  <<(?did cx:bpn "BPNL000000000111")>>
      cred:credentialSubject ?did .
}
```

HTTP 200

[Asserts]
jsonpath "$.results.bindings" count > 0
jsonpath "$.results.bindings[0].did.type" == "uri"
jsonpath "$.results.bindings[0].did.value" == "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1"




POST {{base_url}}/query
Authorization: Bearer {{dcp_token}}
Content-Type: application/sparql-query
Accept: application/sparql-results+json

```
PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX gx:   <https://w3id.org/gaia-x/2511#>

SELECT DISTINCT ?did
WHERE {
  <<(?did gx:legalName "ACME GmbH")>>
      cred:credentialSubject ?did .
}
```

HTTP 200

[Asserts]
jsonpath "$.results.bindings" count > 0
jsonpath "$.results.bindings[0].did.type" == "uri"
jsonpath "$.results.bindings[0].did.value" == "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1"




POST {{base_url}}/query
Authorization: Bearer {{dcp_token}}
Content-Type: application/sparql-query
Accept: application/sparql-results+json

```
PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX gx:   <https://w3id.org/gaia-x/2511#>
SELECT DISTINCT ?did
WHERE {
  <<(?did gx:legalAddress ?addr)>>
      cred:credentialSubject ?did .

  <<(?addr gx:countryCode "DE")>>
      cred:credentialSubject ?did .
}
```

HTTP 200

[Asserts]
jsonpath "$.results.bindings" count > 0
jsonpath "$.results.bindings[0].did.type" == "uri"
jsonpath "$.results.bindings[0].did.value" == "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1"




POST {{base_url}}/query
Authorization: Bearer {{dcp_token}}
Content-Type: application/sparql-query
Accept: application/sparql-results+json

```
PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX prov: <http://www.w3.org/ns/prov#>
PREFIX cx:   <https://w3id.org/catenax/ontology/participant#>

SELECT DISTINCT ?did
WHERE {

  <<(?did cx:bpn "BPNL000000000111")>>
      cred:credentialSubject ?did .

  FILTER NOT EXISTS {
    <<(?newer prov:wasDerivedFrom ?did)>>
        cred:credentialSubject ?newer .
  }
}
```

HTTP 200

[Asserts]
jsonpath "$.results.bindings" count > 0
jsonpath "$.results.bindings[0].did.type" == "uri"
jsonpath "$.results.bindings[0].did.value" == "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1"

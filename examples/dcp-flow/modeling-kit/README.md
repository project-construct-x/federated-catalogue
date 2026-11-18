# Construct-X Registry Modeling Kit

This modelling kit adds:

- LegalPerson example credential
- SHACL example registry schema
- SPARQL example discovery queries
- Hurl-based discovery examples

The DCP infrastructure is provided by the surrounding [DCP flow example](../README.md).

## Contents

| File | Purpose |
|------|---------|
| `legal-person.jsonld` | Example LegalPerson credential containing legal name, address and BPN |
| `shapes.ttl` | SHACL shape for BPN validation and DID-backed subjects, uploaded through `/schemas` |
| `construct-x-registry-demo.hurl` | Registry discovery queries (BPN → DID, name → DID, country → companies, latest-approved pattern) |

### Start the Stack
From the repository root:
```
docker compose -f examples/dcp-flow/compose.yaml -f examples/dcp-flow/compose.admin.yaml down -v --remove-orphans

mvn -pl fc-service-server -am clean package '-DskipTests' '-Dskip.npx=true' '-Dcheckstyle.skip=true'

docker compose -f examples/dcp-flow/compose.yaml build fixtures

docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures init

docker compose -f examples/dcp-flow/compose.yaml -f examples/dcp-flow/compose.admin.yaml up -d --build

docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures smoke
```

### Signing and Publishing the fixture
Sign the Fixture:
```
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures sign-asset --input /examples/modeling-kit/legal-person.jsonld --output /state/legal-person.jwt
```

Publish:
```
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures request --method POST --path /assets --body /state/legal-person.jwt --content-type application/vc+jwt
```

### Run Hurl Queries
Prepare Hurl:
```
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures prepare-hurl --input /examples/modeling-kit/construct-x-registry-demo.hurl
```

Run Hurl:
```
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps hurl --test --file-root /examples --variables-file /examples/.state/prepared.env /examples/.state/prepared.hurl
```

### Notes
The local DCP fixture stack operates with semantics-only verification and does not enable optional SHACL enforcement.
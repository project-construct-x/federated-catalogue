# Shared local DCP flow test environment

Run these commands **from the repository root**, on the branch you want to test.
Requires Docker Compose v2.24+ and Java 25/Maven for building the catalogue.
No Python or Hurl installation on the host is needed.

The separate Compose project `fc-dcp-flow` owns its own PostgreSQL, Fuseki and asset
volumes. It does not reuse or modify the normal `docker/dev.env` deployment.
Only the catalogue is exposed, on **http://localhost:18081**, bound to loopback.
No Keycloak login, auth bypass or mocked catalogue authentication is used.

## Start

```sh
mvn -pl fc-service-server -am package '-DskipTests' '-Dskip.npx=true' '-Dcheckstyle.skip=true'
docker compose -f examples/dcp-flow/compose.yaml build fixtures
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures init
docker compose -f examples/dcp-flow/compose.yaml up -d --build
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures smoke
```

The local build skips Checkstyle to avoid CRLF line-ending warnings on Windows.
This skips all Checkstyle checks for this invocation; CI checks remain unchanged.

`init` creates three independent Ed25519 key pairs, DID documents, a local TLS CA,
and the catalogue environment file under `.state/`. Existing keys are preserved.
Private keys/tokens are ignored by Git. The fixture image does not contain them;
the catalogue image contains only the public local CA. Rebuild the catalogue image
and recreate the services after intentionally changing the CA or identity keys.
These are disposable **local test identities**, never production credentials.

The catalogue is built from the local JAR, so rebuild the JAR and image after Java
changes. The first build needs network access for images/dependencies. The test
server is a deliberately narrow DCP fixture, not a full wallet or production issuer.
The shared test-only membership uses an inline example vocabulary; this does not
claim to validate the canonical remote-context modelling-kit fixtures.

## What happens on each request

1. The runner signs a new participant SI token (`iss=sub`, configured `aud`, `exp`, new `jti`).
2. The catalogue resolves `did:web:dcp-fixtures:participant` over trusted local HTTPS.
3. It verifies the SI signature and consumes the replay identifier in its real database.
4. It queries the participant's Credential Service at `https://dcp-fixtures/presentations/query`.
5. The test service validates the catalogue's own SI signature, audience, issuer,
   subject, expiry, forwarded opaque access grant and replay identifier.
6. It returns a participant-signed JWT VP containing an issuer-signed MembershipCredential.
7. The catalogue's real DCP/VC verifier authenticates the request and executes the operation.

| Identity | DID | Purpose |
|---|---|---|
| Issuer | `did:web:dcp-fixtures:issuer` | Signs the membership, trusted by catalogue configuration |
| Participant | `did:web:dcp-fixtures:participant` | SI tokens, presentations and self-published assets |
| Catalogue | `did:web:dcp-fixtures:catalogue` | SI tokens for outbound Credential Service calls |

The audience is `http://catalogue:8081` even when a host-side client accesses
`http://localhost:18081`. Audience is an agreed identifier, not inferred from the request URL.
All DID/CS addresses are internal Docker names; external wallets cannot resolve them without
their own networking/trust configuration. No TLS verification is disabled.

## Hurl

```sh
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures asset --output /state/asset.jwt
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures prepare-hurl
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps hurl --test --file-root /examples --variables-file /examples/.state/prepared.env /examples/.state/prepared.hurl
```

The supplied `smoke.hurl` authenticates two separate reads, uploads a signed reference
credential and queries its BPN claim through SPARQL. `prepare-hurl` replaces **each**
`{{dcp_token}}` occurrence with a distinct token variable; the original Hurl file stays
unchanged. Run it immediately before Hurl (tokens expire in ten minutes).
Do not use Hurl `--retry` or `--repeat` with prepared tokens: prepare a new set for each run.
Long-running scenarios should use the `request` command per operation instead.

For your own file inside this directory:

```sh
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures prepare-hurl --input /examples/my-scenario.hurl
```

Use `{{base_url}}` and **one `Authorization: Bearer {{dcp_token}}` per request**.
File bodies are relative to `/examples` because Hurl uses `--file-root /examples`.
Prepared variable files contain bearer credentials: do not commit or publish them, and
avoid verbose request logging. The explicit `token` command prints a single token for
manual clients; consume it only once, including after failed downstream operations.

## Test your own modelling claims

Put your JSON-LD VC in this directory. Set its issuer to
`did:web:dcp-fixtures:participant`, keep the intended credentialSubject and vocabulary,
then sign it without changing its claims:

```sh
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures sign-asset --input /examples/my-reference.jsonld --output /state/my-reference.jwt
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures request --method POST --path /assets --body /state/my-reference.jwt --content-type application/vc+jwt
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps fixtures request --path /query/info
```

`request` generates a fresh SI token every invocation; the uploaded VC is separate
from the membership used to authenticate. It prints the HTTP status and API response.
The signing helper refuses other issuers rather than silently rewriting the fixture.
Validity dates and contexts supplied by your fixture are preserved and must be valid/reachable.
This fixture stack does not enable optional SHACL/trust-framework policies or membership
revocation/status-list checking. Enable those separately when testing those requirements.

The current application permits self-publishing: the asset issuer must equal the
authenticated participant DID. The membership issuer is intentionally a different DID.
Uploading that membership itself as a participant-owned asset is therefore not the positive
test case. Third-party-issued registry assets need the separately agreed ownership policy.
Schema management stays admin-only; this stack intentionally has no Keycloak admin setup.

## Checks and troubleshooting

```sh
docker compose -f examples/dcp-flow/compose.yaml run --rm --no-deps --entrypoint python fixtures -m unittest -v test_flow
docker compose -f examples/dcp-flow/compose.yaml ps
docker compose -f examples/dcp-flow/compose.yaml logs --tail 100 catalogue
```

The Python smoke runner waits for catalogue health, then checks missing/expired/wrong-audience
SI tokens, wrong membership issuer/subject, expired membership, invalid VC signature, valid DCP,
SI replay, signed upload and discovery of the exact uploaded subject. Successful runs leave
their assets in this isolated stack for inspection; each fixture has a new subject ID.

401: invalid proof/replay. 403: authenticated caller lacks resource access. 503: authentication
configuration or infrastructure unavailable. Check container clocks, local CA, DIDs, configured
issuer/audience and schema migrations. Old prepared Hurl tokens must be regenerated.

Stop the stack without deleting its data:

```sh
docker compose -f examples/dcp-flow/compose.yaml down
```

`down --volumes` deliberately deletes **this test project's** database, graph and assets.
It does not delete `.state/` keys. Keep replay records while still-valid SI tokens may be reused;
generate fresh tokens after any reset. Avoid using these fixture credentials outside this stack.

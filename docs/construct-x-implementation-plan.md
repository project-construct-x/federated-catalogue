# Construct-X implementation plan

Make one Federated Catalogue deployment serve as **catalogue** (assets / offerings / credentials) and **company registry** (DID ↔ secondary identifiers) for Construct-X, without a separate registry microservice.

This plan turns the modelling in [`company-identifier-references.md`](./company-identifier-references.md) into concrete delivery work, given how the catalogue actually works today.

## Current baseline (constraints)

| Fact | Implication for Construct-X |
|------|-----------------------------|
| Credentials arrive only via **HTTP REST** (`POST /assets`, `POST /participants`, `POST /verification`) | Construct-X write path should evolve to **DCP presentation** (holder proves right-to-publish), then hand off to the same verify/store pipeline |
| JWT-VC/VP formats are accepted; LD proofs are rejected | Issue Construct-X credentials as **standard JWT-VC** (or Gaia-X Loire JWT if co-aligned) |
| “IDSA/DCP trust frameworks” in docs means **payload shape compatibility**, not a DCP wire protocol | Add a real DCP verifier via [EECC dcp](https://github.com/european-epc-competence-center/dcp); format support alone is not enough |
| Verification is real but **toggleable**; docker defaults enable **semantics only** (`vc-signature` / `vp-signature` often `false`) | An authoritative registry profile **must** turn signatures (and ideally schema + trust framework) on |
| Claims are projected into RDF and discovered via **`POST /query`** / **`POST /query/search`** | Registry lookups are SPARQL (or a thin façade over it), not a new graph store |
| Dual store: **Postgres** (signed assets + lifecycle) + **graph** (active claims; Fuseki default / Neo4j optional) | Keep both; Construct-X discovery contract is **Fuseki + SPARQL** — see [Storage and query architecture](#storage-and-query-architecture-findings) |
| Auth on `keycloak-reduction` | **Keycloak is application-admin only** (`ADMIN_ALL`). **Every protected machine operation requires a fresh DCP proof** (Self-Issued ID Token + one trusted MembershipCredential). **OID4VP initial-connector bootstrap is still unimplemented**; `/api/auth/oid4vp/**` is denied. See [Auth delivery status](#auth-delivery-status-keycloak-reduction) |

**Dual-role principle:** keep one API surface. Catalogue assets and registry reference credentials share ingest, verification, versioning, and query. Differ only by vocabulary, SHACL, and operator policy.

**Auth principle:**

| Actor | AuthN | AuthZ |
|-------|-------|-------|
| **Machine operations** (connectors, automated catalogue/registry I/O) | **Always DCP** — present Construct-X **MembershipCredential** ([EECC dcp](https://github.com/european-epc-competence-center/dcp)) | Credential type / issuer / trust policy (`MembershipCredential`) — **not** Keycloak roles, **not** OID4VP |
| **Humans — initial connector authentication only** | **OpenID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) presenting the same **MembershipCredential**; may mint a **catalogue access token** (not Keycloak) to bind/bootstrap the connector | Same membership / issuer policy |
| **Application admins** only | Keycloak OIDC login (Bearer JWT) | Single admin capability (e.g. `ADMIN_ALL`) for ops IAM, `/admin/**`, break-glass |

No Keycloak accounts for catalogue users or connectors. No `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` permission matrix. OID4VP is **not** the protocol for ongoing machine traffic. The machine/admin split below is implemented; the OID4VP bootstrap row is still the target, not the running server.

## Auth delivery status (`keycloak-reduction`)

Branch `keycloak-reduction` (ahead of `dev`) implements work packages A–E for the Keycloak/DCP split. Wallet and Credential Service interoperability, example migration, and OID4VP bootstrap remain open. Detail lives in:

- [Keycloak administration boundary](keycloak-admin-boundary.md)
- [Endpoint access inventory](endpoint-access-inventory.md)
- [DCP machine authentication](dcp-asset-authentication.md)
- [Catalogue-owned participants](dcp-participant-storage.md)

### Implemented

| Area | What the server does now |
|------|--------------------------|
| Realm | Dev, staging, prod, and Helm `fc-realm.json` keep one client role, `ADMIN_ALL`. Fine-grained and composite catalogue roles are gone. Direct-access grants and Keycloak Authorization Services are off. Interactive admin login is the authorization-code flow. |
| Keycloak service account | The `federated-catalogue` client has a service account with `realm-management/manage-users` and `view-clients` so the catalogue can call the Keycloak Admin API. It does not have `ADMIN_ALL` or `realm-admin`. A service-account token does not open catalogue admin or machine APIs. |
| Admin APIs | Keycloak JWT with `resource_access.<client>.roles` containing `ADMIN_ALL` is required for `/admin/**`, protected Actuator routes, `/schemas/**` (including reads), `/users/**`, `/roles`, and `/session`. Other clients, realm roles, and scopes grant nothing. |
| Machine APIs | All 35 inventory operations require `DcpAuthenticationToken`: assets, participants, query/discovery (including `GET /query`), verification, provenance, compliance, trust-framework listing, and validation reads. The asset-only opt-in switch is removed. Missing audience or trusted issuer, or an unavailable replay database, fails closed (503). A rejected proof is 401. |
| DCP proof | Each request carries a fresh Self-Issued ID Token (`Authorization: Bearer`). Signature uses the DID `capabilityInvocation` key, with `iss == sub`, configured audience, `exp`/`jti`, and zero clock skew. `(issuer, jti)` is consumed in PostgreSQL (`dcp_used_si_tokens`, hashes only) before the Credential Service is contacted. The pull must yield exactly one signed presentation and exactly one `MembershipCredential` whose issuer is the configured trust anchor and whose subject equals the holder. Lab signature toggles do not weaken these checks. |
| Ownership | Self-publish only. Asset issuer must equal the membership subject DID. Participant create/update/delete/read is limited to that DID; the collection returns at most the caller's own record. Path, body, and header participant ids do not authenticate the caller. Delegation and third-party publishing are rejected. |
| Participants | Liquibase `024` stores participants in `catalogue_participants`, keyed by DID. Keycloak groups are not participants. `GET /participants/{id}/users` is admin-only and returns 410. Admin `participantId` is ignored. The migration starts empty; existing installations re-register through the DCP participant path. |
| Audit | Envers `revinfo` stores `participant_did`, `actor_did`, and `authentication_method=DCP` (changeset `023`). Only a real `DcpAuthenticationToken` supplies them. An MVC interceptor logs one JSON `dcp_operation` event per completed machine call, without bodies, tokens, or query strings. Security-chain rejections do not become those events. |
| Portal | `fc-demo-portal` OIDC login requires `ADMIN_ALL`. |
| Tests and contract | OpenAPI `x-access-category` is lint-enforced. `KeycloakBoundaryTest` and `DcpMachineSecurityTest` exercise the real filter chains for the classified operations, including rejection of Keycloak and bootstrap-style JWTs. Replay, ownership, identity propagation, and realm shape have targeted tests. |

Under the current policy the authenticated actor DID and the membership subject DID are the same value: the Self-Issued holder. `isConsumer`, `isProvider`, and credential status lists are not authorization checks yet. The historical property name `POST_ASSETS` is the shared membership policy for every machine operation.

### Still open

- **OID4VP** initial connector authentication and the catalogue bootstrap token. `/api/auth/oid4vp/**` is a deny-all placeholder.
- **Credential status** (BitstringStatusList) and **membership flags** as authorization policy.
- **Delegation** / publishing credentials whose issuer is not the caller.
- **Live wallet and Credential Service** acceptance. Current DCP tests use local fixtures and isolate DID resolution and VC/VP cryptography.
- **Example and hurl migration.** Legacy scenarios still request a Keycloak password grant, which the slim realm rejects. `examples/construct-x-registry-demo/README.md` still describes Keycloak writes as the lab default.
- **Existing realm updates.** `--import-realm` does not rewrite a realm already stored in Keycloak. Operators follow [keycloak/realms/README.md](../keycloak/realms/README.md).
- **Registry modelling** (Phases 0–5): vocabulary, LegalPerson/BPN fixtures, SHACL, strict profile, federation, and [CX-BUG-1](#known-bugs--issues-claim-extraction--json-ld-contexts).

### Decisions locked by this branch

These were open choices in the original work packages. Later work should keep them.

1. **Schema management, including reads, stays Keycloak admin.** It is not a DCP machine operation.
2. **Query and discovery stay DCP-protected.** They are not public and not an admin capability. An administrator token does not execute catalogue queries.
3. **There is no long-term dual-auth flag.** Machine routes have no Keycloak fallback and no asset-only switch.
4. **Participants live in PostgreSQL.** They are not Keycloak groups, and the migration does not import groups as trusted identities.
5. **Reads of catalogue assets and SPARQL are open to every verified member.** Writes and participant records are ownership-scoped.

---

## Goals

1. **Registry:** resolve `BPN → DID`, `legal name → DID`, `address/country → DID`, and (optionally restricted) `IBAN → DID` from signed claims.
2. **Catalogue:** continue to host Construct-X service offerings / other assets in the same node.
3. **Authoritative mappings:** operators can run a profile where VC signatures and shapes are enforced before store.
4. **DCP for machines; OID4VP only for initial connector users; Keycloak for admins.** **Done:** machine operations authenticate via DCP with a Construct-X **MembershipCredential**, and Keycloak is limited to **application admins** with no fine-grained role matrix. **Open:** **OID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) for **initial connector authentication**, including an optional catalogue bootstrap token.
5. **Operable without new core query APIs** for v1; optional convenience resolve endpoints only if SPARQL ergonomics block adoption.
6. **Federated discovery:** partner catalogues can answer cross-node identifier lookups via existing `query.partners` / `POST /query/search`.

Non-goals for v1:

- Issuing user/connector access tokens from **Keycloak** (OID4VP session tokens are catalogue-issued after MembershipCredential presentation, and only for initial connector authentication)
- Using **OID4VP as the machine protocol** (connectors always speak DCP after bootstrap)
- Reimplementing OpenID4VP or DCP wire protocol (use EECC [oid4vp](https://github.com/european-epc-competence-center/oid4vp) and [dcp](https://github.com/european-epc-competence-center/dcp))
- Keeping Keycloak as a general user directory or permission engine for catalogue writers/readers
- Replacing Tractus-X BDRS API 1:1 (directory dump + MembershipCredential bearer) unless Construct-X explicitly requires that contract
- Storing secrets (IBAN) in a publicly queryable graph without an access model
- Collapsing document storage into the graph (graph-only catalogue) or replacing Postgres with JSONB-as-graph
- Making openCypher / Neo4j the Construct-X integrator contract (Neo4j remains optional ops backend)

---

## Requirements traceability

Mapped from [`company-identifier-references.md`](./company-identifier-references.md):

| ID | Requirement | Acceptance |
|----|-------------|------------|
| CX-R1 | Company **DID** is the anchor (`credentialSubject.id`) | Fixture + query prove one subject IRI per company |
| CX-R2 | Secondary identifiers as RDF predicates (name, address, BPN, IBAN, VAT/LEI) | SPARQL examples return expected DID |
| CX-R3 | Custom vocabulary via `@context` (reuse Catena-X / schema.org / Gaia-X where useful) | Context documented; shapes know the IRIs |
| CX-R4 | Ingest via `POST /assets` (`application/vc+jwt` or `application/ld+json` where supported) | Hurl demo publishes and discovers |
| CX-R5 | Split model allowed: Participant VC + lightweight Reference VC | Both patterns in examples |
| CX-R6 | **Machines** authenticate with **MembershipCredential over DCP**; **OID4VP only** for **initial connector authentication**; Keycloak tokens do not authorize catalogue writes | **Done for machines:** DCP proof required on machine writes and reads; Keycloak-only and bootstrap-style bearers rejected. **Open:** human OID4VP bootstrap, live wallet/Credential Service acceptance, hurl migration |
| CX-R6a | Keycloak **application-admin only**; no complex role/permission matrix | **Done** in shipped realm JSON and portal/server authorization: single `ADMIN_ALL`; fine-grained and `Ro-*` roles removed. Existing imported realms still need the operator update in `keycloak/realms/README.md` |
| CX-R7 | Authoritative mode: signatures + trust-framework (+ optional compliance) | Strict profile rejects unsigned / bad issuer |
| CX-R8 | Updates via versions / provenance; stale mappings supersedable | Version query prefers latest approved |
| CX-R9 | Sensitive fields (IBAN) not casually public | Separate asset / DCP presentation policy / omit from public shapes |
| CX-R10 | Optional SHACL for BPN/IBAN via `POST /schemas` | Invalid BPN rejected when schema on |
| CX-R11 | Federation via `federated-catalogue.query.partners` | Cross-node BPN→DID via `POST /query/search` |

Catalogue co-existence (implicit):

| ID | Requirement | Acceptance |
|----|-------------|------------|
| CX-C1 | Same node hosts catalogue assets and registry VCs | No schema conflict; shared verification toggles understood |
| CX-C2 | Registry queries do not break existing Gaia-X / DCS query demos | Regression hurl still green |

---

## Target architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                 Construct-X Federated Catalogue               │
│                                                               │
│  Humans — initial connector authentication only               │
│       │  OpenID4VP (EECC oid4vp)                              │
│       │  present MembershipCredential                         │
│       │  → optional catalogue access token (not Keycloak)     │
│       │    to bind / bootstrap the connector                  │
│                                                               │
│  Machine operations (connectors) — always                     │
│       │  DCP (EECC dcp)                                       │
│       │  present MembershipCredential                         │
│       ▼                                                       │
│  AuthN/AuthZ façade (membership / issuer policy)              │
│       │                                                       │
│       ▼                                                       │
│  Verification (strict profile) + ingest / query               │
│       │                                                       │
│       ├──► Postgres (signed asset + lifecycle)  [source of truth]
│       └──► Fuseki (active claims / RDF-star)  [discovery index] │
│            (Neo4j optional; not Construct-X query contract)    │
│                                                               │
│  Application admins only                                      │
│       │  Keycloak OIDC (single ADMIN_ALL-class role)          │
│       ▼                                                       │
│  /admin/** , ops IAM, graph rebuild / backend switch, …       │
└─────────────────────────────────────────────────────────────┘
```

The DCP and Keycloak boxes match `keycloak-reduction`. The OpenID4VP box is still a target: those routes are denied until bootstrap is implemented.

### Target auth model (hard requirements)

| Who | How they authenticate | What they may do | Keycloak? |
|-----|----------------------|------------------|-----------|
| **Machine operations** (connectors) | **Always DCP** + **MembershipCredential** | Catalogue/registry data APIs (`POST` assets / participants / query as policy allows); authZ from membership type + issuer trust | **No** |
| **Humans (initial connector authentication only)** | **OID4VP** + **MembershipCredential**; may mint a **catalogue access token** to bind the connector | Bootstrap / bind connector — **not** ongoing machine I/O | **No** |
| **Application admins** | Keycloak login only | `/admin/**`, residual admin ops (stats, trust-framework config, graph rebuild, …) | **Yes — only them** |

Hard rules:

1. **No Keycloak login for users or connectors.** Writers never obtain `ASSET_CREATE` (or any fine-grained role) from a realm. Connector and user Bearer tokens are **not** Keycloak access tokens.
2. **No complex roles/permissions in Keycloak.** Delete the fine-grained + composite catalogue (`ASSET_*`, `SCHEMA_*`, `QUERY_*`, `Ro-MU-*`, `Ro-AS-A`, `Ro-PA-A`, convenience composites like `asset-creator`, Gaia-X claim remaps). Keep **one** application-admin role (e.g. `ADMIN_ALL`).
3. **Machine operations always use DCP.** Connectors present **MembershipCredential** over DCP for every catalogue/registry operation. OID4VP is **not** an alternative machine protocol.
4. **OID4VP is only for humans doing initial connector authentication.** After a verified MembershipCredential presentation, the catalogue **may issue a longer-lived access (and optional refresh) token** to bind/bootstrap the connector. That token does not replace DCP for subsequent machine traffic.
5. Keycloak JWT must **not** be accepted as sufficient for Construct-X data APIs in the authoritative profile.
6. Map former “permissions” onto **VC types / issuers / presentation scopes** (membership flags such as `isConsumer` / `isProvider`), not realm role names.

Recommended credential split (CX-R5):

| Credential | Issuer | Subject | Claims | Role |
|------------|--------|---------|--------|------|
| **MembershipCredential** | Construct-X onboarding issuer (e.g. `did:web:issuer.int.construct-x.net:issuer`) | holder / wallet DID | `isConsumer`, `isProvider`; `credentialStatus` (BitstringStatusList) | **AuthN/AuthZ for machines and initial connector users** — required in every **DCP** machine presentation and every **OID4VP** initial-connector login. After OID4VP verification, the catalogue may mint a bootstrap token bound to this credential. **Default encoding: VCDM 2.0** (`membership-credential-v2.jsonld`); VCDM 1.1 kept for issuer compatibility |
| **Participant / LegalPerson VC** | Company or notary | company DID | `schema:name`, `gx:legalAddress`, `gx:legalRegistrationNumber` | Registry / catalogue claims (discovery) |
| **Identifier Reference VC** | Trusted Construct-X registry operator | company DID | `cx:bpn` (and later controlled IBAN / other refs) | Registry mapping claims (discovery) |

Catalogue assets (service offerings, DCS-style templates, …) remain ordinary assets on the same instance. Membership is **not** a substitute for LegalPerson/Reference payloads; it only proves the caller is an onboarded Construct-X member.

### Storage and query architecture (findings)

Gaia-X / XFSC catalogues use **two stores on purpose**. Construct-X inherits that; do not collapse them for v1.

#### Dual storage (document store vs Self-Description Graph)

| Store | Holds | Tech in this repo | Role |
|-------|--------|-------------------|------|
| **Document / Self-Description Storage** | Exact signed asset bytes (JSON-LD / VC-JWT) + **administrative lifecycle metadata** (active / revoked / deprecated / EOL, versions, hashes) | **PostgreSQL** (`AssetStore`) | Source of truth; clients can re-fetch the raw credential and verify proofs |
| **Self-Description Graph** | **Claims** projected from *active* credentials as a linked graph | **Fuseki** (default) or **Neo4j** | Discovery / registry lookup index; rebuildable from Postgres |

Lifecycle state is **outside** the signed payload (Gaia-X Architecture). Same JSON-LD exchange format does **not** mean one database: the graph is a **derived index**, not a replacement for the document store.

**Do not move the document store into the graph alone.** Reasons: signatures bind byte-exact payloads (graph round-trips are not proof-preserving); revoke/deprecate must drop claims without rewriting the VC; versions stay in storage while only active claims are indexed; `POST /admin/graph/rebuild` needs Postgres as rebuild source; non-RDF assets never become claims.

#### What the graph holds (use case)

Not the VC file. It holds **subject–predicate–object claims** (and typed edges between entities) extracted from `credentialSubject`, tagged so they can be deleted per asset:

- **Fuseki:** RDF-star — each claim triple annotated with `cred:credentialSubject → <asset IRI>`
- **Neo4j + n10s:** property-graph import; source tracked via `claimsGraphUri`

Concrete Construct-X / catalogue questions this answers:

1. **Registry:** `BPN → DID`, `name → DID`, `country → companies` via SPARQL on predicates such as `cx:bpn`, `schema:name`, `gx:legalAddress`
2. **Catalogue discovery:** offerings by provider / keyword / `gx:dependsOn` chains across many VCs
3. **Relationship / policy filters:** e.g. constraints that walk hosting or dependency edges (Gaia-X Self-Description Graph pattern)
4. **Federation:** same claim patterns on partner nodes via `POST /query/search`

#### Why not PostgreSQL alone for discovery

Postgres is **necessary** for documents and lifecycle; it is **not sufficient** as the only discovery store:

| Need | Graph + SPARQL/Cypher | Postgres alone |
|------|----------------------|----------------|
| Multi-hop joins across SDs | Native pattern match | Recursive CTEs / fragile joins |
| Evolving / federated vocabularies | New triples, no migration | Schema churn or opaque JSONB |
| Claim + credential provenance together | RDF-star / edge metadata | Extra tables, awkward queries |
| Interop with JSON-LD / SHACL / VC ecosystem | Native | Constant mapping |

JSON-in-Postgres would reimplement a weaker graph store and lose standard SPARQL clients and Gaia-X alignment.

#### Graph backend preference (Construct-X)

| Backend | Query language | Fit |
|---------|----------------|-----|
| **Apache Jena Fuseki** (preferred) | SPARQL / SPARQL-star | Native RDF; matches claim projection, SHACL, and Construct-X registry examples; compose default `GRAPHSTORE_IMPL=fuseki` |
| **Neo4j + n10s** (keep available) | openCypher | Historic XFSC/GXFS path; Browser / GDS; useful for switch+rebuild demos — not the Construct-X contract |

**Decision:** Construct-X authoritative profile uses **Fuseki + SPARQL** as the discovery contract. Keep Neo4j in the stack for admin switch / rebuild; do not dual-write or require Cypher of Construct-X integrators. Optional `GET /registry/resolve` façades (Phase 6) stay thin SPARQL wrappers.

---

## Strip Keycloak to a minimum (concrete work)

The pre-strip server authorized almost everything by URL ↔ Keycloak role in `SecurityConfig`, scoped callers with JWT `participant_id` in `SessionUtils`, and stored participants through the Keycloak Admin API. `keycloak-reduction` replaces the machine half with **MembershipCredential over DCP** and leaves Keycloak as an **admin-only** IdP plus a narrow service account for user administration. **OID4VP initial connector authentication** (optional catalogue bootstrap token) is still future work; its routes are denied.

### Target end state

| Area | Before | Target | On `keycloak-reduction` |
|------|--------|--------|-------------------------|
| Who has Keycloak accounts | Admins, participant admins, asset operators, readers, test users | **Application admins only** | Shipped realms: one admin role. A **service account** exists for the Keycloak Admin API and is not a catalogue user |
| How machines operate | Bearer JWT + `ASSET_CREATE` / `Ro-*` | **Always DCP** + **MembershipCredential** | **Done** for all 35 machine operations |
| How humans bind a connector | Keycloak login / password-grant | **OID4VP** + MembershipCredential (optional catalogue access token); **not** used for ongoing machine I/O | **Open.** `/api/auth/oid4vp/**` denies all requests |
| Realm roles | Fine-grained CRUD + composites + Gaia-X remaps | **One admin role** (`ADMIN_ALL`); no permission matrix | **Done** in realm JSON and JWT conversion |
| `/users`, `/roles`, participant user admin via Keycloak | First-class | Admin-only residual; participants are not Keycloak groups | **Done.** `/users` and `/roles` require `ADMIN_ALL`. Participants are catalogue rows. `GET /participants/{id}/users` returns 410 |
| Demo portal OIDC login for publish | Used by writers | Admin UI only; connector bootstrap is OID4VP; publish is DCP | Portal login is **admin-only**. Publish is **DCP**. OID4VP bootstrap is **open** |

### Work package A — Realm & config (remove permission complexity)

**Done.**

1. **Slim realm JSON** (`keycloak/realms/{dev,staging,prod}/fc-realm.json`, Helm `fc-realm.json`):
   - Client roles `ASSET_*`, `SCHEMA_*`, `QUERY_EXECUTE`, `Ro-MU-CA`, `Ro-MU-A`, `Ro-AS-A`, `Ro-PA-A`, and convenience composites are removed. `Ro-MU-CA` was not kept as an alias.
   - The only application role is `ADMIN_ALL`.
   - Non-admin seed users are removed. The dev realm keeps one local administrator.
   - Direct-access grants and Keycloak Authorization Services are disabled.
   - The client service account is enabled with `manage-users` and `view-clients` only.
2. **`CommonConstants`:** fine-grained role constants are removed. Admin constants remain for Keycloak-gated paths.
3. **`CustomJwtAuthenticationConverter`:** grants only `ADMIN_ALL` from `resource_access` of the configured client. Gaia-X claim remapping is gone.
4. **Docs / OpenAPI:** machine operations are classified `dcp-machine`; admin operations `administration`. Operator text is in the auth docs linked above and in `keycloak/realms/README.md`. Example hurl files still describe password-grant calls and are tracked under work package E.

### Work package B — Spring Security split (admins vs OID4VP bootstrap vs DCP machines)

**DCP and admin chains are done. OID4VP integration is open.**

Touch: `fc-service-server/.../config/SecurityConfig.java`, `DcpMachineSecurityConfig`, `DcpMachineEndpoints`.

Running chains, first match wins:

1. Public GET resources (health, API docs, static files) and OPTIONS.
2. **DCP machine chain** (`DcpMachineAuthenticationFilter` + `DcpAuthenticationToken`) for the explicit method/path allowlist shared with authorization.
3. **OID4VP prefix** `/api/auth/oid4vp/**`: deny-all until the bootstrap protocol lands.
4. **Admin chain:** stateless OAuth2 resource server; caller must be a `JwtAuthenticationToken` with `ROLE_ADMIN_ALL`. Covers `/admin/**`, protected Actuator, `/schemas/**`, `/users/**`, `/roles`, `/session`, and the retired participant-user route.
5. Broader DCP matcher: `POST /dcp/presentations` is permitted through to its controller, which validates the client Self-Issued ID Token. Other methods on machine prefixes that are outside the allowlist are denied.
6. Fallback deny.

**Done**

- Keycloak JWTs never authorize the 35 machine operations, including when an admin session is already present. A DCP proof on one request does not authorize the next request.
- Schema management, including reads, is admin-owned.
- Query and discovery, including `GET /query`, require DCP. They are not public.
- Role matchers `ASSET_*`, `QUERY_*`, and `Ro-*` are gone from the server security chains.

**Still to build — OID4VP, initial connector authentication only.** Integrate [EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp) (`de.eecc.oid4vc:oid4vp`). Do not reimplement presentation requests, `direct_post`, or `response_code`. Do not put OID4VP on ingest or query paths, and do not let a bootstrap token satisfy `DcpMachineAuthenticationFilter`.

- **When:** a human authenticates a connector for the first time (wallet presents **MembershipCredential**).
- **Presentation request:** DCQL asks for Construct-X `MembershipCredential` from the trusted issuer. Status-list and `isConsumer` / `isProvider` checks stay optional until that policy is explicitly enabled. `verifierUrl` points at a compatible verifier (recommended: [EECC VC Verifier](https://github.com/european-epc-competence-center/vc-verifier)).
- **Wallet:** POSTs `vp_token` + `state` to `response_uri`; `oid4Vp.processDirectPost(...)` verifies the presentation.
- **Bootstrap token:** `DirectPostResult.issueResponseCode()`, then exchange `state` + `response_code` for catalogue **access (+ optional refresh) tokens that are not Keycloak JWTs**. Claims bind holder DID, membership issuer, and presentation id. Call `invalidateResponseCode` after redemption. Subsequent machine calls still present a fresh DCP Self-Issued ID Token.

### Work package C — Replace participant/user scoping for posters

**Done for DCP. OID4VP bootstrap-token scoping does not exist yet.**

1. **`DcpParticipantAccess` / `SessionUtils.checkParticipantAccess`:** machine ownership comes only from a verified `DcpAuthenticationToken` and its membership subject DID. JWT `participant_id` and admin roles do not pass this check. A bootstrap-token principal must not be added as a second way through this method; machine routes keep requiring DCP.
2. **`AssetService` / `AssetUploadService` / `ParticipantsService`:** machine operations call `requireDcpIdentity()`. Writes check the asset issuer or participant DID against that subject. `SecurityAuditorAware` and `DcpRevisionListener` record the membership subject and holder DID. They do not store presentation bodies. Admin JWT subjects stay on the existing entity `created_by` / `modified_by` fields.
3. **User administration** stays behind `requireApplicationAdmin()`. Catalogue participants are not assigned as Keycloak roles.
4. **`ParticipantDaoImpl`** persists `catalogue_participants` in PostgreSQL. **`UserDaoImpl`** still manages Keycloak admin accounts. Participant deletion does not delete Keycloak users.

### Work package D — DCP for machines + OID4VP for initial connector auth (pairs with Phase 6)

**Machine DCP and the admin portal gate are done. OID4VP and the example migration are open.**

1. **Done.** Machine operations authenticate through `DcpMachineAuthenticationService` on top of the existing EECC DCP verifier (`DcpVerifierService`, Self-Issued ID Token validation, presentation pull). The body of an upload stays the asset; the membership proof is pulled from the holder's Credential Service, not taken from the upload body.
2. **Open.** Integrate [EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp) only for initial connector authentication (work package B). A later bootstrap token must not authorize machine operations.
3. **Policy in force:** trusted issuer, exactly one `MembershipCredential`, subject equals SI holder, strict VC/VP verification, SI replay protection. `credentialStatus`, `isConsumer`, and `isProvider` are not checked. Unsigned presentations and inline JSON-LD credentials are not an authentication fallback.
4. **Done for DCP:** a verified `DcpIdentity` is installed for the request and cleared afterwards. Ownership and audit use that identity. The verify/store pipeline is unchanged aside from those checks. OID4VP token minting is not implemented.
5. **Open.** Examples and hurl still contain Keycloak password-grant steps. Replace those with a DCP machine-authentication fixture, and add a separate OID4VP bootstrap fixture when that protocol exists. Do not turn password grants back on.
6. **Done for the portal gate:** `fc-demo-portal` requires `ADMIN_ALL`. Publish and query demos are not a portal flow; they need the DCP example migration in item 5. Connector bootstrap remains OID4VP and is not in the portal.

### Work package E — Tests & migration

**Boundary tests, OpenAPI classification, and the slim realm are in the tree. Live interoperability and example cutover are open.**

1. **Done** for the Keycloak/DCP split: controller and security tests use admin JWTs on admin routes and DCP identities on machine routes. `KeycloakBoundaryTest`, `DcpMachineSecurityTest`, `DcpIdentityPropagationTest`, replay tests, and `KeycloakRealmConfigurationTest` cover the boundary. **Open:** OID4VP bootstrap tests, and a deployment run against a real wallet and Credential Service.
2. **No dual-auth flag was shipped.** `FEDERATED_CATALOGUE_DCP_ASSET_AUTHENTICATION_ENABLED` no longer controls access. OID4VP, when added, stays a bootstrap surface.
3. Shipped realm files are the slim admin realm. A fat realm is not kept beside them. Legacy demos that still script password grants cannot authenticate until they move to DCP. Already-imported Keycloak databases are updated by hand; import-on-start does not patch them.

### Exit criteria (Keycloak strip)

- [x] Shipped realm JSON has **no** fine-grained / `Ro-*` permission matrix — `ADMIN_ALL` only, plus the Keycloak-admin service account described above
- [x] Shipped realms have **no** catalogue-user or connector Keycloak accounts. The service account is the server's Admin API client, not a publisher. Existing databases still need the operator update
- [x] Machine APIs, including `POST /assets`, require **DCP + MembershipCredential** in filter and service tests and reject Keycloak-only bearers. OID4VP is not a machine protocol: its prefix is denied
- [ ] Initial connector authentication succeeds with **OID4VP + MembershipCredential** (optional catalogue bootstrap token) **without** a Keycloak account
- [x] Application admin authorization is `ADMIN_ALL` on `/admin/**`, with portal login restricted the same way. Tests use tokens and the security chains; a full browser login against a running Keycloak is an operator check in `keycloak/realms/README.md`
- [x] Operator docs state the implemented split: machines → DCP + MembershipCredential; admins → Keycloak `ADMIN_ALL`; no catalogue role matrix. They also state that OID4VP bootstrap is not implemented yet
- [ ] Live wallet / Credential Service acceptance, with the replay migration applied and participants registered in `catalogue_participants`
- [ ] Example and hurl suites call machine APIs with DCP proofs instead of a Keycloak password grant

---

## Implementation plan (short)

**North star:** **machine operations always use DCP + MembershipCredential**; **OID4VP is only** for **humans doing initial connector authentication** (optional **catalogue bootstrap token**); Keycloak stays **admin-only**. Registry + catalogue share one catalogue node (SPARQL discovery, strict verification profile).

| Priority | What | Outcome |
|----------|------|---------|
| **1. DCP machines** | **Done on `keycloak-reduction`:** every protected machine operation requires a fresh DCP MembershipCredential proof; Keycloak bearers do not authorize those APIs | CX-R6 machine half, CX-R6a |
| **2. Modelling kit** | Membership fixture exists. LegalPerson / BPN fixtures, SHACL, and SPARQL (BPN→DID, name, country, latest approved) are still open. Claim extraction for the issuer-shaped membership VC is [CX-BUG-1](#known-bugs--issues-claim-extraction--json-ld-contexts) | Phase 1 |
| **3. Strict registry profile** | Signatures + semantics + shapes on; negative tests for unsigned / bad issuer / bad BPN | Phase 2 authoritative ops |
| **4. Coexistence + federation** | Shared graph query hygiene; partner `query.search` for remote BPN. Query itself is already DCP-protected | Phases 3–4; CX-C1/C2, CX-R11 |
| **5. Optional resolve API** | Thin `GET /registry/resolve` only if SPARQL is a blocker | Phase 5 — defer if kit is enough |
| **6. Remaining auth** | OID4VP initial-connector bootstrap and catalogue bootstrap token; credential-status policy; hurl/examples on DCP; live wallet acceptance | Phase 6 remainder |

**Sequence from here:** finish registry fixtures and CX-BUG-1 → strict verify → query hygiene and federation → (optional façade) → **OID4VP bootstrap, then retire password-grant examples**. The Keycloak role strip and the DCP machine cutover are already on this branch.

Detail below.

## Phased delivery

### Phase 0 — Agree vocabulary and governance (docs only)

**Deliverables**

- Construct-X namespace table (reuse vs invent):
  - Prefer existing IRIs where possible (`schema:`, `gx:`, Catena-X `cx:bpn`, …)
  - Document any Construct-X-specific predicates if Catena-X IRIs are politically unwanted
- Issuer policy: who may assert `cx:bpn` (self-asserted vs registry-operator-only)
- **Write-auth credential policy:** Construct-X **`MembershipCredential` (VCDM 2.0)** — **machines present it over DCP**; **humans present it over OID4VP only when initially authenticating a connector** (may issue a catalogue bootstrap token). VCDM 1.1 JWT shape for issuer compatibility; Participant / registry-operator may be added later as additional presentation options.
- **Admin boundary (decided on `keycloak-reduction`):** Keycloak `ADMIN_ALL` for `/admin/**`, schemas (including reads), user/role/session admin, and protected Actuator. DCP for the 35 machine operations, including query. Public access is limited to health, docs, and static GET resources. Record any later change in the [endpoint inventory](endpoint-access-inventory.md) and the security matchers together
- Strict vs lab verification matrix (see Phase 2)

**Exit:** signed-off vocab + issuer + write-auth + admin-boundary policy in this repo (extend company-identifier doc or a short `construct-x-vocab.md`).

### Phase 1 — Modelling kit (examples + shapes, no core code)

**Deliverables**

1. **MembershipCredential (first / write-auth)** — fixture that later must appear in every **DCP** machine presentation and every **OID4VP** initial-connector login:
   - Type `MembershipCredential`; subject = holder/wallet DID; issuer = Construct-X onboarding issuer
   - Subject claims: `isConsumer`, `isProvider` (as issued today); `credentialStatus` = `BitstringStatusListEntry`
   - **Default: VCDM 2.0** — `examples/construct-x-registry-demo/membership-credential-v2.jsonld` (**issuer shape:** remote `@context` URLs only — `credentials/v2` + `status/v1`; no inline term map as the source of truth)
   - Compatibility: VCDM 1.1 JSON-LD + real int JWT (`membership-credential-v1.jsonld`, `membership-credential-v1.example.vc.jwt`)
   - **Blocked by [CX-BUG-1](#known-bugs--issues-claim-extraction--json-ld-contexts)** until claim extraction projects `isConsumer` / `isProvider` for that shape
2. Example JWT-VC (or signed fixture pipeline) for registry discovery:
   - LegalPerson with name + address
   - Reference VC with `cx:bpn`
   - Optional restricted IBAN VC (separate file; not used in public query demo)
3. SHACL shapes (`POST /schemas`) for BPN pattern, required `credentialSubject.id`, optional uniqueness guidance; optional membership-shape for issuer / status-list checks
4. SPARQL library (hurl), mirroring company-identifier examples:
   - BPN → DID
   - name → DID
   - country → companies
   - “latest approved” pattern (reuse DCS provenance/version approach)
5. README under `examples/construct-x-registry-demo/` (same style as `examples/dcs-template-demo/`). Update the current text that still says catalogue writes use Keycloak until a DCP façade lands: machine authentication is already required. Document **DCP for machines**. Document **OID4VP** as the still-open initial-connector bootstrap.

**Exit:** membership v2 fixture reviewed as the DCP write-auth credential (and the future OID4VP bootstrap credential); `hurl --test` against local stack publishes registry fixtures with a DCP proof and resolves BPN→DID with semantics-only verification.

### Phase 2 — Authoritative registry profile (ops + config)

**Deliverables**

1. Documented **Construct-X strict profile** (compose overlay or env preset):
   - `FEDERATED_CATALOGUE_VERIFICATION_VC_SIGNATURE=true`
   - `FEDERATED_CATALOGUE_VERIFICATION_VP_SIGNATURE=true` (if VPs used)
   - `FEDERATED_CATALOGUE_VERIFICATION_SEMANTICS=true`
   - schema validation enabled for Construct-X shapes
   - DID resolution + trust anchors available (as for `docker-compose.strict.yml`)
2. Keep the shipped admin-only realm. Do **not** add Construct-X catalogue roles back. Strict-profile docs should point at the DCP settings (`FEDERATED_CATALOGUE_DCP_AUDIENCE`, verifier DID and signing key, `FEDERATED_CATALOGUE_DCP_POST_ASSETS_REQUIRED_ISSUER`) as well as the verification toggles. Ingest signature toggles do not disable DCP authentication checks.
3. Operator runbook section: how to reject unsigned mappings; optional `POST /assets/{id}/compliance-check` if Gaia-X notarisation is in scope
4. Negative tests: unsigned JWT, wrong issuer key, malformed BPN → rejected when profile on

**Exit:** same demo fails without signatures and passes with them; ops guide lists the env flags. Machine calls in that demo use DCP. OID4VP bootstrap stays out of this profile until it exists.

### Phase 3 — Catalogue + registry coexistence

**Deliverables**

1. One compose profile / deployment story that loads:
   - Gaia-X / Construct-X participant schemas
   - Construct-X identifier shapes
   - Existing catalogue query demos still green
2. Guidance: shared graph means **query filters by type/predicate**; document recommended `FILTER` / type guards so registry lookups do not collide with offering graphs
3. Sensitive-data policy: IBAN (and similar) either omitted, separate credential presentation policy, or out-of-band vault — never in the default public fixture set; **not** a Keycloak role gate

**Exit:** CX-C1 / CX-C2 satisfied with documented query hygiene.

### Phase 4 — Federation

**Deliverables**

1. Config snippet for `federated-catalogue.query.partners`
2. Hurl scenario: BPN known only on partner node resolved via `POST /query/search`
3. Conflict guidance: same BPN mapped to different DIDs across nodes — document prefer-local / multi-hit behaviour (no silent merge)

**Exit:** CX-R11 demonstrated.

### Phase 5 — Optional convenience API (only if needed)

Add **only if** Construct-X clients cannot reasonably run SPARQL:

| Endpoint (sketch) | Behaviour |
|-------------------|-----------|
| `GET /registry/resolve?bpn=…` | Fixed SPARQL → `{ "did": "…", "assetId": "…" }` |
| `GET /registry/resolve?name=…` | Same, fuzzy/contains policy documented |

Implementation notes:

- Thin controller over existing query service; no second store
- Auth matches query today: a verified DCP membership, not a Keycloak role and not a public route
- Read façade stays HTTP GET/POST over the existing query service; DCP authenticates the caller and does not replace SPARQL

Defer if Phase 1 SPARQL kit is enough for Construct-X integrators.

### Phase 6 — DCP for machines + OID4VP initial connector auth + Keycloak admin-only strip

**Landed on `keycloak-reduction`:** machine operations authenticate via DCP with one trusted **MembershipCredential** and a fresh Self-Issued ID Token. Keycloak is **application-admin only** (`ADMIN_ALL`), with the service account limited to Keycloak user administration. Work packages A–E for that split are described above as done except the OID4VP and example items.

**Still the goal for the rest of this phase:** **OID4VP only** for **humans performing initial connector authentication**, optionally minting a catalogue bootstrap token that does not replace DCP.

Do not conflate JWT-VC format support with protocol support. Do not use OID4VP as a second machine protocol. Do not reintroduce a Keycloak fallback on machine routes.

**Mandatory dependencies** — do not reimplement either wire protocol:

| Item | Value |
|------|--------|
| Machine protocol | [european-epc-competence-center/dcp](https://github.com/european-epc-competence-center/dcp) — `de.eecc.dcp:dcp` / `dcp-spring-boot-starter` |
| Initial connector authentication (humans only) | [european-epc-competence-center/oid4vp](https://github.com/european-epc-competence-center/oid4vp) — `de.eecc.oid4vc:oid4vp` |
| Role | DCP = **all connector/runtime write (and scoped-query) auth**; OID4VP = **bootstrap a connector once**; Keycloak no longer grants publish |

Integration sketch:

1. **Done.** Machine presentation pull and Self-Issued ID Token validation go through the existing EECC DCP integration (`DcpVerifierService`, `SelfIssuedIdTokenValidator`, `DcpMachineAuthenticationService`). Replay, audience, and the single-membership policy sit in front of that pull.
2. **Open.** Add `oid4vp` for endpoints used only at initial connector authentication (presentation request, `direct_post`, `response_code` → catalogue bootstrap token). Recommended verifier: [EECC VC Verifier](https://github.com/european-epc-competence-center/vc-verifier).
3. Both paths use Construct-X **MembershipCredential**. The DCP path already requires type, trusted issuer, and holder binding. Status list and membership flags stay off until enabled on purpose.
4. **Done for DCP authZ.** A verified member may run machine operations. Writes are limited to the caller's own issuer/DID. Query and asset reads are available to any verified member. Ingest still uses the existing verification + store pipeline (Phase 2 strict profile is separate and still open).
5. **Open.** On successful **OID4VP** presentation: issue a catalogue bootstrap token to **bind the connector**. That token must not satisfy machine authentication.
6. **Done**, except example migration and OID4VP tests: slim realm, security-chain split, Keycloak bearers rejected on machine APIs, portal restricted to `ADMIN_ALL`, OpenAPI categories and operator docs updated.
7. Track EECC library maturity (`dcp` `0.1.x`, `oid4vp` as published); pin versions before the production wallet cutover.

**Exit:**

- [x] Machine publish requires a **DCP** proof and does not accept a Keycloak token (filter and service tests; live wallet still open)
- [ ] OID4VP initial connector authentication succeeds and may yield a bootstrap token; machine follow-up still uses DCP
- [x] Keycloak-only machine call is rejected
- [x] Application admin authorization is Keycloak `ADMIN_ALL` on `/admin/**`
- [x] Shipped realm has **no** catalogue permission matrix — `ADMIN_ALL` plus the Keycloak-admin service account
- [x] Negative tests for bad/missing DCP proofs, untrusted issuer, and wrong credential shape. OID4VP-as-machine-protocol is rejected because that prefix is deny-all
- [ ] Live wallet / Credential Service run, and hurl publish using that DCP flow

---

## Work breakdown (engineering checklist)

- [ ] **Vocab & policy** — Phase 0 write-up for identifier vocabulary and who may assert `cx:bpn`. Admin boundary and machine write-auth are decided; see [Auth delivery status](#auth-delivery-status-keycloak-reduction)
- [ ] **Storage/query profile** — Construct-X compose/docs pin Fuseki + SPARQL; Neo4j optional for ops switch only (see storage findings)
- [x] **Fixtures (membership)** — `MembershipCredential` VCDM 2.0 default + VCDM 1.1 / real JWT (`examples/construct-x-registry-demo/membership-credential-v{1,2}.*`) — **issuer-shaped remote `@context` URLs** (see CX-BUG-1)
- [ ] **CX-BUG-1** — claim extraction for remote-only JSON-LD contexts (`credentials/v2` + `status/v1`); MembershipCredential must project `isConsumer` / `isProvider` into Fuseki
- [ ] **Fixtures (registry)** — LegalPerson + Reference VC examples; signing notes (fc-tools / external signer)
- [ ] **SHACL** — BPN (+ optional IBAN) shapes; register via `POST /schemas` in demo
- [ ] **Discovery hurl** — BPN/name/country queries; latest-version pattern
- [ ] **Strict profile** — env/compose + negative verification tests
- [x] **Keycloak strip A** — slim realm JSON (`ADMIN_ALL` only; fine-grained roles removed; service account for Keycloak user admin)
- [x] **Keycloak strip B** — admin JWT chain vs DCP machine chain; role matchers removed; query is DCP; schemas stay admin. OID4VP prefix is deny-all
- [x] **Keycloak strip C** — DCP subject DID replaces JWT `participant_id`; participants stored in PostgreSQL; self-publish ownership. OID4VP bootstrap token still open
- [x] **Keycloak strip D (machines + portal)** — DCP required for all 35 machine operations; portal login requires `ADMIN_ALL`. OID4VP bootstrap and hurl migration still open
- [x] **Keycloak strip E (boundary)** — security tests, OpenAPI `x-access-category`, operator docs, slim realm files. Live wallet acceptance and example cutover still open
- [ ] **Coexistence** — regression against Gaia-X / DCS demos (legacy lab realm OK outside Construct-X profile)
- [ ] **Federation** — partner search demo + conflict note
- [ ] **(Optional)** resolve façade API + OpenAPI
- [x] **(Phase 6 machines)** EECC [dcp](https://github.com/european-epc-competence-center/dcp) path authenticates machine operations and then uses the existing verify/store pipeline
- [ ] **(Phase 6 bootstrap)** EECC [oid4vp](https://github.com/european-epc-competence-center/oid4vp) for initial connector authentication only
- [ ] **Docs** — link this plan from the operator guide / company-identifier references. Auth behaviour is already documented beside this plan

Phases 0–4 still do not require a new query or storage core, **except** [CX-BUG-1](#known-bugs--issues-claim-extraction--json-ld-contexts). Machine calls in those phases already need a DCP proof. The remaining Phase 6 core work is OID4VP bootstrap, credential-status policy, and the example cutover.

---

## Known bugs / issues (claim extraction & JSON-LD contexts)

Lab finding while publishing `examples/construct-x-registry-demo/membership-credential-v2.jsonld` into a Fuseki-backed local stack (`POST /assets` → empty RDF-star claim graph).

### CX-BUG-1 — Issuer-shaped MembershipCredential yields `graphClaims=0`

**Symptom:** `POST /assets` returns **201**; Postgres stores the asset (`rdfAssetCount ≥ 1`, `rebuildNeeded=true`); Fuseki stays empty (`claimCount=0`). Server log: `CredentialVerificationResult [… graphClaims=0 …]` then `SparqlGraphStore.addClaims.enter; got claims: []`.

**Canonical input (must work):** Construct-X issuer shape — `@context` is **remote URL list only**, contexts resolved at expand/extract time (no inline term map):

```json
{
  "@context": [
    "https://www.w3.org/ns/credentials/v2",
    "https://www.w3.org/ns/credentials/status/v1"
  ]
}
```

plus `credentialSubject.isConsumer` / `isProvider` and `credentialStatus` (`BitstringStatusListEntry`) as in the int issuer JWT / `membership-credential-v2.jsonld`.

**Root causes (reproduced with Titanium `JsonLd.expand`):**

| # | Failure | Detail |
|---|---------|--------|
| A | **`PROTECTED_TERM_REDEFINITION`** | Composing remote `credentials/v2` + `credentials/status/v1` makes Titanium fail expand. `CredentialSubjectClaimExtractor` uses bare `JsonLd.expand` (no catalogue `DocumentLoader`). Exception is swallowed in `ClaimExtractionService` → empty list. |
| B | **Unscoped subject terms drop out** | With `credentials/v2` alone, expand succeeds but `credentialSubject` collapses to `{ "@id": "<wallet DID>" }` — `isConsumer` / `isProvider` **do not** become RDF triples. `toRdf` → `tripleCount=0`. |
| C | **Extractor ↔ verification loader split** | Verification / Danube paths can use Spring `DocumentLoader` (cache, `additional-context`, `enable-http`). Claim extractors instantiate Titanium/Danube **without** that loader, so catalogue context policy does not apply to graph projection. |
| D | **Fallback silent** | Both credential extractors failing or returning empty is treated as success with zero claims — asset is still stored as RDF. |

**Requirement (acceptance):** The catalogue **must** ingest and project claims from MembershipCredentials (and later registry VCs) when `@context` is **only resolvable URL(s)** — the form issuers actually mint. Inline Construct-X term maps are a **lab workaround only**, not the production contract.

**Acceptance checks:**

1. `POST /assets` of remote-context-only `membership-credential-v2.jsonld` → `graphClaims ≥ 1` (at least `isConsumer` / `isProvider`, preferably `rdf:type` for membership if typed on subject).
2. Fuseki RDF-star query by wallet DID / `cred:credentialSubject` returns those triples without a manual rebuild.
3. Remote context fetch uses the same trustable loader policy as verification (HTTP allowed in lab; cache / override in prod); expand must tolerate `credentials/v2` + `credentials/status/v1` (or an equivalent documented loader strategy).
4. Empty claim extraction after a successful credential ingest is a **hard failure** (or loud warning + metrics), not a silent 201 with empty graph.

**Likely fix direction (core, not examples-only):**

- Wire `DocumentLoader` (or Titanium loader options) into `CredentialSubjectClaimExtractor` / Danube path.
- Resolve protected-term clash for status/v1 (upstream Titanium options, ordered context load, or pre-cached merged context).
- Ensure boolean / `@vocab` issuer-dependent terms from VC 2.0 survive subject `toRdf`.
- Optionally fail ingest when credential payload is VC-shaped but claim list is empty.

**Work item:** checklist below — do **not** paper over with forever-inline demo contexts as the Construct-X source of truth.

---

## Verification & trust matrix

| Mode | semantics | vc-signature | schema | Suitable for |
|------|-----------|--------------|--------|--------------|
| Lab / demo | on | off | off | Local Construct-X experiments |
| Staging | on | on | on | Pre-prod registry |
| Authoritative Construct-X registry | on | on | on (+ TF if required) | Production DID↔BPN authority |

DCP machine authentication always checks the Self-Issued ID Token and the membership VC/VP signatures. The ingest toggles in this table do not turn those authentication checks off. A lab profile can still store a loosely verified *asset* after a valid membership proof. Authoritative registry ingest should keep `vc-signature` on. Do not reintroduce Keycloak roles as a substitute for that proof.

---

## Test plan (summary)

1. **Happy path:** publish Reference VC → SPARQL BPN→DID returns company DID.
2. **Split model:** Participant VC + Reference VC both queryable on same subject.
3. **Strict reject:** unsigned / bad signature → `POST /assets` fails.
4. **Shape reject:** invalid BPN → fail when schema enabled.
5. **Versioning:** v2 supersedes v1; discovery prefers approved latest.
6. **Catalogue coexistence:** upload offering + registry VC; both discoverable with type filters.
7. **Federation:** partner-only BPN resolved via `POST /query/search`.
8. **Authz (machines) — covered by security tests, still needs a live wallet run:** trusted MembershipCredential over DCP → machine call allowed without a Keycloak role; missing/wrong proof, Keycloak bearer, and bootstrap-style JWT → rejected. A verified member may read and query; writes must be self-published.
9. **Authz (initial connector authentication) — open:** OID4VP + MembershipCredential → bootstrap token issued without Keycloak; that token does not authorize later machine writes.
10. **Authz (admins) — covered for the token boundary:** `ADMIN_ALL` → `/admin/**` allowed; any other Keycloak token is denied there and on machine routes. Portal login uses the same role.

Executable form: extend `examples/` with a Construct-X hurl suite analogous to `dcs-template-demo` and `queries/verify-against-fuseki.hurl`. Publish goes through the **DCP** machine flow that is already enforced. Add a separate **OID4VP** scenario when bootstrap exists. Leave password-grant steps out.

---

## Risks

| Risk | Mitigation |
|------|------------|
| CX-BUG-1: issuer MembershipCredential stores with empty claim graph | Fix claim extractors + DocumentLoader; treat empty claims as ingest error; keep fixture remote-URL-shaped |
| Expectation that JWT-VC ingest is DCP | Machine authentication is a separate pull of a MembershipCredential. Storing a VC via `POST /assets` still needs that proof first |
| EECC dcp still `0.1.x` | Pin the version already integrated. Production cutover waits on a real wallet and Credential Service, not on another protocol implementation |
| Treating the branch as fully cut over | DCP and admin Keycloak are in the server. OID4VP, credential status, hurl, and live interoperability are not. Existing Keycloak databases do not pick up the slim realm on restart |
| Using OID4VP for connector runtime | Remains a non-goal. The bootstrap token, once issued, binds the connector; machine I/O stays on a fresh DCP proof |
| Rebuilding a role matrix in Keycloak | Remains a non-goal. `ADMIN_ALL` plus the Keycloak-admin service account. Catalogue permissions stay in issuer and ownership policy |
| Legacy demos that script a password grant | Those scenarios fail against the slim realm until they send a DCP proof. Do not restore direct-access grants to keep them running |
| Lab defaults → false sense of security | Strict profile mandatory in Construct-X ops guide |
| Public graph leaks IBAN | Separate asset / omit / vault; CX-R9 in demo defaults |
| Cross-node conflicting BPN mappings | Document multi-hit; no automatic overwrite |
| Vocab fork vs Catena-X IRIs | Phase 0 decision; prefer reuse for tooling |

---

## Suggested sequence for first PR series

1. **Done on this branch:** admin/DCP split, slim realm, participant store, audit, and the auth docs linked from [Auth delivery status](#auth-delivery-status-keycloak-reduction)
2. **CX-BUG-1** — claim extraction / DocumentLoader for remote-only MembershipCredential contexts (required before graph demos are meaningful)
3. `examples/construct-x-registry-demo/` — correct the README (DCP is already required), then LegalPerson / BPN fixtures and hurl using a DCP proof
4. SHACL + schema registration in the demo (schema APIs are admin Keycloak calls)
5. Strict-profile overlay/docs + negative ingest tests
6. Federation scenario (`POST /query/search` already requires DCP)
7. OID4VP for **initial connector authentication** only, without a path onto machine APIs
8. Live wallet / Credential Service acceptance, replay migration, and re-registration of participants
9. Optional resolve API only after integrator feedback  

---

## Summary

Construct-X uses the Federated Catalogue as **catalogue and registry at once**: identifier VCs on the ingest path, a **strict verification profile**, and **SPARQL discovery** (and federation) over the claim graph. Storage stays dual: **Postgres** for signed documents and lifecycle, **Fuseki** for the Self-Description Graph (Neo4j optional, not the integrator contract).

On `keycloak-reduction`, **machine operations authenticate with one trusted MembershipCredential over DCP** ([EECC dcp](https://github.com/european-epc-competence-center/dcp)), with self-publish ownership, catalogue-owned participants, and replay-protected Self-Issued ID Tokens. **Keycloak is admin-only** (`ADMIN_ALL`), plus a service account for Keycloak user administration. **OID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) initial-connector bootstrap, credential-status checks, registry fixtures beyond the membership sample, and live wallet acceptance are still ahead. Former `ASSET_*` / `Ro-*` checks are not coming back; catalogue authorization stays on the DCP issuer and the caller's DID.

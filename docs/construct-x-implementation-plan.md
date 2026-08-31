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
| Auth today is Keycloak roles (`ASSET_CREATE`, `QUERY_EXECUTE`, `Ro-*`, …) | **Strip Keycloak to a minimum:** only **application admins** log in with Keycloak. **Machine operations always use DCP** (MembershipCredential). **OID4VP is only** for **humans doing initial connector authentication** (may mint a longer-lived catalogue access token). Drop fine-grained / composite role catalogues from the realm |

**Dual-role principle:** keep one API surface. Catalogue assets and registry reference credentials share ingest, verification, versioning, and query. Differ only by vocabulary, SHACL, and operator policy.

**Auth principle:**

| Actor | AuthN | AuthZ |
|-------|-------|-------|
| **Machine operations** (connectors, automated catalogue/registry I/O) | **Always DCP** — present Construct-X **MembershipCredential** ([EECC dcp](https://github.com/european-epc-competence-center/dcp)) | Credential type / issuer / trust policy (`MembershipCredential`) — **not** Keycloak roles, **not** OID4VP |
| **Humans — initial connector authentication only** | **OpenID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) presenting the same **MembershipCredential**; may mint a **catalogue access token** (not Keycloak) to bind/bootstrap the connector | Same membership / issuer policy |
| **Application admins** only | Keycloak OIDC login (Bearer JWT) | Single admin capability (e.g. `ADMIN_ALL`) for ops IAM, `/admin/**`, break-glass |

No Keycloak accounts for users or connectors. No `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` permission matrix for Construct-X. OID4VP is **not** the protocol for ongoing machine traffic.

---

## Goals

1. **Registry:** resolve `BPN → DID`, `legal name → DID`, `address/country → DID`, and (optionally restricted) `IBAN → DID` from signed claims.
2. **Catalogue:** continue to host Construct-X service offerings / other assets in the same node.
3. **Authoritative mappings:** operators can run a profile where VC signatures and shapes are enforced before store.
4. **DCP for machines; OID4VP only for initial connector users; Keycloak for admins:** **machine operations always authenticate via DCP** with a Construct-X **MembershipCredential** ([EECC dcp](https://github.com/european-epc-competence-center/dcp)). **OID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) is **only** for **humans performing initial connector authentication**; that presentation **can yield a longer-lived catalogue access token** (OAuth 2.0 `response_code` → token) to bind the connector. Keycloak remains only for **application admins**. **No** complex roles / permission specifications in Keycloak.
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
| CX-R6 | **Machines** authenticate with **MembershipCredential over DCP**; **OID4VP only** for **initial connector authentication**; Keycloak tokens do not authorize catalogue writes | Connector write without DCP membership presentation rejected; human OID4VP bootstrap succeeds without Keycloak; Keycloak-only write rejected |
| CX-R6a | Keycloak **application-admin only**; no complex role/permission matrix | Realm has a single admin role; `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` composites removed from Construct-X realm; only admins use OIDC login |
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

Today authorization is almost entirely URL ↔ role matching in `SecurityConfig`, JWT `participant_id` scoping in `SessionUtils`, and Keycloak Admin API as the user/participant store. Construct-X must replace the **user/connector** half of that with **MembershipCredential**: **DCP for all machine operations**, **OID4VP only for initial connector authentication** (optional catalogue bootstrap token), and leave Keycloak as an **admin-only** IdP.

### Target end state

| Area | Before (today) | After (Construct-X) |
|------|----------------|---------------------|
| Who has Keycloak accounts | Admins, participant admins, asset operators, readers, test users | **Application admins only** |
| How machines operate | Bearer JWT + `ASSET_CREATE` / `Ro-*` | **Always DCP** + **MembershipCredential** |
| How humans bind a connector | Keycloak login / password-grant | **OID4VP** + MembershipCredential (optional catalogue access token); **not** used for ongoing machine I/O |
| Realm roles | Fine-grained CRUD + composites + Gaia-X remaps | **One admin role** (`ADMIN_ALL` or equivalent); no permission matrix |
| `/users`, `/roles`, participant user admin via Keycloak | First-class | Deprecate / admin-only residual or remove from Construct-X profile |
| Demo portal OIDC login for publish | Used by writers | Admin UI only; connector bootstrap is OID4VP; publish is DCP |

### Work package A — Realm & config (remove permission complexity)

1. **Slim realm JSON** (`keycloak/realms/{dev,staging,prod}/fc-realm.json`, Helm `fc-realm.json`):
   - Remove client roles: `ASSET_*`, `SCHEMA_*`, `QUERY_EXECUTE`, `Ro-MU-CA`, `Ro-MU-A`, `Ro-AS-A`, `Ro-PA-A`, convenience composites (`asset-creator`, `asset-editor`, …).
   - Keep a single application-admin role (prefer `ADMIN_ALL`; decide whether to rename/alias `Ro-MU-CA` once for migration then delete).
   - Remove non-admin seed users (`fc-restricted-test`, writer-style users); keep one admin user for ops.
   - Drop unused protocol mappers / authz settings that only served fine-grained roles (keep what admins still need).
2. **`CommonConstants`:** stop treating fine-grained role names as the Construct-X auth model; retain admin constant(s) only for Keycloak-gated paths.
3. **`CustomJwtAuthenticationConverter`:** remove Gaia-X claim → `Ro-*` remapping when Construct-X profile is active (or delete if unused).
4. **Docs / OpenAPI:** strip “Required permission: `ASSET_CREATE` …” for data write ops; document **DCP + MembershipCredential** for machine operations and **OID4VP + MembershipCredential** only for initial connector authentication. Operator guide: Keycloak = admin login only.

### Work package B — Spring Security split (admins vs OID4VP bootstrap vs DCP machines)

Touch: `fc-service-server/.../config/SecurityConfig.java`.

**End state:** three chains. Keycloak JWTs never authorize data APIs. **Machine operations always authenticate with DCP** (MembershipCredential). **OID4VP is only** for **humans performing initial connector authentication** and **may mint a longer-lived catalogue access token** to bind the connector — it is **not** the protocol for subsequent machine traffic.

1. **Keycloak JWT required only** for application-admin surfaces, e.g.:
   - `/admin/**`
   - Residual ops that stay admin-owned (trust-framework admin, graph rebuild, schema **management** if operators keep that under admin — decide in Phase 0; default: schema CRUD = admin Keycloak **or** treat schema publish as machine data gated by DCP membership)
2. **OID4VP — initial connector authentication only** — integrate [EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp) (`de.eecc.oid4vc:oid4vp`; Java 25, already the catalogue JDK). Do not reimplement presentation requests, `direct_post`, or `response_code`. Do **not** put OID4VP on connector ingest/query paths.
   - **When:** a human authenticates a connector for the first time (wallet / holder presents **MembershipCredential**).
   - **Presentation request:** DCQL asks for Construct-X `MembershipCredential` (trusted issuer; `credentialStatus` / BitstringStatusList as required). `verifierUrl` points at a compatible verifier (recommended: [EECC VC Verifier](https://github.com/european-epc-competence-center/vc-verifier)).
   - **Wallet / holder:** POSTs `vp_token` + `state` to `response_uri`; `oid4Vp.processDirectPost(...)` verifies the presentation.
   - **Bootstrap token:** handler returns `DirectPostResult.issueResponseCode()`; client exchanges `state` + `response_code` at a catalogue token endpoint; catalogue issues **access (+ optional refresh) tokens that are not Keycloak JWTs**, used to **bind/bootstrap the connector**. Token claims bind holder DID, membership issuer, `isConsumer` / `isProvider`, presentation id. Call `invalidateResponseCode` after redemption.
3. **Machine operations — always DCP** — integrate [EECC dcp](https://github.com/european-epc-competence-center/dcp) (`dcp-spring-boot-starter`). Connectors present **MembershipCredential** on every catalogue/registry operation. Same membership / issuer policy as OID4VP bootstrap; different wire protocol.
4. **Data APIs must not** `hasRole(ASSET_*)` / `hasRole(QUERY_*)` / `hasAnyRole(Ro-*)` for Construct-X:
   - Writes: `POST/PUT/DELETE /assets*`, provenance/compliance mutations, `POST /participants` — allowed iff the principal is a **DCP** membership presentation (machine) — **not** Keycloak, **not** OID4VP-as-protocol
   - Reads/query: either **public under operator policy**, or the same **DCP** membership presentation if Construct-X requires credential-scoped query — **not** `QUERY_EXECUTE` in Keycloak
5. Filter chain order:
   - **OID4VP endpoints** (unauthenticated protocol): presentation request URI, `direct_post` response, poll-by-state, token redemption — paths analogous to the library sketch (`/api/auth/oid4vp/**`). Scope: **initial connector authentication only**.
   - **DCP / machine data APIs:** DCP auth filter (or façade) establishes a principal from MembershipCredential presentation; **not** the Keycloak issuer
   - **Admin chain:** OAuth2 resource server as today (Keycloak issuer + `ADMIN_ALL` only)
6. Reject Construct-X data APIs that present only a Keycloak access token (authoritative profile), even if the JWT is valid for `/admin/**`. Reject using OID4VP as a substitute for DCP on machine operations.

### Work package C — Replace participant/user scoping for posters

1. **`SessionUtils.checkParticipantAccess`:** for machine requests, authorize from **MembershipCredential subject (DID)** and issuer policy on the **DCP** presentation; for initial connector authentication, from the OID4VP-derived bootstrap token. Never JWT `participant_id` / `Ro-MU-CA` bypass.
2. **`AssetService` / `AssetUploadService` / `ParticipantsService`:** dual-path then cut Keycloak path for machines; auditor (`SecurityAuditorAware`) records DID / DCP presentation id (machine) or OID4VP bootstrap token `jti` (initial connector auth), not Keycloak `sub`.
3. **`UsersService` role-assignment rules** (`doCheckRoleAssignmentRule`): obsolete for Construct-X users — either admin-only stub or remove from Construct-X profile.
4. Keycloak Admin DAOs (`UserDaoImpl`, `ParticipantDaoImpl`, …): keep only if application admins still manage admin accounts/groups; **do not** model catalogue participants as Keycloak groups for ordinary users.

### Work package D — DCP for machines + OID4VP for initial connector auth (pairs with Phase 6)

1. Integrate [EECC dcp](https://github.com/european-epc-competence-center/dcp) (`dcp-spring-boot-starter`) as the **only** protocol for **machine operations** (connector ingest/query).
2. Integrate [EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp) (`de.eecc.oid4vc:oid4vp`) **only** for **humans performing initial connector authentication** (work package B). Do not use OID4VP for connector runtime traffic.
3. Both paths use the same presentation policy: **require `MembershipCredential`** (trusted issuer; optional `credentialStatus` / `isConsumer` / `isProvider`; optional later: Participant / registry-operator VCs from Phase 0).
4. DCP success → authorize existing verify + store pipeline (strict profile) with that principal. OID4VP success → mint catalogue bootstrap token to bind the connector; subsequent machine calls still go through DCP.
5. Examples/hurl: replace password-grant / Keycloak Bearer steps with a DCP membership presentation for publish; add a separate OID4VP initial-connector-auth fixture (optional catalogue token).
6. **`fc-demo-portal`:** OAuth2 login only for admin UI; connector bootstrap is OID4VP; publish/query demos use DCP.

### Work package E — Tests & migration

1. Update `*ControllerTest` / `@WithMockJwtAuth` suites: admin paths keep **Keycloak** JWT; machine write paths assert **DCP + MembershipCredential** (or test doubles); OID4VP tests cover **initial connector authentication only** — not `ASSET_CREATE`.
2. Migration flag only during cutover (`auth.machines=dcp`, Keycloak writers disabled); **no** long-term “both” for Construct-X production. OID4VP stays a **bootstrap-only** surface, not a second machine protocol.
3. Lab compose may temporarily keep a fat realm for legacy Gaia-X demos; Construct-X authoritative compose ships the **slim admin-only** realm.

### Exit criteria (Keycloak strip)

- [ ] Construct-X realm JSON has **no** fine-grained / `Ro-*` permission matrix — admin role only
- [ ] Non-admin users have **no** Keycloak accounts in Construct-X deployments
- [ ] `POST /assets` (and agreed machine write APIs) succeed with **DCP + MembershipCredential** and **fail** with Keycloak-only Bearer and **fail** if the caller tries OID4VP as the machine protocol
- [ ] Initial connector authentication succeeds with **OID4VP + MembershipCredential** (optional catalogue bootstrap token) **without** a Keycloak account
- [ ] Application admin can still log in via Keycloak and use `/admin/**`
- [ ] Operator docs state: machines → DCP + MembershipCredential; humans binding a connector → OID4VP only; admins → Keycloak; no role cataloguing in Keycloak

---

## Implementation plan (short)

**North star:** **machine operations always use DCP + MembershipCredential**; **OID4VP is only** for **humans doing initial connector authentication** (optional **catalogue bootstrap token**); Keycloak stays **admin-only**. Registry + catalogue share one catalogue node (SPARQL discovery, strict verification profile).

| Priority | What | Outcome |
|----------|------|---------|
| **1. DCP machines + OID4VP connector bootstrap** | EECC `dcp` for **all machine ops**; EECC `oid4vp` **only** for initial connector authentication with **`MembershipCredential`** (VCDM 2.0 default), which **may mint a catalogue bootstrap token**; Keycloak Bearer no longer authorizes data APIs | CX-R6 / R6a; Phase 6 is the cutover, but policy + fixtures start in Phase 0–1 |
| **2. Modelling kit** | Membership + LegalPerson / BPN reference fixtures, SHACL, SPARQL (BPN→DID, name, country, latest approved) | Phase 1 demo runnable with semantics-only |
| **3. Strict registry profile** | Signatures + semantics + shapes on; negative tests for unsigned / bad issuer / bad BPN | Phase 2 authoritative ops |
| **4. Coexistence + federation** | Shared graph query hygiene; partner `query.search` for remote BPN | Phases 3–4; CX-C1/C2, CX-R11 |
| **5. Optional resolve API** | Thin `GET /registry/resolve` only if SPARQL is a blocker | Phase 5 — defer if kit is enough |
| **6. Keycloak strip** | Slim admin-only realm; `/admin/**` via Keycloak; machines = DCP; humans binding connectors = OID4VP only | Phase 6 work packages A–E |

**Sequence in one line:** agree membership write-auth policy → ship membership credential fixtures → turn on strict verify → keep catalogue/registry queries clean → federate → (optional façade) → **cut over machines to DCP, humans to OID4VP bootstrap only, and strip Keycloak roles**.

Detail below.

## Phased delivery

### Phase 0 — Agree vocabulary and governance (docs only)

**Deliverables**

- Construct-X namespace table (reuse vs invent):
  - Prefer existing IRIs where possible (`schema:`, `gx:`, Catena-X `cx:bpn`, …)
  - Document any Construct-X-specific predicates if Catena-X IRIs are politically unwanted
- Issuer policy: who may assert `cx:bpn` (self-asserted vs registry-operator-only)
- **Write-auth credential policy:** Construct-X **`MembershipCredential` (VCDM 2.0)** — **machines present it over DCP**; **humans present it over OID4VP only when initially authenticating a connector** (may issue a catalogue bootstrap token). VCDM 1.1 JWT shape for issuer compatibility; Participant / registry-operator may be added later as additional presentation options.
- **Admin boundary:** which ops stay Keycloak-admin-only (default: `/admin/**`, break-glass); confirm schemas/query are DCP-membership, public, or admin
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
5. README under `examples/construct-x-registry-demo/` (same style as `examples/dcs-template-demo/`), documenting membership-as-authZ (v2 default): **DCP for machines**, **OID4VP only for initial connector authentication**

**Exit:** membership v2 fixture reviewed as the DCP write-auth credential (and OID4VP bootstrap credential); `hurl --test` against local stack publishes registry fixtures and resolves BPN→DID with semantics-only verification.

### Phase 2 — Authoritative registry profile (ops + config)

**Deliverables**

1. Documented **Construct-X strict profile** (compose overlay or env preset):
   - `FEDERATED_CATALOGUE_VERIFICATION_VC_SIGNATURE=true`
   - `FEDERATED_CATALOGUE_VERIFICATION_VP_SIGNATURE=true` (if VPs used)
   - `FEDERATED_CATALOGUE_VERIFICATION_SEMANTICS=true`
   - schema validation enabled for Construct-X shapes
   - DID resolution + trust anchors available (as for `docker-compose.strict.yml`)
2. **Keycloak strip kickoff** for this profile (full strip in Phase 6 / work packages A–E):
   - Document admin-only Keycloak vs machine DCP vs OID4VP-only initial connector auth
   - Do **not** add new Construct-X Keycloak roles; freeze / start deleting fine-grained roles from Construct-X realm drafts
3. Operator runbook section: how to reject unsigned mappings; optional `POST /assets/{id}/compliance-check` if Gaia-X notarisation is in scope
4. Negative tests: unsigned JWT, wrong issuer key, malformed BPN → rejected when profile on

**Exit:** same demo fails without signatures and passes with them; ops guide lists the env flags and the admin-only Keycloak / machine-DCP / OID4VP-bootstrap intent.

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
- Auth: **no** `QUERY_EXECUTE` in Keycloak — public under operator policy, or DCP-scoped query if required
- Read façade stays HTTP GET/POST; DCP is for credential presentation, not for replacing SPARQL

Defer if Phase 1 SPARQL kit is enough for Construct-X integrators.

### Phase 6 — DCP for machines + OID4VP initial connector auth + Keycloak admin-only strip

**Goal:** **machine operations always authenticate via DCP** with **MembershipCredential**. **OID4VP is only** for **humans performing initial connector authentication** (optional catalogue bootstrap token). Keycloak remains **only** for **application admins**. Remove complex roles and permission specifications from Keycloak (work packages A–E above).

Do not conflate JWT-VC format support with protocol support. Do not use OID4VP as a second machine protocol.

**Mandatory dependencies** — do not reimplement either wire protocol:

| Item | Value |
|------|--------|
| Machine protocol | [european-epc-competence-center/dcp](https://github.com/european-epc-competence-center/dcp) — `de.eecc.dcp:dcp` / `dcp-spring-boot-starter` |
| Initial connector authentication (humans only) | [european-epc-competence-center/oid4vp](https://github.com/european-epc-competence-center/oid4vp) — `de.eecc.oid4vc:oid4vp` |
| Role | DCP = **all connector/runtime write (and scoped-query) auth**; OID4VP = **bootstrap a connector once**; Keycloak no longer grants publish |

Integration sketch:

1. Add `dcp-spring-boot-starter` (or `dcp`) to `fc-service-server` / a dedicated DCP module for **machine** presentation queries (`DcpPresentation`, `PresentationQueryMessage`, SI-token validation as the package matures).
2. Add `oid4vp` for **OID4VP endpoints used only at initial connector authentication** (presentation request, `direct_post`, `response_code` → catalogue bootstrap token). Recommended verifier: [EECC VC Verifier](https://github.com/european-epc-competence-center/vc-verifier).
3. Both paths: Construct-X **MembershipCredential** (Phase 0 credential policy).
4. On successful **DCP** presentation:
   - **AuthZ:** presented claims → allow machine create/update/query as policy (replaces all former `ASSET_*` / `Ro-*` write roles).
   - **Ingest:** hand extracted VCs/VPs into existing verification + store (Phase 2 strict profile).
5. On successful **OID4VP** presentation: issue catalogue bootstrap token to **bind the connector**; do **not** treat that as a standing substitute for DCP on data APIs.
6. Execute **Strip Keycloak** work packages A–E:
   - Slim realm to **admin-only** role; delete fine-grained / composite roles
   - `SecurityConfig` split: Keycloak JWT → `/admin/**` only; DCP → machine data APIs; OID4VP → bootstrap endpoints only
   - Data writes reject Keycloak-only Bearer in Construct-X authoritative profile
   - Portal / examples / tests updated
7. Track EECC library maturity (`dcp` `0.1.x`, `oid4vp` as published); pin versions before production cutover.

**Exit:**

- A connector publishes after **DCP** presentation **without** a Keycloak account or token
- OID4VP initial connector authentication succeeds and may yield a bootstrap token; machine follow-up still uses DCP
- Keycloak-only write → rejected in Construct-X authoritative profile
- Application admin logs in with Keycloak; uses `/admin/**`
- Realm has **no** complex permission/role catalogue — admin role only
- Negative tests: bad/missing presentation, untrusted issuer, wrong credential type, OID4VP used as machine protocol → rejected

---

## Work breakdown (engineering checklist)

- [ ] **Vocab & policy** — Phase 0 write-up; admin boundary; write-auth VC types; fix “Contruct-X” → Construct-X in docs
- [ ] **Storage/query profile** — Construct-X compose/docs pin Fuseki + SPARQL; Neo4j optional for ops switch only (see storage findings)
- [x] **Fixtures (membership)** — `MembershipCredential` VCDM 2.0 default + VCDM 1.1 / real JWT (`examples/construct-x-registry-demo/membership-credential-v{1,2}.*`) — **issuer-shaped remote `@context` URLs** (see CX-BUG-1)
- [ ] **CX-BUG-1** — claim extraction for remote-only JSON-LD contexts (`credentials/v2` + `status/v1`); MembershipCredential must project `isConsumer` / `isProvider` into Fuseki
- [ ] **Fixtures (registry)** — LegalPerson + Reference VC examples; signing notes (fc-tools / external signer)
- [ ] **SHACL** — BPN (+ optional IBAN) shapes; register via `POST /schemas` in demo
- [ ] **Discovery hurl** — BPN/name/country queries; latest-version pattern
- [ ] **Strict profile** — env/compose + negative verification tests
- [ ] **Keycloak strip A** — slim realm JSON (admin role only; delete `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` / composites)
- [ ] **Keycloak strip B** — `SecurityConfig` admin-only JWT; user data APIs off role matchers
- [ ] **Keycloak strip C** — DID/DCP presentation scoping replaces JWT `participant_id` for machines; OID4VP bootstrap token only for initial connector auth
- [ ] **Keycloak strip D** — DCP façade for **all machine** posts; OID4VP **only** for initial connector authentication; portal admin-only login
- [ ] **Keycloak strip E** — tests, OpenAPI, operator docs, Construct-X compose with slim realm
- [ ] **Coexistence** — regression against Gaia-X / DCS demos (legacy lab realm OK outside Construct-X profile)
- [ ] **Federation** — partner search demo + conflict note
- [ ] **(Optional)** resolve façade API + OpenAPI
- [ ] **(Phase 6)** EECC [dcp](https://github.com/european-epc-competence-center/dcp) → machine authZ + verify/store; EECC [oid4vp](https://github.com/european-epc-competence-center/oid4vp) → initial connector authentication only
- [ ] **Docs** — link this plan from operator guide / company-identifier references

No mandatory core changes for Phases 0–4 if existing ingest, verification toggles, schemas, versions, and query federation behave as documented — **except** [CX-BUG-1](#known-bugs--issues-claim-extraction--json-ld-contexts) (claim extraction for issuer-shaped MembershipCredential). **Phase 6 is mandatory core work** for Construct-X: DCP machine auth + OID4VP connector bootstrap + Keycloak admin-only strip (work packages A–E).

---

## Known bugs / issues (claim extraction & JSON-LD contexts)

Lab finding while publishing `examples/construct-x-registry-demo/membership-credential-v2.jsonld` into a Fuseki-backed local stack (`POST /assets` → empty RDF-star claim graph).

### CX-BUG-1 — Issuer-shaped MembershipCredential yields `graphClaims=0`

**Symptom:** `POST /assets` returns **201**; Postgres stores the asset (`rdfAssetCount ≥ 1`, `rebuildNeeded=true`); Fuseki stays empty (`claimCount=0`). Server log: `CredentialVerificationResult [… graphClaims=0 …]` then `SparqlGraphStore.addClaims.enter; got claims: []`.

**Canonical input (must work):** Construct-X issuer shape — `@context` is **remote URL list only**, contexts resolved at expand/extract time (no inline term map):

```json
"@context": [
  "https://www.w3.org/ns/credentials/v2",
  "https://www.w3.org/ns/credentials/status/v1"
]
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

Reminder: without `vc-signature`, a too-loose DCP (or OID4VP) policy can still accept bad payloads. Prefer **MembershipCredential presentation + signature + issuer policy**. Do **not** reintroduce Keycloak roles as a substitute for cryptographic trust.

---

## Test plan (summary)

1. **Happy path:** publish Reference VC → SPARQL BPN→DID returns company DID.
2. **Split model:** Participant VC + Reference VC both queryable on same subject.
3. **Strict reject:** unsigned / bad signature → `POST /assets` fails.
4. **Shape reject:** invalid BPN → fail when schema enabled.
5. **Versioning:** v2 supersedes v1; discovery prefers approved latest.
6. **Catalogue coexistence:** upload offering + registry VC; both discoverable with type filters.
7. **Federation:** partner-only BPN resolved via `POST /query/search`.
8. **Authz (machines):** DCP presentation with trusted MembershipCredential → write allowed **without** Keycloak; missing/wrong presentation → rejected; Keycloak-only Bearer → rejected; OID4VP used as machine protocol → rejected.
9. **Authz (initial connector authentication):** OID4VP + MembershipCredential → bootstrap token issued **without** Keycloak; does not by itself authorize ongoing machine writes (those remain DCP).
10. **Authz (admins):** application admin Keycloak login → `/admin/**` OK; non-admin Keycloak user (if any remain in lab) cannot substitute for DCP on machine write paths.

Executable form: extend `examples/` with a Construct-X hurl suite analogous to `dcs-template-demo` and `queries/verify-against-fuseki.hurl`; add a Phase 6 **DCP** presentation scenario for publish and a separate **OID4VP** initial-connector-auth scenario (no password-grant for writers).

---

## Risks

| Risk | Mitigation |
|------|------------|
| CX-BUG-1: issuer MembershipCredential stores with empty claim graph | Fix claim extractors + DocumentLoader; treat empty claims as ingest error; keep fixture remote-URL-shaped |
| Expectation of DCP “support” | Docs state JWT format ≠ DCP protocol; Phase 6 uses EECC [dcp](https://github.com/european-epc-competence-center/dcp) for **machines**, not a custom stack |
| EECC dcp still `0.1.x` | Pin version; gate production on SI-token / VP validation façades; short migration window only |
| Dual/triple auth confusion (Keycloak + DCP + OID4VP) | Construct-X profile: **machines = DCP only**; **OID4VP = initial connector authentication only**; **admins = Keycloak only**; reject Keycloak on data writes; reject OID4VP as a machine protocol |
| Using OID4VP for connector runtime | Explicit non-goal; OID4VP bootstrap token binds the connector; subsequent I/O is DCP |
| Rebuilding a role matrix in Keycloak | Explicit non-goal; single `ADMIN_ALL`; permissions live in VC/issuer policy |
| Legacy Gaia-X demos need fat realm | Keep fat realm on **lab/legacy** compose only; Construct-X authoritative compose is slim |
| Lab defaults → false sense of security | Strict profile mandatory in Construct-X ops guide |
| Public graph leaks IBAN | Separate asset / omit / vault; CX-R9 in demo defaults |
| Cross-node conflicting BPN mappings | Document multi-hit; no automatic overwrite |
| Vocab fork vs Catena-X IRIs | Phase 0 decision; prefer reuse for tooling |

---

## Suggested sequence for first PR series

1. Docs: this plan + vocab/issuer/admin-boundary + Keycloak strip work packages + link from company-identifier references  
2. **CX-BUG-1** — claim extraction / DocumentLoader for remote-only MembershipCredential contexts (required before graph demos are meaningful)  
3. `examples/construct-x-registry-demo/` — start with **MembershipCredential** (DCP write-auth + OID4VP bootstrap credential), then LegalPerson / BPN fixtures + hurl (semantics-only; temporary Keycloak OK until Phase 6)  
4. SHACL + schema registration in demo  
5. Strict-profile overlay/docs + negative tests  
6. Federation scenario  
7. Phase 6: EECC DCP façade for **machine** posts without Keycloak; EECC OID4VP for **initial connector authentication** only  
8. Keycloak strip: slim realm (admin only), `SecurityConfig` three-way split, portal/examples/tests  
9. Optional resolve API only after integrator feedback  

---

## Summary

Construct-X uses the Federated Catalogue as **catalogue and registry at once**: identifier VCs on the ingest path, a **strict verification profile**, and **SPARQL discovery** (and federation) over the claim graph. Storage stays dual: **Postgres** for signed documents and lifecycle, **Fuseki** for the Self-Description Graph (Neo4j optional, not the integrator contract). **Machine operations always authenticate with MembershipCredential over DCP** ([EECC dcp](https://github.com/european-epc-competence-center/dcp) / `de.eecc.dcp`). **OID4VP** ([EECC oid4vp](https://github.com/european-epc-competence-center/oid4vp)) is **only** for **humans performing initial connector authentication** and may mint a catalogue bootstrap token — it is **not** the machine protocol. **Keycloak is stripped to a minimum:** only **application admins** log in; the realm keeps a **single admin role** with **no** complex permission matrix. Former `ASSET_*` / `Ro-*` semantics move to credential types and issuer policy—not Keycloak.

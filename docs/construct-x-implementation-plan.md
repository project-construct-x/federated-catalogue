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
| Auth today is Keycloak roles (`ASSET_CREATE`, `QUERY_EXECUTE`, `Ro-*`, …) | **Strip Keycloak to a minimum:** only **application admins** log in with Keycloak; **all data posters** authenticate and authorize via **VCs + DCP**. Drop fine-grained / composite role catalogues from the realm |

**Dual-role principle:** keep one API surface. Catalogue assets and registry reference credentials share ingest, verification, versioning, and query. Differ only by vocabulary, SHACL, and operator policy.

**Auth principle:**

| Actor | AuthN | AuthZ |
|-------|-------|-------|
| **Catalogue users** (participants, registry writers, anyone posting data) | DCP presentation of VCs | Credential type / issuer / trust policy — **not** Keycloak roles |
| **Application admins** only | Keycloak OIDC login (Bearer JWT) | Single admin capability (e.g. `ADMIN_ALL`) for ops IAM, `/admin/**`, break-glass |

No long-lived Keycloak users for writers. No `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` permission matrix for Construct-X.

---

## Goals

1. **Registry:** resolve `BPN → DID`, `legal name → DID`, `address/country → DID`, and (optionally restricted) `IBAN → DID` from signed claims.
2. **Catalogue:** continue to host Construct-X service offerings / other assets in the same node.
3. **Authoritative mappings:** operators can run a profile where VC signatures and shapes are enforced before store.
4. **VC/DCP for all users; Keycloak for admins only:** every data-posting user authenticates and authorizes with **verifiable credentials over DCP**. Keycloak remains only for **application admins** (ops login). **No** complex roles / permission specifications in Keycloak.
5. **Operable without new core query APIs** for v1; optional convenience resolve endpoints only if SPARQL ergonomics block adoption.
6. **Federated discovery:** partner catalogues can answer cross-node identifier lookups via existing `query.partners` / `POST /query/search`.

Non-goals for v1:

- Full OpenID4VP (prefer DCP + EECC library for dataspace alignment; OpenID4VP only if Construct-X explicitly requires it)
- Keeping Keycloak as a general user directory or permission engine for catalogue writers/readers
- Replacing Tractus-X BDRS API 1:1 (directory dump + MembershipCredential bearer) unless Construct-X explicitly requires that contract
- Storing secrets (IBAN) in a publicly queryable graph without an access model

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
| CX-R6 | **All users** post data via **VC + DCP**; Keycloak tokens do not authorize catalogue writes | Writer without DCP presentation rejected; writer with trusted presentation succeeds **without** Keycloak |
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
│  Users (all data posters)                                     │
│       │  DCP Verifiable Presentation Protocol (EECC dcp)      │
│       │  VCs prove identity + right-to-publish                │
│       ▼                                                       │
│  AuthN/AuthZ façade (VC / issuer policy — no Keycloak roles)  │
│       │                                                       │
│       ▼                                                       │
│  Verification (strict profile) → store → RDF / Fuseki         │
│                                                               │
│  Application admins only                                      │
│       │  Keycloak OIDC (single ADMIN_ALL-class role)          │
│       ▼                                                       │
│  /admin/** , ops IAM, break-glass — not data publish          │
└─────────────────────────────────────────────────────────────┘
```

### Target auth model (hard requirements)

| Who | How they authenticate | What they may do | Keycloak? |
|-----|----------------------|------------------|-----------|
| **Catalogue users** (everyone posting or using data APIs) | VCs presented over **DCP** | `POST` assets / participants / related write APIs; authZ from credential type + issuer trust | **No** |
| **Application admins** | Keycloak login only | `/admin/**`, residual admin ops (stats, trust-framework config, graph rebuild, …) | **Yes — only them** |

Hard rules:

1. **No Keycloak login for users.** Writers never obtain `ASSET_CREATE` (or any fine-grained role) from a realm.
2. **No complex roles/permissions in Keycloak.** Delete the fine-grained + composite catalogue (`ASSET_*`, `SCHEMA_*`, `QUERY_*`, `Ro-MU-*`, `Ro-AS-A`, `Ro-PA-A`, convenience composites like `asset-creator`, Gaia-X claim remaps). Keep **one** application-admin role (e.g. `ADMIN_ALL`).
3. **All data posts** go through DCP presentation + VC verification; Keycloak JWT must **not** be accepted as sufficient for Construct-X write paths in the authoritative profile.
4. Map former “permissions” onto **VC types / issuers / presentation scopes**, not realm role names.

Recommended credential split (CX-R5):

| Credential | Issuer | Subject | Claims |
|------------|--------|---------|--------|
| **Participant / LegalPerson VC** | Company or notary | company DID | `schema:name`, `gx:legalAddress`, `gx:legalRegistrationNumber` |
| **Identifier Reference VC** | Trusted Construct-X registry operator | company DID | `cx:bpn` (and later controlled IBAN / other refs) |

Catalogue assets (service offerings, DCS-style templates, …) remain ordinary assets on the same instance.

---

## Strip Keycloak to a minimum (concrete work)

Today authorization is almost entirely URL ↔ role matching in `SecurityConfig`, JWT `participant_id` scoping in `SessionUtils`, and Keycloak Admin API as the user/participant store. Construct-X must replace the **user** half of that with DCP+VC and leave Keycloak as an **admin-only** IdP.

### Target end state

| Area | Before (today) | After (Construct-X) |
|------|----------------|---------------------|
| Who has Keycloak accounts | Admins, participant admins, asset operators, readers, test users | **Application admins only** |
| How users post data | Bearer JWT + `ASSET_CREATE` / `Ro-*` | **DCP presentation + VCs** |
| Realm roles | Fine-grained CRUD + composites + Gaia-X remaps | **One admin role** (`ADMIN_ALL` or equivalent); no permission matrix |
| `/users`, `/roles`, participant user admin via Keycloak | First-class | Deprecate / admin-only residual or remove from Construct-X profile |
| Demo portal OIDC login for publish | Used by writers | Admin UI only; publish demos use DCP |

### Work package A — Realm & config (remove permission complexity)

1. **Slim realm JSON** (`keycloak/realms/{dev,staging,prod}/fc-realm.json`, Helm `fc-realm.json`):
   - Remove client roles: `ASSET_*`, `SCHEMA_*`, `QUERY_EXECUTE`, `Ro-MU-CA`, `Ro-MU-A`, `Ro-AS-A`, `Ro-PA-A`, convenience composites (`asset-creator`, `asset-editor`, …).
   - Keep a single application-admin role (prefer `ADMIN_ALL`; decide whether to rename/alias `Ro-MU-CA` once for migration then delete).
   - Remove non-admin seed users (`fc-restricted-test`, writer-style users); keep one admin user for ops.
   - Drop unused protocol mappers / authz settings that only served fine-grained roles (keep what admins still need).
2. **`CommonConstants`:** stop treating fine-grained role names as the Construct-X auth model; retain admin constant(s) only for Keycloak-gated paths.
3. **`CustomJwtAuthenticationConverter`:** remove Gaia-X claim → `Ro-*` remapping when Construct-X profile is active (or delete if unused).
4. **Docs / OpenAPI:** strip “Required permission: `ASSET_CREATE` …” for user write ops; document DCP presentation instead. Operator guide: Keycloak = admin login only.

### Work package B — Spring Security split (admins vs users)

Touch: `fc-service-server/.../config/SecurityConfig.java`.

1. **Keycloak JWT required only** for application-admin surfaces, e.g.:
   - `/admin/**`
   - Residual ops that stay admin-owned (trust-framework admin, graph rebuild, schema **management** if operators keep that under admin — decide in Phase 0; default: schema CRUD = admin Keycloak **or** move schema publish to DCP if Construct-X treats shapes as posted data)
2. **Data APIs must not** `hasRole(ASSET_*)` / `hasRole(QUERY_*)` / `hasAnyRole(Ro-*)` for Construct-X:
   - Writes: `POST/PUT/DELETE /assets*`, provenance/compliance mutations, `POST /participants` (user-facing registry/catalogue publish)
   - Reads/query: either **public under operator policy**, or gated by a **separate DCP presentation** if Construct-X requires credential-scoped query — **not** `QUERY_EXECUTE` in Keycloak
3. Introduce a Construct-X security profile / filter chain order:
   - DCP auth filter (or façade) establishes a principal from presentation
   - Admin chain remains OAuth2 resource server as today
4. Reject Construct-X writes that present only a Keycloak access token without DCP (authoritative profile).

### Work package C — Replace participant/user scoping for posters

1. **`SessionUtils.checkParticipantAccess`:** for DCP-authenticated requests, authorize from **VP/VC subject (DID)** and issuer policy, not JWT `participant_id` / `Ro-MU-CA` bypass.
2. **`AssetService` / `AssetUploadService` / `ParticipantsService`:** dual-path then cut Keycloak path for users; auditor (`SecurityAuditorAware`) records DID / presentation id, not Keycloak `sub`.
3. **`UsersService` role-assignment rules** (`doCheckRoleAssignmentRule`): obsolete for Construct-X users — either admin-only stub or remove from Construct-X profile.
4. Keycloak Admin DAOs (`UserDaoImpl`, `ParticipantDaoImpl`, …): keep only if application admins still manage admin accounts/groups; **do not** model catalogue participants as Keycloak groups for ordinary users.

### Work package D — DCP for all user posts (pairs with Phase 6)

1. Integrate [EECC dcp](https://github.com/european-epc-competence-center/dcp) (`dcp-spring-boot-starter`).
2. Presentation query = “right to publish” (Membership / Participant / registry-operator VCs from Phase 0).
3. On success → existing verify + store pipeline (strict profile).
4. Examples/hurl: replace password-grant / Keycloak Bearer steps with DCP presentation fixtures.
5. **`fc-demo-portal`:** OAuth2 login only for admin UI; remove publish-via-portal-login as the happy path.

### Work package E — Tests & migration

1. Update `*ControllerTest` / `@WithMockJwtAuth` suites: admin paths keep JWT; write paths assert DCP (or test doubles), not `ASSET_CREATE`.
2. Migration flag only during cutover (`auth.users=dcp`, Keycloak writers disabled); **no** long-term “both” for Construct-X production.
3. Lab compose may temporarily keep a fat realm for legacy Gaia-X demos; Construct-X authoritative compose ships the **slim admin-only** realm.

### Exit criteria (Keycloak strip)

- [ ] Construct-X realm JSON has **no** fine-grained / `Ro-*` permission matrix — admin role only
- [ ] Non-admin users have **no** Keycloak accounts in Construct-X deployments
- [ ] `POST /assets` (and agreed user write APIs) succeed with DCP+VC and **fail** with Keycloak-only Bearer
- [ ] Application admin can still log in via Keycloak and use `/admin/**`
- [ ] Operator docs state: users → DCP/VC; admins → Keycloak; no role cataloguing in Keycloak

---

## Phased delivery

### Phase 0 — Agree vocabulary and governance (docs only)

**Deliverables**

- Construct-X namespace table (reuse vs invent):
  - Prefer existing IRIs where possible (`schema:`, `gx:`, Catena-X `cx:bpn`, …)
  - Document any Construct-X-specific predicates if Catena-X IRIs are politically unwanted
- Issuer policy: who may assert `cx:bpn` (self-asserted vs registry-operator-only)
- **Write-auth credential policy:** which VC types / issuers, presented over DCP, grant user `POST` (Membership / Participant / registry-operator)
- **Admin boundary:** which ops stay Keycloak-admin-only (default: `/admin/**`, break-glass); confirm schemas/query are DCP, public, or admin
- Strict vs lab verification matrix (see Phase 2)

**Exit:** signed-off vocab + issuer + write-auth + admin-boundary policy in this repo (extend company-identifier doc or a short `construct-x-vocab.md`).

### Phase 1 — Modelling kit (examples + shapes, no core code)

**Deliverables**

1. Example JWT-VC (or signed fixture pipeline) for:
   - LegalPerson with name + address
   - Reference VC with `cx:bpn`
   - Optional restricted IBAN VC (separate file; not used in public query demo)
2. SHACL shapes (`POST /schemas`) for BPN pattern, required `credentialSubject.id`, optional uniqueness guidance
3. SPARQL library (hurl), mirroring company-identifier examples:
   - BPN → DID
   - name → DID
   - country → companies
   - “latest approved” pattern (reuse DCS provenance/version approach)
4. README under e.g. `examples/construct-x-registry-demo/` (same style as `examples/dcs-template-demo/`)

**Exit:** `hurl --test` against local stack publishes fixtures and resolves BPN→DID with semantics-only verification.

### Phase 2 — Authoritative registry profile (ops + config)

**Deliverables**

1. Documented **Construct-X strict profile** (compose overlay or env preset):
   - `FEDERATED_CATALOGUE_VERIFICATION_VC_SIGNATURE=true`
   - `FEDERATED_CATALOGUE_VERIFICATION_VP_SIGNATURE=true` (if VPs used)
   - `FEDERATED_CATALOGUE_VERIFICATION_SEMANTICS=true`
   - schema validation enabled for Construct-X shapes
   - DID resolution + trust anchors available (as for `docker-compose.strict.yml`)
2. **Keycloak strip kickoff** for this profile (full strip in Phase 6 / work packages A–E):
   - Document admin-only Keycloak boundary vs user DCP path
   - Do **not** add new Construct-X Keycloak roles; freeze / start deleting fine-grained roles from Construct-X realm drafts
3. Operator runbook section: how to reject unsigned mappings; optional `POST /assets/{id}/compliance-check` if Gaia-X notarisation is in scope
4. Negative tests: unsigned JWT, wrong issuer key, malformed BPN → rejected when profile on

**Exit:** same demo fails without signatures and passes with them; ops guide lists the env flags and the admin-only Keycloak / user-DCP intent.

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

### Phase 6 — DCP for all users + Keycloak admin-only strip

**Goal:** every catalogue **user** authenticates and authorizes posts via **VCs over DCP**. Keycloak remains **only** for **application admins**. Remove complex roles and permission specifications from Keycloak (work packages A–E above).

Treat DCP as the **user authN/authZ + verifier façade** in front of the same ingest/verify pipeline (`POST /assets` / internal `verifyCredential`):

- Do not conflate JWT-VC format support with protocol support
- **Mandatory dependency:** use the EECC DCP Java package — do not reimplement DCP wire DTOs / presentation query flows from scratch

| Item | Value |
|------|--------|
| Repository | [european-epc-competence-center/dcp](https://github.com/european-epc-competence-center/dcp) |
| Maven (core) | `de.eecc.dcp:dcp` |
| Maven (Spring Boot) | `de.eecc.dcp:dcp-spring-boot-starter` |
| Role | Verifier-side DCP v1.x for **all user write auth**; Keycloak no longer grants publish |

Integration sketch:

1. Add `dcp-spring-boot-starter` (or `dcp`) to `fc-service-server` / a dedicated DCP module.
2. Use library APIs (`DcpPresentation`, `PresentationQueryMessage`, scope / PE query definitions, SI-token validation as the package matures) to talk to holder Credential Services.
3. Define Construct-X **presentation queries** for right-to-publish (Phase 0 credential policy).
4. On successful presentation:
   - **AuthZ:** presented claims → allow user create/update (replaces all former `ASSET_*` / `Ro-*` write roles).
   - **Ingest:** hand extracted VCs/VPs into existing verification + store (Phase 2 strict profile).
5. Execute **Strip Keycloak** work packages A–E:
   - Slim realm to **admin-only** role; delete fine-grained / composite roles
   - `SecurityConfig` split: Keycloak JWT → `/admin/**` (and agreed admin ops) only
   - User writes reject Keycloak-only Bearer in Construct-X authoritative profile
   - Portal / examples / tests updated
6. Track EECC library maturity (`0.1.x`); pin version before production cutover.

**Exit:**

- Any user publishes after DCP presentation **without** a Keycloak account or token
- Keycloak-only write → rejected in Construct-X authoritative profile
- Application admin logs in with Keycloak; uses `/admin/**`
- Realm has **no** complex permission/role catalogue — admin role only
- Negative tests: bad/missing presentation, untrusted issuer, wrong credential type → rejected

OpenID4VP remains out of scope unless Construct-X explicitly requires it; prefer DCP + EECC package for dataspace alignment.

---

## Work breakdown (engineering checklist)

- [ ] **Vocab & policy** — Phase 0 write-up; admin boundary; write-auth VC types; fix “Contruct-X” → Construct-X in docs
- [ ] **Fixtures** — LegalPerson + Reference VC examples; signing notes (fc-tools / external signer)
- [ ] **SHACL** — BPN (+ optional IBAN) shapes; register via `POST /schemas` in demo
- [ ] **Discovery hurl** — BPN/name/country queries; latest-version pattern
- [ ] **Strict profile** — env/compose + negative verification tests
- [ ] **Keycloak strip A** — slim realm JSON (admin role only; delete `ASSET_*` / `SCHEMA_*` / `QUERY_*` / `Ro-*` / composites)
- [ ] **Keycloak strip B** — `SecurityConfig` admin-only JWT; user data APIs off role matchers
- [ ] **Keycloak strip C** — DID/presentation scoping replaces JWT `participant_id` for users
- [ ] **Keycloak strip D** — DCP façade for all user posts; portal admin-only login
- [ ] **Keycloak strip E** — tests, OpenAPI, operator docs, Construct-X compose with slim realm
- [ ] **Coexistence** — regression against Gaia-X / DCS demos (legacy lab realm OK outside Construct-X profile)
- [ ] **Federation** — partner search demo + conflict note
- [ ] **(Optional)** resolve façade API + OpenAPI
- [ ] **(Phase 6)** EECC [dcp](https://github.com/european-epc-competence-center/dcp) → user authZ + verify/store
- [ ] **Docs** — link this plan from operator guide / company-identifier references

No mandatory core changes for Phases 0–4 if existing ingest, verification toggles, schemas, versions, and query federation behave as documented. **Phase 6 is mandatory core work** for Construct-X: DCP user auth + Keycloak admin-only strip (work packages A–E).

---

## Verification & trust matrix

| Mode | semantics | vc-signature | schema | Suitable for |
|------|-----------|--------------|--------|--------------|
| Lab / demo | on | off | off | Local Construct-X experiments |
| Staging | on | on | on | Pre-prod registry |
| Authoritative Construct-X registry | on | on | on (+ TF if required) | Production DID↔BPN authority |

Reminder: without `vc-signature`, a too-loose DCP policy can still accept bad payloads. Prefer **DCP presentation + signature + issuer policy**. Do **not** reintroduce Keycloak roles as a substitute for cryptographic trust.

---

## Test plan (summary)

1. **Happy path:** publish Reference VC → SPARQL BPN→DID returns company DID.
2. **Split model:** Participant VC + Reference VC both queryable on same subject.
3. **Strict reject:** unsigned / bad signature → `POST /assets` fails.
4. **Shape reject:** invalid BPN → fail when schema enabled.
5. **Versioning:** v2 supersedes v1; discovery prefers approved latest.
6. **Catalogue coexistence:** upload offering + registry VC; both discoverable with type filters.
7. **Federation:** partner-only BPN resolved via `POST /query/search`.
8. **Authz (users):** DCP presentation with trusted right-to-publish VC → write allowed **without** Keycloak; missing/wrong presentation → rejected; Keycloak-only Bearer → rejected on Construct-X write APIs.
9. **Authz (admins):** application admin Keycloak login → `/admin/**` OK; non-admin Keycloak user (if any remain in lab) cannot substitute for DCP on user write paths.

Executable form: extend `examples/` with a Construct-X hurl suite analogous to `dcs-template-demo` and `queries/verify-against-fuseki.hurl`; add a Phase 6 DCP presentation scenario (no password-grant for writers).

---

## Risks

| Risk | Mitigation |
|------|------------|
| Expectation of DCP “support” | Docs state JWT format ≠ DCP protocol; Phase 6 uses EECC [dcp](https://github.com/european-epc-competence-center/dcp), not a custom stack |
| EECC dcp still `0.1.x` | Pin version; gate production on SI-token / VP validation façades; short migration window only |
| Dual auth confusion (Keycloak + DCP) | Construct-X profile: users = DCP only; admins = Keycloak only; reject Keycloak on user writes |
| Rebuilding a role matrix in Keycloak | Explicit non-goal; single `ADMIN_ALL`; permissions live in VC/issuer policy |
| Legacy Gaia-X demos need fat realm | Keep fat realm on **lab/legacy** compose only; Construct-X authoritative compose is slim |
| Lab defaults → false sense of security | Strict profile mandatory in Construct-X ops guide |
| Public graph leaks IBAN | Separate asset / omit / vault; CX-R9 in demo defaults |
| Cross-node conflicting BPN mappings | Document multi-hit; no automatic overwrite |
| Vocab fork vs Catena-X IRIs | Phase 0 decision; prefer reuse for tooling |

---

## Suggested sequence for first PR series

1. Docs: this plan + vocab/issuer/admin-boundary + Keycloak strip work packages + link from company-identifier references  
2. `examples/construct-x-registry-demo/` fixtures + hurl (semantics-only; temporary Keycloak OK until Phase 6)  
3. SHACL + schema registration in demo  
4. Strict-profile overlay/docs + negative tests  
5. Federation scenario  
6. Phase 6: EECC DCP façade + user posts without Keycloak  
7. Keycloak strip: slim realm (admin only), `SecurityConfig` split, portal/examples/tests  
8. Optional resolve API only after integrator feedback  

---

## Summary

Construct-X uses the Federated Catalogue as **catalogue and registry at once**: identifier VCs on the ingest path, a **strict verification profile**, SPARQL discovery (and federation). **All users** authenticate and authorize **data posts with VCs over DCP** ([EECC dcp](https://github.com/european-epc-competence-center/dcp) / `de.eecc.dcp`). **Keycloak is stripped to a minimum:** only **application admins** log in; the realm keeps a **single admin role** with **no** complex permission matrix. Former `ASSET_*` / `Ro-*` semantics move to credential types and issuer policy—not Keycloak.

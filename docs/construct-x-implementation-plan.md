# Construct-X implementation plan

Make one Federated Catalogue deployment serve as **catalogue** (assets / offerings / credentials) and **company registry** (DID ↔ secondary identifiers) for Construct-X, without a separate registry microservice.

This plan turns the modelling in [`company-identifier-references.md`](./company-identifier-references.md) into concrete delivery work, given how the catalogue actually works today.

## Current baseline (constraints)

| Fact | Implication for Construct-X |
|------|-----------------------------|
| Credentials arrive only via **HTTP REST** (`POST /assets`, `POST /participants`, `POST /verification`) | Registry writes are push ingest, not DCP / OpenID4VP / DIDComm presentation exchange |
| JWT-VC/VP formats are accepted; LD proofs are rejected | Issue Construct-X credentials as **standard JWT-VC** (or Gaia-X Loire JWT if co-aligned) |
| “IDSA/DCP trust frameworks” in docs means **payload shape compatibility**, not a DCP wire protocol | Do **not** plan a DCP verifier endpoint in v1 |
| Verification is real but **toggleable**; docker defaults enable **semantics only** (`vc-signature` / `vp-signature` often `false`) | An authoritative registry profile **must** turn signatures (and ideally schema + trust framework) on |
| Claims are projected into RDF and discovered via **`POST /query`** / **`POST /query/search`** | Registry lookups are SPARQL (or a thin façade over it), not a new graph store |
| Auth is Keycloak roles (`ASSET_CREATE`, `QUERY_EXECUTE`, …) | Who may publish mappings is an IAM + issuer-trust question, not “anyone with a VC” |

**Dual-role principle:** keep one API surface. Catalogue assets and registry reference credentials share ingest, verification, versioning, and query. Differ only by vocabulary, SHACL, and operator policy.

---

## Goals

1. **Registry:** resolve `BPN → DID`, `legal name → DID`, `address/country → DID`, and (optionally restricted) `IBAN → DID` from signed claims.
2. **Catalogue:** continue to host Construct-X service offerings / other assets in the same node.
3. **Authoritative mappings:** operators can run a profile where VC signatures and shapes are enforced before store.
4. **Operable without new core APIs** for v1; optional convenience endpoints only if SPARQL ergonomics block adoption.
5. **Federated discovery:** partner catalogues can answer cross-node identifier lookups via existing `query.partners` / `POST /query/search`.

Non-goals for v1:

- Implementing DCP / OpenID4VP presentation protocol
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
| CX-R6 | Publish gated by Keycloak roles | Unauthorized `POST /assets` rejected |
| CX-R7 | Authoritative mode: signatures + trust-framework (+ optional compliance) | Strict profile rejects unsigned / bad issuer |
| CX-R8 | Updates via versions / provenance; stale mappings supersedable | Version query prefers latest approved |
| CX-R9 | Sensitive fields (IBAN) not casually public | Separate asset / role / omit from public shapes |
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
│  Writers (registry operators / participants)                  │
│       │  JWT-VC  POST /assets | POST /participants            │
│       ▼                                                       │
│  Verification (strict profile)                                │
│    · JWT signature (issuer DID assertionMethod)               │
│    · optional Loire / trust-anchor policy                     │
│    · semantics + Construct-X SHACL                            │
│       ▼                                                       │
│  Asset store + RDF graph (Fuseki, RDF-star)                   │
│       │                                                       │
│       ├── Catalogue consumers: offerings, templates, …        │
│       └── Registry consumers: SPARQL / thin resolve API       │
│                                                               │
│  Federation: POST /query/search → partner catalogues          │
└─────────────────────────────────────────────────────────────┘
```

Recommended credential split (CX-R5):

| Credential | Issuer | Subject | Claims |
|------------|--------|---------|--------|
| **Participant / LegalPerson VC** | Company or notary | company DID | `schema:name`, `gx:legalAddress`, `gx:legalRegistrationNumber` |
| **Identifier Reference VC** | Trusted Construct-X registry operator | company DID | `cx:bpn` (and later controlled IBAN / other refs) |

Catalogue assets (service offerings, DCS-style templates, …) remain ordinary assets on the same instance.

---

## Phased delivery

### Phase 0 — Agree vocabulary and governance (docs only)

**Deliverables**

- Construct-X namespace table (reuse vs invent):
  - Prefer existing IRIs where possible (`schema:`, `gx:`, Catena-X `cx:bpn`, …)
  - Document any Construct-X-specific predicates if Catena-X IRIs are politically unwanted
- Issuer policy: who may assert `cx:bpn` (self-asserted vs registry-operator-only)
- Strict vs lab verification matrix (see Phase 2)

**Exit:** signed-off vocab + issuer policy in this repo (extend company-identifier doc or a short `construct-x-vocab.md`).

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
2. Keycloak role mapping for Construct-X operators (`ASSET_CREATE` for registry writers; `QUERY_EXECUTE` for resolvers)
3. Operator runbook section: how to reject unsigned mappings; optional `POST /assets/{id}/compliance-check` if Gaia-X notarisation is in scope
4. Negative tests: unsigned JWT, wrong issuer key, malformed BPN → rejected when profile on

**Exit:** same demo fails without signatures and passes with them; ops guide lists the env flags.

### Phase 3 — Catalogue + registry coexistence

**Deliverables**

1. One compose profile / deployment story that loads:
   - Gaia-X / Construct-X participant schemas
   - Construct-X identifier shapes
   - Existing catalogue query demos still green
2. Guidance: shared graph means **query filters by type/predicate**; document recommended `FILTER` / type guards so registry lookups do not collide with offering graphs
3. Sensitive-data policy: IBAN (and similar) either omitted, separate asset with restricted roles, or out-of-band vault — never in the default public fixture set

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
- Same auth as `QUERY_EXECUTE`
- Still no DCP — HTTP GET/POST only

Defer if Phase 1 SPARQL kit is enough for Construct-X integrators.

### Phase 6 — Future: presentation protocols (explicit backlog)

If Construct-X later requires wallet-driven presentation (DCP / OpenID4VP):

- Treat as a **new verifier façade** in front of the same ingest/verify pipeline
- Out of scope until a concrete protocol + authn story is chosen
- Do not conflate JWT-VC format support with protocol support

---

## Work breakdown (engineering checklist)

- [ ] **Vocab & policy** — Phase 0 write-up; fix “Contruct-X” → Construct-X in docs
- [ ] **Fixtures** — LegalPerson + Reference VC examples; signing notes (fc-tools / external signer)
- [ ] **SHACL** — BPN (+ optional IBAN) shapes; register via `POST /schemas` in demo
- [ ] **Discovery hurl** — BPN/name/country queries; latest-version pattern
- [ ] **Strict profile** — env/compose + negative verification tests
- [ ] **IAM** — Keycloak roles/docs for registry writers vs queriers
- [ ] **Coexistence** — regression against Gaia-X / DCS demos
- [ ] **Federation** — partner search demo + conflict note
- [ ] **(Optional)** resolve façade API + OpenAPI
- [ ] **Docs** — link this plan from operator guide / company-identifier references

No mandatory core changes for Phases 0–4 if existing ingest, verification toggles, schemas, versions, and query federation behave as documented. Core work appears only for Phase 5+ or if gaps are found (e.g. schema not enforced on ingest path).

---

## Verification & trust matrix

| Mode | semantics | vc-signature | schema | Suitable for |
|------|-----------|--------------|--------|--------------|
| Lab / demo | on | off | off | Local Construct-X experiments |
| Staging | on | on | on | Pre-prod registry |
| Authoritative Construct-X registry | on | on | on (+ TF if required) | Production DID↔BPN authority |

Reminder: without `vc-signature`, anyone with `ASSET_CREATE` can publish arbitrary mappings; role gates alone are not cryptographic trust.

---

## Test plan (summary)

1. **Happy path:** publish Reference VC → SPARQL BPN→DID returns company DID.
2. **Split model:** Participant VC + Reference VC both queryable on same subject.
3. **Strict reject:** unsigned / bad signature → `POST /assets` fails.
4. **Shape reject:** invalid BPN → fail when schema enabled.
5. **Versioning:** v2 supersedes v1; discovery prefers approved latest.
6. **Catalogue coexistence:** upload offering + registry VC; both discoverable with type filters.
7. **Federation:** partner-only BPN resolved via `POST /query/search`.
8. **Authz:** missing `ASSET_CREATE` / `QUERY_EXECUTE` → 401/403.

Executable form: extend `examples/` with a Construct-X hurl suite analogous to `dcs-template-demo` and `queries/verify-against-fuseki.hurl`.

---

## Risks

| Risk | Mitigation |
|------|------------|
| Expectation of DCP “support” | Docs state JWT format ≠ DCP protocol; Phase 6 backlog only |
| Lab defaults → false sense of security | Strict profile mandatory in Construct-X ops guide |
| Public graph leaks IBAN | Separate asset / omit / vault; CX-R9 in demo defaults |
| Cross-node conflicting BPN mappings | Document multi-hit; no automatic overwrite |
| Vocab fork vs Catena-X IRIs | Phase 0 decision; prefer reuse for tooling |

---

## Suggested sequence for first PR series

1. Docs: this plan + vocab/issuer policy + link from company-identifier references  
2. `examples/construct-x-registry-demo/` fixtures + hurl (semantics-only)  
3. SHACL + schema registration in demo  
4. Strict-profile overlay/docs + negative tests  
5. Federation scenario  
6. Optional resolve API only after integrator feedback  

---

## Summary

Construct-X can use the Federated Catalogue as **catalogue and registry at once** by standardising identifier VCs on the existing HTTP ingest path, enabling a **strict verification profile** for authoritative mappings, and discovering companies via SPARQL (and federation). No DCP implementation is required for v1; trust comes from JWT verification, shapes, issuer policy, and IAM—not from a separate registry service.

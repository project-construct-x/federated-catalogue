# Construct-X registry demo

Fixtures and queries for using the Federated Catalogue as the **Construct-X company
registry** and (from Phase 6) as the **DCP write-auth** gate. See
[`docs/construct-x-implementation-plan.md`](../../docs/construct-x-implementation-plan.md).

## Story (auth first)

> A participant is onboarded to Construct-X. The Construct-X issuer issues a
> **MembershipCredential** to the participant's wallet DID. Later, whenever that
> participant calls a catalogue write API over **DCP**, the presentation **must**
> include this membership credential so the catalogue can authorize the caller as a
> Construct-X member.
>
> Registry payloads (LegalPerson, BPN reference, …) are separate assets; membership is the
> **right-to-publish** credential, not the company-profile document.

## Files

| File | Purpose |
|------|---------|
| `membership-credential-v2.jsonld` | **Default write-auth VC** — VCDM 2.0 (`validFrom` / `validUntil`) encoding of the Construct-X membership data model. Use this in demos and Phase 6 DCP presentations. |
| `membership-credential-v1.jsonld` | VCDM 1.1 JSON-LD mirror of what Construct-X issuers currently mint (`issuanceDate` / `expirationDate`). Compatibility / reference only. |
| `membership-credential-v1.example.vc.jwt` | Real signed JWT-VC from `did:web:issuer.int.construct-x.net:issuer` (Ed25519). Source for the v1 claim shape. |

Further Phase 1 fixtures (LegalPerson, BPN Reference VC, SHACL, SPARQL hurl) will land in this
folder next.

## Membership data model

Aligned with credentials issued by the Construct-X int issuer (decoded from
`membership-credential-v1.example.vc.jwt`).

| Field | Example | Role |
|-------|---------|------|
| `type` | `MembershipCredential` | VC type matched by the DCP presentation query |
| `issuer` | `did:web:issuer.int.construct-x.net:issuer` | Construct-X onboarding issuer (trusted for write-auth) |
| `credentialSubject.id` | wallet / participant DID | Member holder — the DCP presenter |
| `isConsumer` / `isProvider` | booleans | Dataspace role flags on the member |
| `credentialStatus` | `BitstringStatusListEntry` | Revocation via status-list credential |

The live JWT also carried an opaque `credentialSubject.foo.bar` extension property; demos omit
issuer-specific extras and keep the stable membership claims above.

### v1 (VCDM 1.1) vs v2 (VCDM 2.0, default)

| | v1 | v2 (default) |
|--|----|--------------|
| `@context` | `https://www.w3.org/2018/credentials/v1` | `https://www.w3.org/ns/credentials/v2` (+ status/v1) |
| Validity | `issuanceDate` / `expirationDate` | `validFrom` / `validUntil` |
| Subject claims | same (`id`, `isConsumer`, `isProvider`) | same |
| Status | `BitstringStatusListEntry` | same |
| Wire format today | JWT-VC with `vc` claim (see `.example.vc.jwt`) | JSON-LD fixture; sign to VC-JWT 2.0 for strict / DCP |

Catalogue demos and the Phase 6 presentation policy target **v2**. Keep v1 to round-trip
against credentials already issued in Construct-X int.

## Why this credential comes first

| Concern | Rule |
|---------|------|
| Who may `POST` data? | Caller presents a trusted **MembershipCredential** over DCP |
| What replaces Keycloak `ASSET_*` / `Ro-*`? | This VC type + issuer trust policy (+ optional status-list check) |
| Is membership the company profile? | **No** — LegalPerson / Reference VCs carry name, address, BPN claims for SPARQL discovery |
| Lab today vs Phase 6 | Fixtures exist now; catalogue still uses Keycloak for writes until the DCP façade lands |

Phase 6 presentation query (intent): require `type` containing `MembershipCredential`, an
issuer on the Construct-X trust list, and (when enabled) a non-revoked `credentialStatus`.
Missing or wrong membership → write rejected even if a Keycloak Bearer is present.

## Prerequisites

- Same as [`../README.md`](../README.md): local compose stack, `hurl`, Keycloak user for lab
  publishes until Phase 6.
- Signing for strict mode: same flow as
  [`../dcs-template-demo/README.md`](../dcs-template-demo/README.md#signing-the-fixtures-for-strict-mode)
  (`fc-tools/signing/generate-jwt-fixture.py`).

## Using the fixtures (lab)

Until DCP is wired, treat **v2** as the canonical membership payload to sign and later attach
to presentations:

```bash
# Inspect default (VCDM 2.0)
jq . membership-credential-v2.jsonld

# Sign v2 for strict / DCP demos
python3 ../../fc-tools/signing/generate-jwt-fixture.py \
  --payload membership-credential-v2.jsonld \
  --key ../../docker/did-server/certs/jwt-signing.pem \
  --output membership-credential-v2.vc.jwt

# Inspect the real int-env JWT (v1 wire format)
python3 -c "import sys,json,base64; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(p)), indent=2))" \
  "$(cat membership-credential-v1.example.vc.jwt)"
```

Executable hurl (publish membership, then registry VCs, then BPN→DID SPARQL) will be added
with the remaining Phase 1 fixtures.

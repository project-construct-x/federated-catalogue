# EDC to Cataglogue POST Asset

## DCP Flow General

A client wants access to something the verifier protects. The verifier does not trust the client’s word alone; it fetches Verifiable Presentations from the holder’s Credential Service.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (Holder)
    participant STS as Secure Token Service
    participant V as Verifier
    participant DID as DID resolver
    participant CS as Credential Service

    Note over C,STS: Phase 1 — Obtain SI token
    C->>STS: Request SI token (optional scopes)
    STS-->>C: SI token (+ optional token claim)

    Note over C,V: Phase 2 — Request protected resource
    C->>V: Authorization: Bearer SI token

    Note over V,DID: Phase 3 — Resolve holder identity
    V->>DID: Resolve DID from sub claim
    DID-->>V: DID document

    critical Client token validation
        Note over V: iss must equal sub
        Note over V: Valid signature, aud, exp, jti
        Note over V: Find CredentialService in DID doc
    end

    Note over V,CS: Phase 4 — Pull credentials
    V->>CS: POST /presentations/query
    Note right of V: PresentationQueryMessage<br/>+ verifier SI token<br/>+ forward client token if present

    critical CS authorization
        Note over CS: Validate verifier SI token
        Note over CS: scope XOR presentationDefinition only
    end

    CS-->>V: PresentationResponseMessage

    critical VP validation
        Note over V: Signatures, auth relationship
        Note over V: Issuer binding, revocation, expiry
    end

    alt Credentials valid
        V-->>C: Access granted
    else Validation failed
        V-->>C: Access denied
    end
```

**Presentation query conditions** ([spec](https://eclipse-dataspace-dcp.github.io/decentralized-claims-protocol/v1.0.1/#presentation-query-message)):

- `PresentationQueryMessage` must contain **`scope`** or **`presentationDefinition`**, not both
- **`scope`**: normative aliases include `org.eclipse.dspace.dcp.vc.type:…` and `org.eclipse.dspace.dcp.vc.id:…`
- If the query used **`presentationDefinition`**, the response **must** include **`presentationSubmission`**
- Credential Service may return fewer presentations than requested if the holder is not entitled to some scopes (still HTTP 2xx)

## DCP Catalogue Flow

Identity Hub does not sign the asset. It stores the `MembershipCredential` (issued earlier by the trusted membership issuer) and its STS issues the client SI token. The `POST /assets` body is the asset. A `PresentationResponseMessage` as that body is rejected. The catalogue only allows self-publishing: the asset issuer DID must equal the membership subject, which must equal the SI holder.

There are two SI tokens. The connector sends one whose audience is the catalogue (`Authorization: Bearer` on `POST /assets`, `iss` = `sub` = participant DID, `capabilityInvocation` key, fresh `jti`). The catalogue then mints its own verifier SI token, audience set to the holder's Credential Service, and uses that on the outbound query. The client `jti` is consumed in PostgreSQL before that call.

The pull is a scoped presentation query, not a fetch of the latest credentials. The catalogue resolves the `CredentialService` endpoint from the holder DID document, then `POST`s a `PresentationQueryMessage` to `{credentialService}/presentations/query`. It accepts exactly one signed presentation containing exactly one `MembershipCredential` from the configured issuer, bound to that holder, and verifies both signatures before storing the asset.

```mermaid
sequenceDiagram
    autonumber
    participant Issuer as Membership issuer
    participant EDC as EDC connector
    participant IH as Participant Identity Hub
    participant FC as Federated Catalogue

    Note over Issuer, IH: Before publish
    Issuer->>IH: Store MembershipCredential for the participant DID

    Note over EDC, FC: Publish
    EDC->>EDC: Sign the asset so its issuer DID is the participant DID
    EDC->>IH: Request an SI token with audience set to the catalogue
    IH-->>EDC: Client SI token, iss = sub = participant DID
    EDC->>FC: POST /assets, Bearer client SI token, body is the asset
    FC->>FC: Validate the SI token and consume its jti
    FC->>FC: Resolve CredentialService from the holder DID document
    FC->>FC: Mint a verifier SI token, audience = Credential Service URL
    FC->>IH: POST /presentations/query, Bearer verifier SI token, MembershipCredential for this holder
    IH-->>FC: One presentation with one MembershipCredential
    FC->>FC: Verify both signatures and that subject equals holder
    FC->>FC: Store the asset only if its issuer equals that subject
    FC-->>EDC: 201 Created
```

## POST Asset Credential Flow

**Christian Weiers**

## EDC Extension

**Ernst Schrewe**

## Auth

### Policies

- Access policy for query
- Contract policy
- Asset Post policy (DCP auth)




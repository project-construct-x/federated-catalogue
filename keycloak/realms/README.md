# Keycloak Realm Configuration

Each environment-specific subdirectory contains a single ecosystem-neutral realm import file
`fc-realm.json` (realm name `federated-catalogue-realm`).

| Directory  | Purpose                                      |
|------------|----------------------------------------------|
| `dev/`     | Local development (docker-compose stack)     |
| `staging/` | Staging environment                          |
| `prod/`    | Production environment                       |

The realm name is selected at runtime via `KEYCLOAK_REALM` (default `federated-catalogue-realm`).
The name in the imported JSON must match `KEYCLOAK_REALM` for the application to authenticate against it.

## Admin-only authentication

The Federated Catalogue client uses Keycloak only for application-administrator authentication.
It defines one application role, `ADMIN_ALL`. Catalogue and registry permissions are not represented
as Keycloak roles; machine operations are authorized through DCP membership-credential policies.

The development realm contains one local administrator account. Staging and production administrators
must be treated as operator accounts and assigned `ADMIN_ALL`. Do not add catalogue users, connectors,
participants, fine-grained `ASSET_*` / `SCHEMA_*` / `QUERY_*` roles, or `Ro-*` composites to these realms.

The `federated-catalogue` client disables direct-access grants and Keycloak Authorization Services.
Interactive administrator login uses the OIDC authorization-code flow.

The service account is enabled for the catalogue server's separate client-credentials access to the
Keycloak Admin API. It has only `realm-management/manage-users` (user CRUD and user role mappings) and
`realm-management/view-clients` (lookup of the catalogue client and its roles). It does not receive
`ADMIN_ALL` or `realm-admin`. These are Keycloak's built-in administrative permissions, not catalogue
machine-operation roles. The server continues to require a human administrator's `ADMIN_ALL` token
for `/users` and `/roles`, and DCP for protected machine operations.

### Updating an already imported realm

Restarting Keycloak with `--import-realm` does not update an existing realm. Apply the following to the
configured realm (including an existing `gaia-x` realm where applicable):

1. Open client `federated-catalogue` and enable **Service accounts roles** under its authentication
   capabilities; keep client authentication enabled and direct-access grants disabled.
2. In **Service account roles**, assign the client roles `manage-users` and `view-clients` from
   `realm-management`. Do not grant `realm-admin` or the catalogue's `ADMIN_ALL` role to this account.
3. Ensure the server's `KEYCLOAK_CREDENTIALS_SECRET` matches this client's secret. No new secret is
   needed when enabling the existing account. The supplied client uses full scope, so the assigned
   roles are included in its token; deployments with restricted scope must also allow these roles.
4. Log in to the portal as an application administrator and verify `/admin/me`, `/users` and `/roles`.
   Verify that non-admin users are still denied. A service-account token alone must not grant access
   to catalogue admin or protected machine APIs.

Do not delete a database or clear migration checksums to apply this realm update.

### Existing `gaia-x` deployments

Keycloak's `--import-realm` only imports when the realm does not already exist in the database, so
existing deployments (e.g. our QA stage) already have a `gaia-x` realm persisted in Postgres. They
continue to work by setting `KEYCLOAK_REALM=gaia-x` — no import file is needed.

A fresh deployment that wants a `gaia-x` realm name must supply its own realm import JSON via a
volume mount or ConfigMap; we no longer ship one.

## Why three copies?

Each environment uses a different Keycloak client secret (`FC_CLIENT_SECRET`) and may have
environment-specific redirect URIs or administrator accounts. The correct file is
selected at container startup via the `KC_IMPORT` env var or the `dev.sh` profile mechanism
in `docker/`.

## Updating realm config

1. Export the updated realm from Keycloak admin UI (`Realm settings → Action → Partial export`).
2. Replace the appropriate environment file.
3. Do **not** commit real secrets — the `clientSecret` field must use a placeholder value
   (`REPLACE_ME`) that is overridden at runtime via `FC_CLIENT_SECRET`.

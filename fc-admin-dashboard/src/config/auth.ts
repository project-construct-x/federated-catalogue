export const clientId = import.meta.env.VITE_OIDC_CLIENT_ID || "federated-catalogue";

const realm = "federated-catalogue-realm";

/** Staging Keycloak. Local `npm run dev` authenticates here, not on localhost. */
const stagingIssuer =
  "https://federated-catalogue.staging.construct-x.net/auth/realms/federated-catalogue-realm";

/** Realm issuer. A deployed dashboard uses `/auth` on the same host. */
export function issuer(): string {
  const configured = import.meta.env.VITE_OIDC_ISSUER?.replace(/\/$/, "");
  if (configured) return configured;
  if (import.meta.env.DEV) return stagingIssuer;
  return `${location.origin}/auth/realms/${realm}`;
}

/** Must match a redirect URI registered on the Keycloak client. */
export function redirectUri(): string {
  return `${location.origin}${import.meta.env.BASE_URL}`;
}

export function logoutRedirectUri(): string {
  return `${location.origin}${import.meta.env.BASE_URL}login`;
}

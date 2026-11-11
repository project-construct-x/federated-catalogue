import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { clientId, issuer, logoutRedirectUri, redirectUri } from "@/config/auth";
import type { JwtProfile } from "@/types";

interface Pkce {
  verifier: string;
  state: string;
}

interface TokenResponse {
  access_token: string;
  id_token?: string;
  refresh_token?: string;
  error?: string;
  error_description?: string;
}

function randomString(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("=", "").replaceAll("+", "-").replaceAll("/", "_");
}

async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

export function decodeJwt(token: string): JwtProfile {
  try {
    const payload = token.split(".")[1];
    if (!payload) return {};
    const json = atob(payload.replaceAll("-", "+").replaceAll("_", "/"));
    return JSON.parse(json) as JwtProfile;
  } catch {
    return {};
  }
}

function expired(token: string | null, skewSeconds = 30): boolean {
  if (!token) return true;
  const exp = (decodeJwt(token) as { exp?: number }).exp;
  if (!exp) return false;
  return exp * 1000 <= Date.now() + skewSeconds * 1000;
}

let refreshInFlight: Promise<boolean> | null = null;

export const useAuthStore = defineStore(
  "auth",
  () => {
    const accessToken = ref<string | null>(null);
    const idToken = ref<string | null>(null);
    const refreshToken = ref<string | null>(null);
    const pkce = ref<Pkce | null>(null);

    const profile = computed(() => (accessToken.value ? decodeJwt(accessToken.value) : {}));
    const displayName = computed(
      () => profile.value.name || profile.value.preferred_username || profile.value.email || ""
    );
    const isAdmin = computed(() =>
      (profile.value.resource_access?.["federated-catalogue"]?.roles ?? []).includes("ADMIN_ALL")
    );
    const isAuthenticated = computed(() => !!accessToken.value);

    function storeTokens(tokens: TokenResponse) {
      accessToken.value = tokens.access_token;
      idToken.value = tokens.id_token ?? idToken.value;
      refreshToken.value = tokens.refresh_token ?? refreshToken.value;
    }

    function clear() {
      accessToken.value = null;
      idToken.value = null;
      refreshToken.value = null;
      pkce.value = null;
    }

    async function login() {
      const verifier = randomString();
      const state = randomString();
      pkce.value = { verifier, state };

      const url = new URL(`${issuer()}/protocol/openid-connect/auth`);
      url.searchParams.set("response_type", "code");
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("redirect_uri", redirectUri());
      url.searchParams.set("scope", "openid profile email");
      url.searchParams.set("state", state);
      url.searchParams.set("code_challenge", await codeChallenge(verifier));
      url.searchParams.set("code_challenge_method", "S256");
      location.assign(url.toString());
    }

    async function completeLogin(code: string, state: string) {
      const pending = pkce.value;
      if (!pending || pending.state !== state) {
        throw new Error("Login could not be verified. Start again.");
      }

      const body = new URLSearchParams({
        grant_type: "authorization_code",
        client_id: clientId,
        code,
        redirect_uri: redirectUri(),
        code_verifier: pending.verifier
      });
      const tokens = await requestToken(body);
      storeTokens(tokens);
      pkce.value = null;
    }

    async function refresh(): Promise<boolean> {
      if (!refreshToken.value) {
        clear();
        return false;
      }
      refreshInFlight ??= doRefresh().finally(() => {
        refreshInFlight = null;
      });
      return refreshInFlight;
    }

    async function doRefresh(): Promise<boolean> {
      try {
        const body = new URLSearchParams({
          grant_type: "refresh_token",
          client_id: clientId,
          refresh_token: refreshToken.value || ""
        });
        storeTokens(await requestToken(body));
        return true;
      } catch {
        clear();
        return false;
      }
    }

    async function ensureFresh(): Promise<boolean> {
      if (!accessToken.value) return false;
      if (!expired(accessToken.value)) return true;
      return refresh();
    }

    function logout() {
      const hint = idToken.value;
      clear();
      const url = new URL(`${issuer()}/protocol/openid-connect/logout`);
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("post_logout_redirect_uri", logoutRedirectUri());
      if (hint) url.searchParams.set("id_token_hint", hint);
      location.assign(url.toString());
    }

    return {
      accessToken,
      idToken,
      refreshToken,
      pkce,
      profile,
      displayName,
      isAdmin,
      isAuthenticated,
      login,
      completeLogin,
      refresh,
      ensureFresh,
      logout,
      clear
    };
  },
  {
    persist: {
      pick: ["accessToken", "idToken", "refreshToken", "pkce"]
    }
  }
);

async function requestToken(body: URLSearchParams): Promise<TokenResponse> {
  const response = await fetch(`${issuer()}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body
  });
  const tokens = (await response.json()) as TokenResponse;
  if (!response.ok || tokens.error || !tokens.access_token) {
    throw new Error(tokens.error_description || tokens.error || "Keycloak rejected the login.");
  }
  return tokens;
}

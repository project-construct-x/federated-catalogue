import { ApiError } from "@/types";
import { useAuthStore } from "@/stores/auth";

function endpoint(path: string): string {
  const base = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
  return `${base}${path}`;
}

export function api<T>(path: string, retry = true): Promise<T> {
  return request<T>(path, { method: "GET" }, retry);
}

export function apiPost<T>(path: string, body: string, contentType: string): Promise<T> {
  return request<T>(path, { method: "POST", body, contentType }, true);
}

async function request<T>(
  path: string,
  init: { method: string; body?: string; contentType?: string },
  retry: boolean
): Promise<T> {
  const auth = useAuthStore();
  const fresh = await auth.ensureFresh();
  if (!fresh) {
    throw new ApiError("Sign in again to continue.", 401);
  }

  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${auth.accessToken}`
  };
  if (init.contentType) headers["Content-Type"] = init.contentType;

  const response = await fetch(endpoint(path), {
    method: init.method,
    headers,
    body: init.body
  });

  if (response.status === 401 && retry) {
    const refreshed = await auth.refresh();
    if (refreshed) return request<T>(path, init, false);
    throw new ApiError("Sign in again to continue.", 401);
  }

  if (!response.ok) {
    const message = await readError(response);
    throw new ApiError(message, response.status);
  }

  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body.message) return body.message;
  } catch {
    // The catalogue sometimes returns an empty error body.
  }
  if (response.status === 403) {
    return "This account does not have the federated-catalogue role ADMIN_ALL.";
  }
  return `Request failed (${response.status}).`;
}

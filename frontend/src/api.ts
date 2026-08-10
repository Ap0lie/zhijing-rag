import type { CurrentUser } from "./types";

interface CsrfResponse {
  token: string;
  headerName: string;
}

interface ErrorBody {
  code?: string;
  message?: string;
  fields?: Record<string, string>;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

let csrfToken: CsrfResponse | null = null;
let csrfRequest: Promise<CsrfResponse> | null = null;

export function resetCsrfToken() {
  csrfToken = null;
  csrfRequest = null;
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }
  const contentType = response.headers.get("content-type") ?? "";
  return contentType.includes("application/json") ? response.json() : response.text();
}

async function fetchCsrf(force = false): Promise<CsrfResponse> {
  if (force) {
    csrfToken = null;
    csrfRequest = null;
  }
  if (csrfToken) {
    return csrfToken;
  }
  if (!csrfRequest) {
    csrfRequest = fetch("/api/v1/auth/csrf", { credentials: "include" })
      .then(async (response) => {
        if (!response.ok) {
          throw new ApiError(response.status, "CSRF_UNAVAILABLE", "无法建立安全会话");
        }
        return (await response.json()) as CsrfResponse;
      })
      .then((token) => {
        csrfToken = token;
        return token;
      })
      .finally(() => {
        csrfRequest = null;
      });
  }
  return csrfRequest;
}

async function authenticatedFetch(
  path: string,
  init: RequestInit,
  refreshCsrf = false,
): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    const csrf = await fetchCsrf(refreshCsrf);
    headers.set(csrf.headerName, csrf.token);
  }
  return fetch(path, { ...init, method, headers, credentials: "include" });
}

function responseError(response: Response, body: unknown): ApiError {
  if (response.status === 401) {
    resetCsrfToken();
  }
  const error = typeof body === "object" && body !== null ? (body as ErrorBody) : {};
  return new ApiError(
    response.status,
    error.code ?? "REQUEST_FAILED",
    error.message ?? "请求失败，请稍后重试",
    error.fields,
  );
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const requiresCsrf = !["GET", "HEAD", "OPTIONS"].includes(method);

  let response = await authenticatedFetch(path, init);
  if (requiresCsrf && response.status === 403) {
    await readBody(response);
    response = await authenticatedFetch(path, init, true);
  }
  const body = await readBody(response);
  if (!response.ok) {
    throw responseError(response, body);
  }
  return body as T;
}

export async function apiStreamRequest(path: string, init: RequestInit): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const requiresCsrf = !["GET", "HEAD", "OPTIONS"].includes(method);
  let response = await authenticatedFetch(path, init);
  if (requiresCsrf && response.status === 403) {
    await readBody(response);
    response = await authenticatedFetch(path, init, true);
  }
  if (!response.ok) {
    throw responseError(response, await readBody(response));
  }
  if (!response.body) {
    throw new ApiError(502, "STREAM_UNAVAILABLE", "服务未返回可读取的数据流");
  }
  return response;
}

export async function loginRequest(username: string, password: string): Promise<CurrentUser> {
  const body = new URLSearchParams({ username, password });
  const send = async (refreshCsrf = false) => {
    const csrf = await fetchCsrf(refreshCsrf);
    return fetch("/api/v1/auth/login", {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        [csrf.headerName]: csrf.token,
      },
      body,
    });
  };

  let response = await send();
  if (response.status === 403) {
    await readBody(response);
    response = await send(true);
  }
  const result = await readBody(response);
  if (!response.ok) {
    const error = typeof result === "object" && result !== null ? (result as ErrorBody) : {};
    throw new ApiError(
      response.status,
      error.code ?? "INVALID_CREDENTIALS",
      error.message ?? "用户名或密码不正确",
    );
  }

  resetCsrfToken();
  return result as CurrentUser;
}

export async function logoutRequest(): Promise<void> {
  try {
    await apiRequest<void>("/api/v1/auth/logout", { method: "POST" });
  } finally {
    resetCsrfToken();
  }
}

export async function downloadRequest(path: string): Promise<{ blob: Blob; filename: string }> {
  const response = await fetch(path, { credentials: "include" });
  if (!response.ok) {
    const body = await readBody(response);
    if (response.status === 401) {
      resetCsrfToken();
    }
    const error = typeof body === "object" && body !== null ? (body as ErrorBody) : {};
    throw new ApiError(
      response.status,
      error.code ?? "DOWNLOAD_FAILED",
      error.message ?? "下载失败，请稍后重试",
    );
  }
  const disposition = response.headers.get("content-disposition") ?? "";
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const fallback = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  return {
    blob: await response.blob(),
    filename: encoded ? decodeURIComponent(encoded) : fallback ?? "document",
  };
}

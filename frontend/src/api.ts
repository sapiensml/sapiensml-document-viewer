// Calls to the viewer's own API. The session cookie travels by itself; no token is ever handled here.

export type ViewerKind = 'PDF' | 'IMAGE' | 'TEXT' | 'NONE';

export interface Session {
  username: string;
  displayName: string;
  canView: boolean;
  /** May use the admin page */
  admin: boolean;
  /** An Alfresco connection is set up; until then documents cannot open */
  configured: boolean;
  applicationName: string;
  /** The application whose links open documents, for example FINEOS; empty when not configured */
  sourceApplication: string;
  allowDownload: boolean;
  allowPrint: boolean;
}

export interface DocumentInfo {
  id: string;
  name: string;
  title: string | null;
  mimeType: string;
  size: number;
  modifiedAt: string | null;
  viewer: ViewerKind;
  converted: boolean;
  properties: { label: string; value: string }[];
  downloadAllowed: boolean;
  printAllowed: boolean;
}

export type AuthType = 'CLIENT_CREDENTIALS' | 'BASIC';

/** The Alfresco connection as the server shows it: secrets are never sent, only whether one is stored. */
export interface AlfrescoSettings {
  source: 'ADMIN' | 'ENVIRONMENT' | 'NONE';
  baseUrl: string | null;
  authType: AuthType;
  tokenUri: string | null;
  clientId: string | null;
  clientSecretSet: boolean;
  scope: string | null;
  username: string | null;
  passwordSet: boolean;
  allowedFolderIds: string[];
  updatedBy: string | null;
  updatedAt: string | null;
}

/** What the admin form sends. An empty secret or password keeps the stored one, for the same account only. */
export interface SettingsForm {
  baseUrl: string;
  authType: AuthType;
  tokenUri: string;
  clientId: string;
  clientSecret: string;
  scope: string;
  username: string;
  password: string;
  allowedFolderIds: string[];
}

export interface CheckStep {
  name: string;
  ok: boolean;
  message: string;
}

export interface CheckResult {
  ok: boolean;
  steps: CheckStep[];
}

export interface SaveResult {
  saved: boolean;
  settings: AlfrescoSettings;
  steps: CheckStep[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly correlationId?: string,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

const NODE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isNodeId(value: string): boolean {
  return NODE_ID.test(value);
}

export const previewUrl = (id: string) => `/api/documents/${encodeURIComponent(id)}/preview`;
export const downloadUrl = (id: string) => `/api/documents/${encodeURIComponent(id)}/download`;

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
  if (!response.ok) {
    throw await toError(response);
  }
  return (await response.json()) as T;
}

export async function toError(response: Response): Promise<ApiError> {
  let detail = `Request failed (${response.status})`;
  let correlationId = response.headers.get('X-Correlation-Id') ?? undefined;
  let fieldErrors: Record<string, string> = {};
  try {
    const body = await response.json();
    detail = body.detail ?? detail;
    correlationId = body.correlationId ?? correlationId;
    fieldErrors = body.fieldErrors ?? {};
  } catch {
    // Not a problem document; keep the generic message
  }
  return new ApiError(response.status, detail, correlationId, fieldErrors);
}

export const getSession = () => getJson<Session>('/api/session');

export const getDocument = (id: string) => getJson<DocumentInfo>(`/api/documents/${encodeURIComponent(id)}`);

export const getAlfrescoSettings = () => getJson<AlfrescoSettings>('/api/admin/alfresco');

async function sendJson<T>(method: string, url: string, body: unknown, accept: number[] = []): Promise<T> {
  const response = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-XSRF-TOKEN': csrfToken() },
    body: JSON.stringify(body),
  });
  if (!response.ok && !accept.includes(response.status)) {
    throw await toError(response);
  }
  return (await response.json()) as T;
}

/** Tries the settings on the server without saving them. */
export const testAlfrescoSettings = (form: SettingsForm) =>
  sendJson<CheckResult>('POST', '/api/admin/alfresco/test', form);

/** Saves the settings when the server's connection test passes (422 carries the failed test). */
export const saveAlfrescoSettings = (form: SettingsForm) =>
  sendJson<SaveResult>('PUT', '/api/admin/alfresco', form, [422]);

/**
 * Signs out here and at the identity provider. The server answers with the provider's sign-out address, because
 * a fetch cannot follow a redirect to another site.
 */
export async function signOut(): Promise<void> {
  const response = await fetch('/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
  });
  let target = '/signed-out';
  if (response.ok) {
    try {
      target = ((await response.json()) as { redirectUrl?: string }).redirectUrl ?? target;
    } catch {
      // Keep the local signed-out page
    }
  }
  window.location.assign(target);
}

function csrfToken(): string {
  const cookie = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='));
  return cookie ? decodeURIComponent(cookie.substring('XSRF-TOKEN='.length)) : '';
}

const RELOAD_KEY = 'sapiensml-viewer-reauth';

/**
 * The session has ended (idle timeout, server restart). Reloading the page signs the user in again, usually
 * silently, and returns to the same document. Returns false when that was just tried, so the page shows a
 * button instead of looping.
 */
export function reauthenticate(): boolean {
  let last = 0;
  try {
    last = Number(sessionStorage.getItem(RELOAD_KEY) ?? 0);
    sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
  } catch {
    return false;
  }
  if (Date.now() - last < 30_000) {
    return false;
  }
  window.location.reload();
  return true;
}

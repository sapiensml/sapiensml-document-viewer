import { type FormEvent, type ReactNode, useEffect, useState } from 'react';

import {
  type AlfrescoSettings,
  ApiError,
  type AuthType,
  type CheckStep,
  type SettingsForm,
  getAlfrescoSettings,
  reauthenticate,
  saveAlfrescoSettings,
  testAlfrescoSettings,
} from '../api';
import { formatDate } from '../format';
import { Notice } from './Notice';

// Token endpoint of the Alfresco Identity Service (Keycloak realm "alfresco"), as used by Alfresco PaaS
const IDENTITY_SERVICE_TOKEN_PATH = '/auth/realms/alfresco/protocol/openid-connect/token';

type Load = { kind: 'loading' } | { kind: 'ready'; settings: AlfrescoSettings } | { kind: 'error'; message: string };

type Outcome = { kind: 'tested' | 'saved' | 'not-saved'; steps: CheckStep[] } | { kind: 'error'; message: string };

function toForm(settings: AlfrescoSettings): SettingsForm {
  return {
    baseUrl: settings.baseUrl ?? '',
    authType: settings.authType,
    tokenUri: settings.tokenUri ?? '',
    clientId: settings.clientId ?? '',
    clientSecret: '',
    scope: settings.scope ?? '',
    username: settings.username ?? '',
    password: '',
    allowedFolderIds: settings.allowedFolderIds,
  };
}

/**
 * The Alfresco connection, for administrators: entered once, then kept on the server (secrets encrypted). The
 * server tests it before saving, and stored secrets are never shown.
 */
export function AdminPage() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [form, setForm] = useState<SettingsForm | null>(null);
  const [folders, setFolders] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<'test' | 'save' | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const show = (settings: AlfrescoSettings) => {
    setLoad({ kind: 'ready', settings });
    setForm(toForm(settings));
    setFolders(settings.allowedFolderIds.join('\n'));
  };

  useEffect(() => {
    getAlfrescoSettings()
      .then(show)
      .catch((error: unknown) => {
        const status = error instanceof ApiError ? error.status : undefined;
        if (status === 401 && reauthenticate()) {
          return;
        }
        setLoad({
          kind: 'error',
          message: status === 403 ? "You cannot change the viewer's settings." : 'The settings could not be loaded.',
        });
      });
  }, []);

  useEffect(() => {
    document.title = 'Alfresco connection – Administration';
  }, []);

  if (load.kind === 'loading' || (load.kind === 'ready' && !form)) {
    return <Notice title="Loading the settings…" busy />;
  }
  if (load.kind === 'error') {
    return <Notice title={load.message} tone="error" />;
  }
  const stored = load.settings;
  const current = form as SettingsForm;
  const set = <K extends keyof SettingsForm>(key: K, value: SettingsForm[K]) => {
    setForm({ ...current, [key]: value });
    setErrors((previous) => ({ ...previous, [key]: '' }));
  };
  const clientCredentials = current.authType === 'CLIENT_CREDENTIALS';
  // A stored secret is kept only for the same token endpoint and client (the server enforces this too)
  const secretKept =
    stored.clientSecretSet && stored.authType === 'CLIENT_CREDENTIALS' && current.tokenUri.trim() === (stored.tokenUri ?? '') &&
    current.clientId.trim() === (stored.clientId ?? '');
  const passwordKept =
    stored.passwordSet && stored.authType === 'BASIC' && current.username.trim() === (stored.username ?? '') &&
    current.baseUrl.trim().replace(/\/(alfresco\/?)?$/, '') === (stored.baseUrl ?? '');

  const submitted = (): SettingsForm => ({
    ...current,
    allowedFolderIds: folders
      .split(/[\s,]+/)
      .map((value) => value.trim())
      .filter(Boolean),
  });

  const run = async (action: 'test' | 'save') => {
    setBusy(action);
    setOutcome(null);
    setErrors({});
    try {
      if (action === 'test') {
        const result = await testAlfrescoSettings(submitted());
        setOutcome({ kind: 'tested', steps: result.steps });
      } else {
        const result = await saveAlfrescoSettings(submitted());
        if (result.saved) {
          show(result.settings);
        }
        setOutcome({ kind: result.saved ? 'saved' : 'not-saved', steps: result.steps });
      }
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 400) {
        setErrors(error.fieldErrors);
        setOutcome({ kind: 'error', message: 'Some settings are missing or wrong.' });
      } else if (error instanceof ApiError && error.status === 401 && reauthenticate()) {
        return;
      } else {
        setOutcome({ kind: 'error', message: error instanceof ApiError ? error.message : 'The request failed.' });
      }
    } finally {
      setBusy(null);
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void run('save');
  };

  return (
    <div className="admin-page">
      <div className="admin-heading">
        <h1>Alfresco connection</h1>
        <Source settings={stored} />
      </div>
      <p className="admin-intro">
        The viewer reads every document from Alfresco with this one service account. Enter it once; it is kept on the
        server with the secret encrypted, and used for every user from the moment it is saved.
      </p>

      <form className="admin-form" onSubmit={onSubmit} noValidate>
        <section className="card">
          <h2>Repository</h2>
          <Field label="Repository address" error={errors.baseUrl} hint="Without /alfresco, for example https://alfresco.example.com">
            <input
              type="url"
              value={current.baseUrl}
              onChange={(event) => set('baseUrl', event.target.value)}
              placeholder="https://alfresco.example.com"
              autoComplete="off"
              spellCheck={false}
              required
            />
          </Field>
        </section>

        <section className="card">
          <h2>Sign-in to Alfresco</h2>
          <div className="choices" role="radiogroup" aria-label="Sign-in method">
            <Choice
              name="authType"
              value="CLIENT_CREDENTIALS"
              current={current.authType}
              onChange={(value) => set('authType', value)}
              title="Service account (OAuth2 client credentials)"
              description="Alfresco PaaS, or Alfresco with the Identity Service (Keycloak)"
            />
            <Choice
              name="authType"
              value="BASIC"
              current={current.authType}
              onChange={(value) => set('authType', value)}
              title="User name and password"
              description="An Alfresco repository user (Community or Enterprise)"
            />
          </div>
          {errors.authType && <p className="field-error">{errors.authType}</p>}

          {clientCredentials ? (
            <>
              <Field label="Token endpoint" error={errors.tokenUri}>
                <div className="input-row">
                  <input
                    type="url"
                    value={current.tokenUri}
                    onChange={(event) => set('tokenUri', event.target.value)}
                    placeholder={`https://alfresco.example.com${IDENTITY_SERVICE_TOKEN_PATH}`}
                    autoComplete="off"
                    spellCheck={false}
                  />
                  <button
                    type="button"
                    className="button"
                    disabled={!/^https?:\/\/[^/]+/.test(current.baseUrl.trim())}
                    onClick={() => set('tokenUri', current.baseUrl.trim().replace(/\/(alfresco\/?)?$/, '') + IDENTITY_SERVICE_TOKEN_PATH)}
                  >
                    Use the standard path
                  </button>
                </div>
              </Field>
              <Field label="Client ID" error={errors.clientId}>
                <input
                  value={current.clientId}
                  onChange={(event) => set('clientId', event.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                />
              </Field>
              <Field
                label="Client secret"
                error={errors.clientSecret}
                hint={
                  secretKept
                    ? 'A secret is stored. Leave this empty to keep it.'
                    : stored.clientSecretSet
                      ? 'The token endpoint or client changed: enter the secret again.'
                      : undefined
                }
              >
                <input
                  type="password"
                  value={current.clientSecret}
                  onChange={(event) => set('clientSecret', event.target.value)}
                  placeholder={secretKept ? '•••••••• (stored)' : ''}
                  autoComplete="new-password"
                />
              </Field>
              <Field label="Scope" error={errors.scope} hint="Optional. Usually empty">
                <input value={current.scope} onChange={(event) => set('scope', event.target.value)} autoComplete="off" />
              </Field>
            </>
          ) : (
            <>
              <Field label="User name" error={errors.username}>
                <input value={current.username} onChange={(event) => set('username', event.target.value)} autoComplete="off" />
              </Field>
              <Field
                label="Password"
                error={errors.password}
                hint={passwordKept ? 'A password is stored. Leave this empty to keep it.' : undefined}
              >
                <input
                  type="password"
                  value={current.password}
                  onChange={(event) => set('password', event.target.value)}
                  placeholder={passwordKept ? '•••••••• (stored)' : ''}
                  autoComplete="new-password"
                />
              </Field>
            </>
          )}
        </section>

        <section className="card">
          <h2>Documents that may open</h2>
          <Field
            label="Allowed folders (node IDs, one per line)"
            error={errors.allowedFolderIds}
            hint="Only documents below these folders open. Empty: every document the service account can read (not recommended)."
          >
            <textarea
              rows={3}
              value={folders}
              onChange={(event) => {
                setFolders(event.target.value);
                setErrors((previous) => ({ ...previous, allowedFolderIds: '' }));
              }}
              placeholder="f15099e2-b4f5-402a-895c-1bf1f4bfbfa3"
              spellCheck={false}
            />
          </Field>
        </section>

        <div className="admin-actions">
          <button type="button" className="button" disabled={busy !== null} onClick={() => void run('test')}>
            {busy === 'test' ? 'Testing…' : 'Test connection'}
          </button>
          <button type="submit" className="button primary" disabled={busy !== null}>
            {busy === 'save' ? 'Testing and saving…' : 'Save'}
          </button>
        </div>
        <Result outcome={outcome} />
      </form>
    </div>
  );
}

function Source({ settings }: { settings: AlfrescoSettings }) {
  if (settings.source === 'ADMIN') {
    return (
      <span className="badge badge-ok">
        Saved{settings.updatedBy ? ` by ${settings.updatedBy}` : ''}
        {settings.updatedAt ? `, ${formatDate(settings.updatedAt)}` : ''}
      </span>
    );
  }
  if (settings.source === 'ENVIRONMENT') {
    return <span className="badge">From the server's settings file; saving here replaces it</span>;
  }
  return <span className="badge badge-warn">Not set up: documents cannot open yet</span>;
}

function Result({ outcome }: { outcome: Outcome | null }) {
  if (!outcome) {
    return null;
  }
  if (outcome.kind === 'error') {
    return (
      <p className="result result-error" role="alert">
        {outcome.message}
      </p>
    );
  }
  const passed = outcome.steps.every((step) => step.ok);
  const title =
    outcome.kind === 'saved'
      ? 'Saved. Documents open with this connection from now on.'
      : outcome.kind === 'not-saved'
        ? 'Not saved: the connection test failed.'
        : passed
          ? 'The connection works.'
          : 'The connection test failed.';
  return (
    <div className={`result ${passed ? 'result-ok' : 'result-error'}`} role="status">
      <p className="result-title">{title}</p>
      <ul className="steps">
        {outcome.steps.map((step) => (
          <li key={step.name} className={step.ok ? 'step-ok' : 'step-fail'}>
            <span aria-hidden="true">{step.ok ? '✓' : '✗'}</span>
            <span className="step-name">{step.name}</span>
            <span className="step-message">{step.message}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <label className={`field${error ? ' field-invalid' : ''}`}>
      <span className="field-label">{label}</span>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

function Choice({
  name,
  value,
  current,
  onChange,
  title,
  description,
}: {
  name: string;
  value: AuthType;
  current: AuthType;
  onChange: (value: AuthType) => void;
  title: string;
  description: string;
}) {
  return (
    <label className={`choice${current === value ? ' choice-selected' : ''}`}>
      <input type="radio" name={name} value={value} checked={current === value} onChange={() => onChange(value)} />
      <span>
        <span className="choice-title">{title}</span>
        <span className="choice-description">{description}</span>
      </span>
    </label>
  );
}

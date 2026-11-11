import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminPage } from './AdminPage';

const STORED = {
  source: 'ADMIN',
  baseUrl: 'https://alfresco.example.com',
  authType: 'CLIENT_CREDENTIALS',
  tokenUri: 'https://alfresco.example.com/auth/realms/alfresco/protocol/openid-connect/token',
  clientId: 'svc-viewer',
  clientSecretSet: true,
  scope: null,
  username: null,
  passwordSet: false,
  allowedFolderIds: ['f15099e2-b4f5-402a-895c-1bf1f4bfbfa3'],
  updatedBy: 'ada',
  updatedAt: '2026-11-11T20:00:00Z',
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function server(handler: (url: string, init?: RequestInit) => Response) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => handler(url, init));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('AdminPage', () => {
  it('shows the stored connection without its secret', async () => {
    server(() => json(STORED));
    render(<AdminPage />);
    expect(await screen.findByDisplayValue('https://alfresco.example.com')).toBeTruthy();
    expect(screen.getByDisplayValue('svc-viewer')).toBeTruthy();
    expect(screen.getByText('A secret is stored. Leave this empty to keep it.')).toBeTruthy();
    expect(screen.getByText(/Saved by ada/)).toBeTruthy();
    expect((screen.getByPlaceholderText('•••••••• (stored)') as HTMLInputElement).value).toBe('');
  });

  it('asks for the secret again when the client changes', async () => {
    server(() => json(STORED));
    render(<AdminPage />);
    const clientId = await screen.findByDisplayValue('svc-viewer');
    fireEvent.change(clientId, { target: { value: 'other-client' } });
    expect(screen.getByText('The token endpoint or client changed: enter the secret again.')).toBeTruthy();
  });

  it('saves with the CSRF token and shows the test steps', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123';
    const fetchMock = server((_url, init) =>
      init?.method === 'PUT'
        ? json({
            saved: true,
            settings: { ...STORED, allowedFolderIds: [] },
            steps: [
              { name: 'Service-account token', ok: true, message: 'Issued by alfresco.example.com' },
              { name: 'Repository', ok: true, message: 'Connected to alfresco.example.com as svc' },
            ],
          })
        : json(STORED),
    );
    render(<AdminPage />);
    const folders = await screen.findByDisplayValue('f15099e2-b4f5-402a-895c-1bf1f4bfbfa3');
    fireEvent.change(folders, { target: { value: ' f15099e2-b4f5-402a-895c-1bf1f4bfbfa3 \n\n 9497a455-efca-4886-b1ac-18cf550fcd99' } });
    fireEvent.click(screen.getByText('Save'));
    expect(await screen.findByText('Saved. Documents open with this connection from now on.')).toBeTruthy();
    expect(screen.getByText('Connected to alfresco.example.com as svc')).toBeTruthy();

    const [url, init] = fetchMock.mock.calls.find(([, options]) => options?.method === 'PUT')!;
    expect(url).toBe('/api/admin/alfresco');
    expect((init!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('csrf-123');
    const body = JSON.parse(init!.body as string);
    expect(body.clientSecret).toBe('');
    expect(body.allowedFolderIds).toEqual([
      'f15099e2-b4f5-402a-895c-1bf1f4bfbfa3',
      '9497a455-efca-4886-b1ac-18cf550fcd99',
    ]);
  });

  it('shows the server field errors and a failed test', async () => {
    server((url, init) => {
      if (url.endsWith('/test')) {
        return json({ ok: false, steps: [{ name: 'Service-account token', ok: false, message: 'Refused.' }] });
      }
      if (init?.method === 'PUT') {
        return json({ detail: 'Some settings are missing or wrong.', fieldErrors: { baseUrl: 'Enter a full https:// address.' } }, 400);
      }
      return json({ ...STORED, source: 'NONE', baseUrl: null, clientSecretSet: false });
    });
    render(<AdminPage />);
    expect(await screen.findByText('Not set up: documents cannot open yet')).toBeTruthy();
    fireEvent.click(screen.getByText('Test connection'));
    expect(await screen.findByText('The connection test failed.')).toBeTruthy();
    expect(screen.getByText('Refused.')).toBeTruthy();
    fireEvent.click(screen.getByText('Save'));
    expect(await screen.findByText('Enter a full https:// address.')).toBeTruthy();
  });

  it('switches to a user name and password', async () => {
    server(() => json(STORED));
    render(<AdminPage />);
    fireEvent.click(await screen.findByText('User name and password'));
    expect(screen.getByText('User name')).toBeTruthy();
    expect(screen.queryByText('Client ID')).toBeNull();
  });
});

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';

const NODE = '42099fec-ce6e-4ab6-a537-0369b6b6f7d2';

const session = (overrides = {}) => ({
  username: 'viewer',
  displayName: 'Vera Viewer',
  canView: true,
  admin: false,
  configured: true,
  applicationName: 'SapiensML Document Viewer',
  sourceApplication: '',
  allowDownload: false,
  allowPrint: true,
  ...overrides,
});

const documentInfo = (overrides = {}) => ({
  id: NODE,
  name: 'notes.txt',
  title: 'Case notes',
  mimeType: 'text/plain',
  size: 12,
  modifiedAt: '2026-11-10T21:25:54.758Z',
  viewer: 'TEXT',
  converted: false,
  properties: [{ label: 'Case', value: '100234' }],
  downloadAllowed: false,
  printAllowed: true,
  ...overrides,
});

function routes(table: Record<string, () => Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const handler = table[url];
      return handler ? handler() : new Response('', { status: 404 });
    }),
  );
}

const json = (body: unknown, status = 200) => () =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('App', () => {
  it('shows the signed-out page without calling the API', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    render(<App location={{ pathname: '/signed-out', search: '' }} />);
    expect(screen.getByText('You have signed out.')).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports a failed sign-in', () => {
    render(<App location={{ pathname: '/signed-out', search: '?error=sign-in' }} />);
    expect(screen.getByText('Sign-in did not complete.')).toBeTruthy();
  });

  it('tells a user without the viewer role why nothing opens', async () => {
    routes({ '/api/session': json(session({ canView: false })) });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    expect(await screen.findByText('You cannot view documents.')).toBeTruthy();
    expect(screen.getByText(/signed in as viewer/)).toBeTruthy();
  });

  it('refuses a link that is not a node ID, naming the source application', async () => {
    routes({ '/api/session': json(session({ sourceApplication: 'FINEOS' })) });
    render(<App location={{ pathname: '/documents/not-a-node', search: '' }} />);
    expect(await screen.findByText('This link does not point to a document.')).toBeTruthy();
    expect(screen.getByText('Open the document again from FINEOS.')).toBeTruthy();
  });

  it('opens a text document with its details', async () => {
    routes({
      '/api/session': json(session()),
      [`/api/documents/${NODE}`]: json(documentInfo()),
      [`/api/documents/${NODE}/preview`]: () => new Response('<b>not markup</b>', { status: 200 }),
    });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    expect(await screen.findByText('<b>not markup</b>')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Case notes' })).toBeTruthy();
    expect(screen.getByText('Vera Viewer')).toBeTruthy();
    expect(screen.queryByText('Download')).toBeNull();
  });

  it('shows the reference of a failed lookup', async () => {
    routes({
      '/api/session': json(session()),
      [`/api/documents/${NODE}`]: json({ detail: 'x', correlationId: 'corr-12345678' }, 404),
    });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    expect(await screen.findByText('The document does not exist, or you cannot open it.')).toBeTruthy();
    expect(screen.getByText('Reference: corr-12345678')).toBeTruthy();
  });

  it('asks for the setup before documents can open', async () => {
    routes({ '/api/session': json(session({ configured: false })) });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    expect(await screen.findByText('The viewer has not been set up yet.')).toBeTruthy();
    expect(screen.getByText(/An administrator must enter the Alfresco connection/)).toBeTruthy();
  });

  it('gives administrators a way to the setup, and nobody else', async () => {
    routes({ '/api/session': json(session({ configured: false, admin: true })) });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    const setup = (await screen.findByText('Set up the Alfresco connection')) as HTMLAnchorElement;
    expect(setup.getAttribute('href')).toBe('/admin');
    expect(screen.getByText('Administration')).toBeTruthy();
    cleanup();
    routes({ '/api/session': json(session()) });
    render(<App location={{ pathname: '/admin', search: '' }} />);
    expect(await screen.findByText("You cannot change the viewer's settings.")).toBeTruthy();
    expect(screen.queryByText('Administration')).toBeNull();
  });

  it('offers download and explains files without a preview', async () => {
    routes({
      '/api/session': json(session({ allowDownload: true })),
      [`/api/documents/${NODE}`]: json(
        documentInfo({ viewer: 'NONE', mimeType: 'application/zip', name: 'bundle.zip', downloadAllowed: true }),
      ),
    });
    render(<App location={{ pathname: `/documents/${NODE}`, search: '' }} />);
    expect(await screen.findByText('This type of file cannot be shown in the browser.')).toBeTruthy();
    const link = screen.getByText('Download') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe(`/api/documents/${NODE}/download`);
  });
});

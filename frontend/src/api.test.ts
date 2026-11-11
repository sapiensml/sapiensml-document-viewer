import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, getDocument, isNodeId, signOut, toError } from './api';
import { describeFailure, formatSize } from './format';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('isNodeId', () => {
  it('accepts Alfresco node IDs only', () => {
    expect(isNodeId('42099fec-ce6e-4ab6-a537-0369b6b6f7d2')).toBe(true);
    expect(isNodeId('47637A50-5086-4084-A37A-505086E08442')).toBe(true);
    expect(isNodeId('-root-')).toBe(false);
    expect(isNodeId('../people/-me-')).toBe(false);
    expect(isNodeId('42099fec-ce6e-4ab6-a537-0369b6b6f7d2/children')).toBe(false);
  });
});

describe('toError', () => {
  it('reads the problem detail and correlation ID', async () => {
    const response = new Response(JSON.stringify({ detail: 'Not here', correlationId: 'abc-123-def' }), {
      status: 404,
      headers: { 'Content-Type': 'application/problem+json' },
    });
    const error = await toError(response);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.message).toBe('Not here');
    expect(error.correlationId).toBe('abc-123-def');
  });

  it('falls back to the header and a generic message', async () => {
    const error = await toError(new Response('oops', { status: 502, headers: { 'X-Correlation-Id': 'from-header' } }));
    expect(error.message).toBe('Request failed (502)');
    expect(error.correlationId).toBe('from-header');
  });
});

describe('getDocument', () => {
  it('encodes the ID and throws ApiError on failure', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 403 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(getDocument('a/b')).rejects.toMatchObject({ status: 403 });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/documents/a%2Fb');
  });
});

describe('signOut', () => {
  it('posts the CSRF token and follows the identity provider address', async () => {
    document.cookie = 'XSRF-TOKEN=token%2Fvalue';
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ redirectUrl: 'https://idp.test/logout?x=1' }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location);

    await signOut();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/logout');
    expect(init.method).toBe('POST');
    expect(init.headers['X-XSRF-TOKEN']).toBe('token/value');
    expect(assign).toHaveBeenCalledWith('https://idp.test/logout?x=1');
  });
});

describe('format', () => {
  it('formats sizes', () => {
    expect(formatSize(512)).toBe('512 B');
    expect(formatSize(46267)).toBe('45 KB');
    expect(formatSize(10205606)).toBe('9.7 MB');
  });

  it('describes failures without detail', () => {
    expect(describeFailure(404)).toMatch(/does not exist/);
    expect(describeFailure(undefined)).toBe('The document could not be displayed.');
  });
});

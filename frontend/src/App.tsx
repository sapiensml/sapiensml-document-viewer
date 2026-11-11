import { type ReactNode, useEffect, useState } from 'react';

import { ApiError, type Session, getSession, isNodeId, reauthenticate, signOut } from './api';
import { AdminPage } from './components/AdminPage';
import { DocumentPage } from './components/DocumentPage';
import { Notice } from './components/Notice';

const DEFAULT_NAME = 'SapiensML Document Viewer';

type SessionState =
  | { kind: 'loading' }
  | { kind: 'ready'; session: Session }
  | { kind: 'error'; status?: number; reference?: string };

/**
 * The pages: /documents/<node id> (the links in business applications), /admin (the Alfresco connection), / and /signed-out. The
 * server serves all but /signed-out to signed-in users only, so a missing session here means it has just expired.
 */
export function App({ location = window.location }: { location?: Pick<Location, 'pathname' | 'search'> }) {
  if (location.pathname === '/signed-out') {
    return <SignedOut failed={new URLSearchParams(location.search).get('error') === 'sign-in'} />;
  }
  return <SignedIn location={location} />;
}

function SignedIn({ location }: { location: Pick<Location, 'pathname' | 'search'> }) {
  const [state, setState] = useState<SessionState>({ kind: 'loading' });

  useEffect(() => {
    getSession()
      .then((session) => setState({ kind: 'ready', session }))
      .catch((error: unknown) => {
        const status = error instanceof ApiError ? error.status : undefined;
        if (status === 401 && reauthenticate()) {
          return;
        }
        setState({ kind: 'error', status, reference: error instanceof ApiError ? error.correlationId : undefined });
      });
  }, []);

  if (state.kind === 'loading') {
    return (
      <Shell>
        <Notice title="Signing in…" busy />
      </Shell>
    );
  }
  if (state.kind === 'error') {
    return (
      <Shell>
        {state.status === 401 ? (
          <Notice title="Your session has ended." tone="error">
            <a className="button primary" href={location.pathname}>
              Sign in again
            </a>
          </Notice>
        ) : (
          <Notice title="The viewer is not available right now." tone="error" reference={state.reference}>
            Try again in a few minutes.
          </Notice>
        )}
      </Shell>
    );
  }

  const { session } = state;
  const source = session.sourceApplication;
  const match = /^\/documents\/([^/]+)\/?$/.exec(location.pathname);
  let body;
  if (location.pathname === '/admin') {
    body = session.admin ? (
      <AdminPage />
    ) : (
      <Notice title="You cannot change the viewer's settings." tone="error">
        The admin page is for viewer administrators only.
      </Notice>
    );
  } else if (!session.canView) {
    body = (
      <Notice title="You cannot view documents." tone="error">
        You are signed in as {session.username}, but your account does not have access to the document viewer. Ask
        your administrator for the document viewer role.
      </Notice>
    );
  } else if (!session.configured) {
    body = (
      <Notice title="The viewer has not been set up yet." tone="error">
        {session.admin ? (
          <a className="button primary" href="/admin">
            Set up the Alfresco connection
          </a>
        ) : (
          'An administrator must enter the Alfresco connection before documents can open.'
        )}
      </Notice>
    );
  } else if (match) {
    const id = decodeURIComponent(match[1]);
    body = isNodeId(id) ? (
      <DocumentPage id={id.toLowerCase()} applicationName={session.applicationName} />
    ) : (
      <Notice title="This link does not point to a document." tone="error">
        Open the document again from {source || 'the application that linked to it'}.
      </Notice>
    );
  } else {
    const invalid = new URLSearchParams(location.search).get('link') === 'invalid';
    body = (
      <Notice title={invalid ? 'This link does not point to a document.' : 'No document selected'}>
        Open a document from {source || 'your business application'} to view it here.
      </Notice>
    );
  }

  return (
    <Shell session={session}>
      {body}
    </Shell>
  );
}

function SignedOut({ failed }: { failed: boolean }) {
  useEffect(() => {
    document.title = DEFAULT_NAME;
  }, []);
  return (
    <Shell>
      <Notice title={failed ? 'Sign-in did not complete.' : 'You have signed out.'} tone={failed ? 'error' : 'info'}>
        <p>{failed ? 'Try again, or contact support if it keeps happening.' : 'You can close this window.'}</p>
        <a className="button primary" href="/">
          Sign in again
        </a>
      </Notice>
    </Shell>
  );
}

function Shell({ session, children }: { session?: Session; children: ReactNode }) {
  const name = session?.applicationName ?? DEFAULT_NAME;
  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <img src="/favicon.svg" alt="" width="24" height="24" />
          <span>{name}</span>
        </div>
        {session && (
          <div className="user">
            {session.admin && (
              <a className="header-link" href="/admin">
                Administration
              </a>
            )}
            <span className="user-name" title={session.username}>
              {session.displayName}
            </span>
            <button type="button" className="button" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        )}
      </header>
      <main className="app-main">{children}</main>
    </div>
  );
}

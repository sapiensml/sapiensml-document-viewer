import { useEffect, useState } from 'react';

import { ApiError, type DocumentInfo, downloadUrl, getDocument, previewUrl, reauthenticate } from '../api';
import { describeFailure } from '../format';
import { DetailsPanel } from './DetailsPanel';
import { ImageViewer } from './ImageViewer';
import { Notice } from './Notice';
import { PdfViewer } from './PdfViewer';
import { TextViewer } from './TextViewer';
import { Toolbar, ToolbarButton, ToolbarGroup } from './Toolbar';

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; document: DocumentInfo }
  | { kind: 'error'; message: string; reference?: string; signIn: boolean };

export function DocumentPage({ id, applicationName }: { id: string; applicationName: string }) {
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [details, setDetails] = useState(() => window.innerWidth >= 1100);

  useEffect(() => {
    let cancelled = false;
    setState({ kind: 'loading' });
    getDocument(id)
      .then((document) => {
        if (!cancelled) {
          setState({ kind: 'ready', document });
        }
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }
        const status = error instanceof ApiError ? error.status : undefined;
        if (status === 401 && reauthenticate()) {
          return;
        }
        setState({
          kind: 'error',
          message: status === 401 ? 'Your session has ended.' : describeFailure(status),
          reference: error instanceof ApiError ? error.correlationId : undefined,
          signIn: status === 401,
        });
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const title = state.kind === 'ready' ? state.document.title || state.document.name : 'Document';
  useEffect(() => {
    document.title = `${title} – ${applicationName}`;
  }, [title, applicationName]);

  if (state.kind === 'loading') {
    return <Notice title="Opening the document…" busy />;
  }
  if (state.kind === 'error') {
    return (
      <Notice title={state.message} tone="error" reference={state.reference}>
        {state.signIn && (
          <button type="button" className="button primary" onClick={() => window.location.reload()}>
            Sign in again
          </button>
        )}
      </Notice>
    );
  }

  const info = state.document;
  const actions = (
    <ToolbarGroup label="Document">
      {info.downloadAllowed && (
        <a className="tool tool-text" href={downloadUrl(info.id)} download={info.name} title="Download the original file">
          Download
        </a>
      )}
      <ToolbarButton label="Show details" pressed={details} onClick={() => setDetails(!details)} text>
        Details
      </ToolbarButton>
    </ToolbarGroup>
  );
  const url = previewUrl(info.id);

  let viewer;
  switch (info.viewer) {
    case 'PDF':
      viewer = <PdfViewer url={url} title={title} allowPrint={info.printAllowed} actions={actions} />;
      break;
    case 'IMAGE':
      viewer = <ImageViewer url={url} title={title} allowPrint={info.printAllowed} actions={actions} />;
      break;
    case 'TEXT':
      viewer = <TextViewer url={url} title={title} allowPrint={info.printAllowed} actions={actions} />;
      break;
    default:
      viewer = (
        <div className="viewer">
          <Toolbar title={title}>{actions}</Toolbar>
          <Notice title="This type of file cannot be shown in the browser.">
            {info.downloadAllowed ? 'Download it to open it on your computer.' : `File type: ${info.mimeType}`}
          </Notice>
        </div>
      );
  }

  return (
    <div className="document-layout">
      {viewer}
      {details && <DetailsPanel document={info} onClose={() => setDetails(false)} />}
    </div>
  );
}

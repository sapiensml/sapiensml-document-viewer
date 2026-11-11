import { type ReactNode, useEffect, useState } from 'react';

import { reauthenticate, toError } from '../api';
import { describeFailure } from '../format';
import { Notice } from './Notice';
import { Toolbar, ToolbarButton, ToolbarGroup } from './Toolbar';

interface Props {
  url: string;
  title: string;
  allowPrint: boolean;
  actions?: ReactNode;
}

type State = { kind: 'loading' } | { kind: 'ready'; text: string } | { kind: 'error'; message: string };

export function TextViewer({ url, title, allowPrint, actions }: Props) {
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [wrap, setWrap] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    fetch(url, { credentials: 'same-origin', signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw await toError(response);
        }
        // React escapes the text: markup in a file is shown, never run
        setState({ kind: 'ready', text: await response.text() });
      })
      .catch((error: { status?: number; name?: string }) => {
        if (error.name === 'AbortError') {
          return;
        }
        if (error.status === 401 && reauthenticate()) {
          return;
        }
        setState({ kind: 'error', message: describeFailure(error.status) });
      });
    return () => controller.abort();
  }, [url]);

  return (
    <div className="viewer">
      <Toolbar title={title}>
        {state.kind === 'ready' && (
          <>
            <ToolbarGroup label="Layout">
              <ToolbarButton label="Wrap long lines" pressed={wrap} onClick={() => setWrap(!wrap)} text>
                Wrap
              </ToolbarButton>
            </ToolbarGroup>
            {allowPrint && (
              <ToolbarGroup label="Print">
                <ToolbarButton label="Print" onClick={() => window.print()} text>
                  Print
                </ToolbarButton>
              </ToolbarGroup>
            )}
          </>
        )}
        {actions}
      </Toolbar>
      {state.kind === 'loading' && <Notice title="Opening the document…" busy />}
      {state.kind === 'error' && <Notice title={state.message} tone="error" />}
      {state.kind === 'ready' && (
        <div className={`text-stage${allowPrint ? ' print-text' : ' no-print'}`}>
          <pre className={wrap ? 'wrap' : undefined}>{state.text}</pre>
        </div>
      )}
    </div>
  );
}

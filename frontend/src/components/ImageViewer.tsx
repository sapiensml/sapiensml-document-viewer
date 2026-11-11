import { type ReactNode, useState } from 'react';

import { Notice } from './Notice';
import { Toolbar, ToolbarButton, ToolbarGroup } from './Toolbar';

interface Props {
  url: string;
  title: string;
  allowPrint: boolean;
  actions?: ReactNode;
}

const STEP = 1.25;

export function ImageViewer({ url, title, allowPrint, actions }: Props) {
  // null: fit the window; otherwise a factor of the image's own size
  const [zoom, setZoom] = useState<number | null>(null);
  const [rotation, setRotation] = useState(0);
  const [failed, setFailed] = useState(false);
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const current = zoom ?? 1;

  return (
    <div className="viewer">
      <Toolbar title={title}>
        {!failed && (
          <>
            <ToolbarGroup label="Zoom">
              <ToolbarButton label="Zoom out" onClick={() => setZoom(Math.max(0.1, current / STEP))}>
                −
              </ToolbarButton>
              <span className="zoom-value">{zoom === null ? 'Fit' : `${Math.round(current * 100)}%`}</span>
              <ToolbarButton label="Zoom in" onClick={() => setZoom(Math.min(8, current * STEP))}>
                +
              </ToolbarButton>
              <ToolbarButton label="Fit to window" pressed={zoom === null} onClick={() => setZoom(null)} text>
                Fit
              </ToolbarButton>
              <ToolbarButton label="Rotate clockwise" onClick={() => setRotation((value) => (value + 90) % 360)}>
                ↻
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
      {failed ? (
        <Notice title="The image could not be displayed." tone="error" />
      ) : (
        <div className={`image-stage${zoom === null ? ' fit' : ''}${allowPrint ? ' print-image' : ' no-print'}`}>
          <img
            src={url}
            alt={title}
            onError={() => setFailed(true)}
            onLoad={(event) =>
              setNatural({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })
            }
            style={{
              transform: `rotate(${rotation}deg)`,
              width: zoom !== null && natural ? `${natural.width * zoom}px` : undefined,
            }}
          />
        </div>
      )}
    </div>
  );
}

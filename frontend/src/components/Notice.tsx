import type { ReactNode } from 'react';

interface Props {
  title: string;
  tone?: 'info' | 'error';
  busy?: boolean;
  /** Shown so users can quote it to support; it finds the request in the server logs */
  reference?: string;
  children?: ReactNode;
}

export function Notice({ title, tone = 'info', busy, reference, children }: Props) {
  return (
    <div className={`notice notice-${tone}`} role={tone === 'error' ? 'alert' : 'status'} aria-busy={busy}>
      {busy && <div className="spinner" aria-hidden="true" />}
      <h2>{title}</h2>
      {children && <div className="notice-body">{children}</div>}
      {reference && <p className="notice-reference">Reference: {reference}</p>}
    </div>
  );
}

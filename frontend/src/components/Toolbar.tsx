import type { ReactNode } from 'react';

export function Toolbar({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="toolbar" role="toolbar" aria-label="Document">
      <h1 className="toolbar-title" title={title}>
        {title}
      </h1>
      <div className="toolbar-actions">{children}</div>
    </div>
  );
}

export function ToolbarGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="toolbar-group" role="group" aria-label={label}>
      {children}
    </div>
  );
}

interface ButtonProps {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  pressed?: boolean;
  /** A word rather than a symbol: wider button */
  text?: boolean;
  children: ReactNode;
}

export function ToolbarButton({ label, onClick, disabled, pressed, text, children }: ButtonProps) {
  return (
    <button
      type="button"
      className={`tool${text ? ' tool-text' : ''}`}
      aria-label={label}
      aria-pressed={pressed}
      title={label}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

import type { DocumentInfo } from '../api';
import { formatDate, formatSize } from '../format';

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="details-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

export function DetailsPanel({ document, onClose }: { document: DocumentInfo; onClose: () => void }) {
  return (
    <aside className="details" aria-label="Document details">
      <div className="details-header">
        <h2>Details</h2>
        <button type="button" className="tool" aria-label="Close details" title="Close details" onClick={onClose}>
          ×
        </button>
      </div>
      <dl>
        <Row label="File name" value={document.name} />
        {document.properties.map((property) => (
          <Row key={property.label} label={property.label} value={property.value} />
        ))}
        <Row label="Size" value={formatSize(document.size)} />
        {document.modifiedAt && <Row label="Last changed" value={formatDate(document.modifiedAt)} />}
        {document.converted && <Row label="Shown as" value="PDF, converted from the original file" />}
      </dl>
    </aside>
  );
}

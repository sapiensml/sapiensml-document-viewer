export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 10 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

export function formatDate(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** A message for a failed request, by status. */
export function describeFailure(status: number | undefined): string {
  switch (status) {
    case 400:
      return 'This link does not point to a document.';
    case 403:
      return 'You are not allowed to view this document.';
    case 404:
      return 'The document does not exist, or you cannot open it.';
    case 415:
      return 'This type of file cannot be shown in the browser.';
    case 503:
      return 'The document store is not available right now. Try again in a few minutes.';
    default:
      return 'The document could not be displayed.';
  }
}

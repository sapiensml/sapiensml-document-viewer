// Copies PDF.js into public/pdfjs/ so it is served from the viewer with stable names and loaded only when a PDF
// is opened. The legacy build carries the polyfills that managed enterprise browsers may still need.
import { cpSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const pdfjs = join(root, 'node_modules', 'pdfjs-dist');
const out = join(root, 'public', 'pdfjs');

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
cpSync(join(pdfjs, 'legacy', 'build', 'pdf.min.mjs'), join(out, 'pdf.min.mjs'));
cpSync(join(pdfjs, 'legacy', 'build', 'pdf.worker.min.mjs'), join(out, 'pdf.worker.min.mjs'));
for (const dir of ['cmaps', 'standard_fonts', 'iccs']) {
  cpSync(join(pdfjs, dir), join(out, dir), { recursive: true });
}
// Image decoders only; the JavaScript engine for PDF scripting is never shipped
mkdirSync(join(out, 'wasm'));
for (const file of readdirSync(join(pdfjs, 'wasm'))) {
  if (!file.startsWith('quickjs')) {
    cpSync(join(pdfjs, 'wasm', file), join(out, 'wasm', file));
  }
}
cpSync(join(pdfjs, 'LICENSE'), join(out, 'LICENSE'));
console.log('Copied PDF.js to public/pdfjs/');

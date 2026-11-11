import type { PDFDocumentProxy } from 'pdfjs-dist';

import type { PdfJsModule } from './pdfjs';

/** About 150 dpi on paper: sharp text without huge images */
const PRINT_SCALE = 150 / 72;

/**
 * Prints the document as the viewer shows it: every page is rendered to an image in a print-only container,
 * then the browser's print dialog opens. Nothing leaves the browser.
 */
export async function printPdf(
  pdfjs: PdfJsModule,
  pdf: PDFDocumentProxy,
  rotation: number,
  onProgress: (done: number, total: number) => void,
): Promise<void> {
  document.querySelectorAll('.print-root').forEach((old) => old.remove());
  const root = document.createElement('div');
  root.className = 'print-root';
  const urls: string[] = [];
  const cleanUp = () => {
    root.remove();
    urls.forEach((url) => URL.revokeObjectURL(url));
  };
  try {
    for (let number = 1; number <= pdf.numPages; number += 1) {
      const page = await pdf.getPage(number);
      const viewport = page.getViewport({ scale: PRINT_SCALE, rotation: (page.rotate + rotation) % 360 });
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      await page.render({ canvas, viewport, intent: 'print', annotationMode: pdfjs.AnnotationMode.ENABLE }).promise;
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob((result) => (result ? resolve(result) : reject(new Error('Page image failed'))), 'image/jpeg', 0.92),
      );
      canvas.width = 0;
      canvas.height = 0;
      page.cleanup();
      const url = URL.createObjectURL(blob);
      urls.push(url);
      const image = document.createElement('img');
      image.src = url;
      image.alt = '';
      root.append(image);
      onProgress(number, pdf.numPages);
    }
    document.body.append(root);
    await Promise.all(Array.from(root.querySelectorAll('img')).map((image) => image.decode().catch(() => undefined)));
  } catch (error) {
    cleanUp();
    throw error;
  }
  await new Promise<void>((resolve) => {
    // Some browsers return from print() before the dialog closes; afterprint is the reliable signal
    const fallback = window.setTimeout(resolve, 120_000);
    window.addEventListener(
      'afterprint',
      () => {
        window.clearTimeout(fallback);
        resolve();
      },
      { once: true },
    );
    window.print();
  });
  cleanUp();
}

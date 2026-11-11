// PDF.js is served from /pdfjs/ (copied there at build time) and loaded only when a PDF is opened.
import type * as PdfJs from 'pdfjs-dist';

export type PdfJsModule = typeof PdfJs;

const ASSETS = `${import.meta.env.BASE_URL}pdfjs/`;

let loading: Promise<PdfJsModule> | null = null;

export function loadPdfJs(): Promise<PdfJsModule> {
  if (!loading) {
    loading = import(/* @vite-ignore */ `${ASSETS}pdf.min.mjs`).then((module: PdfJsModule) => {
      module.GlobalWorkerOptions.workerSrc = `${ASSETS}pdf.worker.min.mjs`;
      return module;
    });
    loading.catch(() => {
      loading = null;
    });
  }
  return loading;
}

/** Options for every document: byte ranges only, no scripting, no eval, assets from the viewer itself. */
export function documentOptions(url: string) {
  return {
    url: new URL(url, document.baseURI).href,
    withCredentials: true,
    rangeChunkSize: 262144,
    // Fetch only the ranges needed for the pages on screen, not the whole file in the background
    disableAutoFetch: true,
    disableStream: true,
    isEvalSupported: false,
    enableXfa: false,
    cMapUrl: `${ASSETS}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${ASSETS}standard_fonts/`,
    wasmUrl: `${ASSETS}wasm/`,
    iccUrl: `${ASSETS}iccs/`,
  };
}

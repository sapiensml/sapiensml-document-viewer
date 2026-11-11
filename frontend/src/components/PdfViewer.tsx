import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask, TextLayer } from 'pdfjs-dist';
import {
  type CSSProperties,
  type ReactNode,
  type RefObject,
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';

import { reauthenticate } from '../api';
import { describeFailure } from '../format';
import { printPdf } from '../pdf/print';
import { type PdfJsModule, documentOptions, loadPdfJs } from '../pdf/pdfjs';
import { Notice } from './Notice';
import { Toolbar, ToolbarButton, ToolbarGroup } from './Toolbar';

const MIN_SCALE = 0.25;
const MAX_SCALE = 4;
const ZOOM_STEP = 1.2;
/** A rendition being created: ask again every 3 s for up to two minutes */
const PREPARING_RETRY_MS = 3000;
const PREPARING_ATTEMPTS = 40;

interface Size {
  width: number;
  height: number;
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'preparing' }
  | { kind: 'ready'; pdfjs: PdfJsModule; pdf: PDFDocumentProxy; base: Size }
  | { kind: 'error'; message: string; signIn: boolean };

interface Props {
  url: string;
  title: string;
  allowPrint: boolean;
  /** Extra toolbar content from the page (download, details) */
  actions?: ReactNode;
}

type Observe = (element: Element, onChange: (visible: boolean) => void) => () => void;

export function PdfViewer({ url, title, allowPrint, actions }: Props) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const [scale, setScale] = useState<number | null>(null);
  const [rotation, setRotation] = useState(0);
  const [page, setPage] = useState(1);
  const [pageInput, setPageInput] = useState('1');
  const [printing, setPrinting] = useState<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const scrollRatio = useRef<number | null>(null);
  const observe = useVisibility(scroller);

  useEffect(() => {
    let cancelled = false;
    let task: PDFDocumentLoadingTask | null = null;
    let timer: number | undefined;
    const open = async (attempt: number) => {
      try {
        const pdfjs = await loadPdfJs();
        task = pdfjs.getDocument(documentOptions(url));
        const pdf = await task.promise;
        const first = await pdf.getPage(1);
        const viewport = first.getViewport({ scale: 1 });
        if (!cancelled) {
          setState({ kind: 'ready', pdfjs, pdf, base: { width: viewport.width, height: viewport.height } });
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        const status = (error as { status?: number }).status;
        const name = (error as { name?: string }).name;
        if (status === 202 && attempt < PREPARING_ATTEMPTS) {
          setState({ kind: 'preparing' });
          timer = window.setTimeout(() => open(attempt + 1), PREPARING_RETRY_MS);
          return;
        }
        if (status === 401) {
          if (reauthenticate()) {
            return;
          }
          setState({ kind: 'error', message: 'Your session has ended.', signIn: true });
          return;
        }
        const message =
          status === 202
            ? 'The document is taking longer than usual to prepare. Try again in a few minutes.'
            : name === 'PasswordException'
              ? 'This PDF is password protected and cannot be shown.'
              : name === 'InvalidPDFException'
                ? 'The file is damaged or is not a PDF.'
                : describeFailure(status);
        setState({ kind: 'error', message, signIn: false });
      }
    };
    setState({ kind: 'loading' });
    setScale(null);
    open(1);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      void task?.destroy();
    };
  }, [url]);

  const ready = state.kind === 'ready' ? state : null;
  const numPages = ready?.pdf.numPages ?? 0;

  const fitWidth = useCallback(() => {
    const element = scroller.current;
    if (!element || !ready) {
      return;
    }
    const pageWidth = rotation % 180 === 0 ? ready.base.width : ready.base.height;
    zoomTo(Math.min(2, (element.clientWidth - 48) / pageWidth));
  }, [ready, rotation]);

  const zoomTo = (next: number) => {
    const element = scroller.current;
    if (element && element.scrollHeight > 0) {
      scrollRatio.current = element.scrollTop / element.scrollHeight;
    }
    setScale(Math.max(MIN_SCALE, Math.min(MAX_SCALE, next)));
  };

  useEffect(() => {
    if (ready && scale === null) {
      fitWidth();
    }
  }, [ready, scale, fitWidth]);

  // Keep the same place in the document when the zoom changes
  useLayoutEffect(() => {
    const element = scroller.current;
    if (element && scrollRatio.current !== null) {
      element.scrollTop = scrollRatio.current * element.scrollHeight;
      scrollRatio.current = null;
    }
  }, [scale, rotation]);

  const frame = useRef(0);
  const onScroll = () => {
    if (frame.current) {
      return;
    }
    frame.current = requestAnimationFrame(() => {
      frame.current = 0;
      const element = scroller.current;
      if (!element) {
        return;
      }
      const line = element.scrollTop + element.clientHeight * 0.3;
      let current = 1;
      for (const child of element.querySelectorAll<HTMLElement>('.pdf-page')) {
        if (child.offsetTop > line) {
          break;
        }
        current = Number(child.dataset.page);
      }
      setPage(current);
      setPageInput(String(current));
    });
  };

  const goTo = (number: number) => {
    const target = scroller.current?.querySelector<HTMLElement>(`.pdf-page[data-page="${number}"]`);
    if (target && scroller.current) {
      scroller.current.scrollTop = target.offsetTop - 12;
    }
  };

  const rotate = () => {
    const element = scroller.current;
    if (element && element.scrollHeight > 0) {
      scrollRatio.current = element.scrollTop / element.scrollHeight;
    }
    setRotation((value) => (value + 90) % 360);
  };

  const print = async () => {
    if (!ready || printing) {
      return;
    }
    setPrinting('Preparing pages…');
    try {
      await printPdf(ready.pdfjs, ready.pdf, rotation, (done, total) =>
        setPrinting(`Preparing page ${done} of ${total}…`),
      );
    } catch {
      window.alert('The document could not be prepared for printing.');
    } finally {
      setPrinting(null);
    }
  };

  // Ctrl+P / Cmd+P would print the page around the viewer; print the document instead
  const printRef = useRef(print);
  printRef.current = print;
  useEffect(() => {
    if (!allowPrint) {
      return undefined;
    }
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'p') {
        event.preventDefault();
        void printRef.current();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [allowPrint]);

  const toolbar = (
    <Toolbar title={title}>
      {ready && scale !== null && (
        <>
          <ToolbarGroup label="Pages">
            <ToolbarButton label="Previous page" disabled={page <= 1} onClick={() => goTo(page - 1)}>
              ‹
            </ToolbarButton>
            <form
              className="page-jump"
              onSubmit={(event) => {
                event.preventDefault();
                const number = Number(pageInput);
                if (Number.isInteger(number) && number >= 1 && number <= numPages) {
                  goTo(number);
                } else {
                  setPageInput(String(page));
                }
              }}
            >
              <input
                aria-label="Page number"
                inputMode="numeric"
                value={pageInput}
                onChange={(event) => setPageInput(event.target.value)}
                onBlur={() => setPageInput(String(page))}
              />
              <span>of {numPages}</span>
            </form>
            <ToolbarButton label="Next page" disabled={page >= numPages} onClick={() => goTo(page + 1)}>
              ›
            </ToolbarButton>
          </ToolbarGroup>
          <ToolbarGroup label="Zoom">
            <ToolbarButton label="Zoom out" disabled={scale <= MIN_SCALE} onClick={() => zoomTo(scale / ZOOM_STEP)}>
              −
            </ToolbarButton>
            <span className="zoom-value" aria-live="polite">
              {Math.round(scale * 100)}%
            </span>
            <ToolbarButton label="Zoom in" disabled={scale >= MAX_SCALE} onClick={() => zoomTo(scale * ZOOM_STEP)}>
              +
            </ToolbarButton>
            <ToolbarButton label="Fit to width" onClick={fitWidth} text>
              Fit width
            </ToolbarButton>
            <ToolbarButton label="Rotate clockwise" onClick={rotate}>
              ↻
            </ToolbarButton>
          </ToolbarGroup>
          {allowPrint && (
            <ToolbarGroup label="Print">
              <ToolbarButton label="Print" onClick={print} disabled={printing !== null} text>
                {printing ?? 'Print'}
              </ToolbarButton>
            </ToolbarGroup>
          )}
        </>
      )}
      {actions}
    </Toolbar>
  );

  let body: ReactNode;
  if (state.kind === 'loading') {
    body = <Notice title="Opening the document…" busy />;
  } else if (state.kind === 'preparing') {
    body = (
      <Notice title="Preparing the document for viewing…" busy>
        This file is being converted to PDF. It opens by itself in a moment.
      </Notice>
    );
  } else if (state.kind === 'error') {
    body = (
      <Notice title={state.message} tone="error">
        {state.signIn && (
          <button type="button" className="button primary" onClick={() => window.location.reload()}>
            Sign in again
          </button>
        )}
      </Notice>
    );
  } else {
    body = null;
  }

  return (
    <div className="viewer">
      {toolbar}
      <div
        className={`pdf-scroller${allowPrint ? '' : ' no-print'}`}
        ref={scroller}
        onScroll={onScroll}
        tabIndex={0}
        aria-label={`${title}, ${numPages} pages`}
        hidden={!ready}
      >
        {ready &&
          scale !== null &&
          Array.from({ length: numPages }, (_, index) => (
            <PdfPage
              key={index + 1}
              pdfjs={ready.pdfjs}
              pdf={ready.pdf}
              number={index + 1}
              scale={scale}
              rotation={rotation}
              base={ready.base}
              observe={observe}
            />
          ))}
      </div>
      {body}
    </div>
  );
}

interface PageProps {
  pdfjs: PdfJsModule;
  pdf: PDFDocumentProxy;
  number: number;
  scale: number;
  rotation: number;
  base: Size;
  observe: Observe;
}

/**
 * One page: a placeholder of the right size until it comes near the screen, then a canvas plus a text layer (so
 * text can be selected and found with the browser's search). Pages far from the screen give their canvas back.
 */
const PdfPage = memo(function PdfPage({ pdfjs, pdf, number, scale, rotation, base, observe }: PageProps) {
  const element = useRef<HTMLDivElement>(null);
  const canvasHost = useRef<HTMLDivElement>(null);
  const textHost = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [measured, setMeasured] = useState<(Size & { rotation: number }) | null>(null);

  useEffect(() => (element.current ? observe(element.current, setVisible) : undefined), [observe]);

  useEffect(() => {
    if (!visible) {
      canvasHost.current?.replaceChildren();
      textHost.current?.replaceChildren();
      return;
    }
    let cancelled = false;
    let render: RenderTask | null = null;
    let text: TextLayer | null = null;
    (async () => {
      const page = await pdf.getPage(number);
      if (cancelled) {
        return;
      }
      const angle = (page.rotate + rotation) % 360;
      const viewport = page.getViewport({ scale, rotation: angle });
      const unscaled = page.getViewport({ scale: 1, rotation: angle });
      setMeasured({ width: unscaled.width, height: unscaled.height, rotation });
      element.current?.style.setProperty('--user-unit', String(viewport.userUnit ?? 1));
      const ratio = window.devicePixelRatio || 1;
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width * ratio);
      canvas.height = Math.floor(viewport.height * ratio);
      render = page.render({
        canvas,
        viewport,
        transform: ratio !== 1 ? [ratio, 0, 0, ratio, 0, 0] : undefined,
        // Draws filled-in form fields, stamps and signatures; nothing on the page is interactive
        annotationMode: pdfjs.AnnotationMode.ENABLE,
      });
      await render.promise;
      if (cancelled) {
        return;
      }
      // The previous canvas stays until this one is ready, so zooming does not flash
      canvasHost.current?.replaceChildren(canvas);
      if (textHost.current) {
        textHost.current.replaceChildren();
        text = new pdfjs.TextLayer({ textContentSource: page.streamTextContent(), container: textHost.current, viewport });
        await text.render();
      }
    })().catch((error: { name?: string }) => {
      if (!cancelled && error?.name !== 'RenderingCancelledException' && error?.name !== 'AbortException') {
        console.warn(`Page ${number} could not be drawn`, error);
      }
    });
    return () => {
      cancelled = true;
      render?.cancel();
      text?.cancel();
    };
  }, [visible, pdfjs, pdf, number, scale, rotation]);

  // Until a page is measured, assume it has the first page's size
  let size: Size = base;
  let sizeRotation = 0;
  if (measured) {
    size = measured;
    sizeRotation = measured.rotation;
  }
  if ((rotation - sizeRotation + 360) % 180 !== 0) {
    size = { width: size.height, height: size.width };
  }
  const style = {
    width: `${Math.floor(size.width * scale)}px`,
    height: `${Math.floor(size.height * scale)}px`,
    '--scale-factor': String(scale),
  } as CSSProperties;

  return (
    <div className="pdf-page" data-page={number} ref={element} style={style} aria-label={`Page ${number}`}>
      <div className="pdf-canvas" ref={canvasHost} />
      <div className="textLayer" ref={textHost} />
    </div>
  );
});

/** One IntersectionObserver for all pages; pages within about two screens of view are drawn. */
function useVisibility(root: RefObject<HTMLDivElement | null>): Observe {
  const callbacks = useRef(new Map<Element, (visible: boolean) => void>());
  const observer = useRef<IntersectionObserver | null>(null);

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') {
      callbacks.current.forEach((callback) => callback(true));
      return undefined;
    }
    observer.current = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          callbacks.current.get(entry.target)?.(entry.isIntersecting);
        }
      },
      { root: root.current, rootMargin: '1500px 0px' },
    );
    callbacks.current.forEach((_, element) => observer.current?.observe(element));
    return () => observer.current?.disconnect();
  }, [root]);

  return useCallback((element: Element, onChange: (visible: boolean) => void) => {
    callbacks.current.set(element, onChange);
    if (observer.current) {
      observer.current.observe(element);
    } else if (typeof IntersectionObserver === 'undefined') {
      onChange(true);
    }
    return () => {
      callbacks.current.delete(element);
      observer.current?.unobserve(element);
    };
  }, []);
}

import React, { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { CHART_HTML } from './chartHtml.generated';

export interface ChartHandle {
  send: (msg: object) => void;
}

interface Props {
  onReady: () => void;
  onError?: (message: string) => void;
}

/** Web build (testing): same chart page inside an iframe, messages via postMessage. */
export const ChartView = forwardRef<ChartHandle, Props>(function ChartView({ onReady, onError }, ref) {
  const frame = useRef<HTMLIFrameElement>(null);
  useImperativeHandle(ref, () => ({
    send: (msg: object) => frame.current?.contentWindow?.postMessage(JSON.stringify(msg), '*'),
  }));
  useEffect(() => {
    const h = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow || typeof e.data !== 'string') return;
      try {
        const m = JSON.parse(e.data);
        if (m.type === 'ready') onReady();
        if (m.type === 'error') onError?.(m.message);
      } catch {
        /* ignore */
      }
    };
    window.addEventListener('message', h);
    return () => window.removeEventListener('message', h);
  }, [onReady, onError]);
  return (
    <iframe
      ref={frame}
      data-testid="chart-frame"
      title="XAU/USD chart"
      srcDoc={CHART_HTML}
      style={{ flex: 1, border: 0, width: '100%', height: '100%', background: '#0B0B0D' }}
    />
  );
});

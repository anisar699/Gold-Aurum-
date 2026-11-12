import React, { forwardRef, useImperativeHandle, useRef } from 'react';
import { WebView as RNWebView, type WebViewMessageEvent, type WebViewProps } from 'react-native-webview';
import { CHART_HTML } from './chartHtml.generated';

export interface ChartHandle {
  send: (msg: object) => void;
}

interface Props {
  onReady: () => void;
  onError?: (message: string) => void;
}

// The platform implementation (WebView.android) is a forwardRef component exposing injectJavaScript; the generic
// typings omit the ref, so narrow it explicitly.
interface WebViewRef { injectJavaScript: (js: string) => void }
const WebView = RNWebView as unknown as React.ComponentType<WebViewProps & { ref?: React.Ref<WebViewRef> }>;

/** Native chart: TradingView Lightweight Charts running offline inside a WebView. */
export const ChartView = forwardRef<ChartHandle, Props>(function ChartView({ onReady, onError }, ref) {
  const web = useRef<WebViewRef>(null);
  useImperativeHandle(ref, () => ({
    send: (msg: object) => {
      web.current?.injectJavaScript(`window.__recv(${JSON.stringify(JSON.stringify(msg))}); true;`);
    },
  }));
  return (
    <WebView
      ref={web}
      testID="chart-webview"
      originWhitelist={['*']}
      source={{ html: CHART_HTML }}
      style={{ flex: 1, backgroundColor: '#0B0B0D' }}
      javaScriptEnabled
      domStorageEnabled={false}
      setSupportMultipleWindows={false}
      allowsLinkPreview={false}
      overScrollMode="never"
      onMessage={(e: WebViewMessageEvent) => {
        try {
          const m = JSON.parse(e.nativeEvent.data);
          if (m.type === 'ready') onReady();
          if (m.type === 'error') onError?.(m.message);
        } catch {
          /* ignore */
        }
      }}
    />
  );
});

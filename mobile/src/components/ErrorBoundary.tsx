import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { reportCrash } from '../lib/crash';
import { C } from './theme';
import { Button } from './ui';

interface State { error: Error | null }

/** Catches render errors, records them in the crash log (and backend, if reachable) and offers recovery. */
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    reportCrash(error, { componentStack: info.componentStack ?? '' });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <View style={{ flex: 1, backgroundColor: C.bg, padding: 20, paddingTop: 60 }}>
        <Text style={{ color: C.gold, fontSize: 18, fontWeight: '800', marginBottom: 8 }}>Kuch ghalat ho gaya / Something went wrong</Text>
        <ScrollView style={{ maxHeight: 200, marginBottom: 16 }}>
          <Text style={{ color: C.sub, fontSize: 12 }}>{String(this.state.error.message)}</Text>
        </ScrollView>
        <Button title="Dobara koshish / Retry" onPress={() => this.setState({ error: null })} />
      </View>
    );
  }
}

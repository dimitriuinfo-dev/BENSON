import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { LogBox, View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import * as SplashScreen from 'expo-splash-screen';

// Expo's own dev-client entry activates keep-awake automatically on load; on this device it
// throws ("Unable to activate keep awake") before the Activity is ready. It's Expo internals,
// not our code (confirmed: no expo-keep-awake call anywhere in this app), and dev-only —
// harmless, but noisy as a red LogBox banner during testing.
LogBox.ignoreLogs(['Unable to activate keep awake']);

// Keeps the native splash screen up until app/index.tsx's boot effect explicitly hides it
// (success, thrown error, or its own 10s safety timeout — see that file's comment). Must run at
// module scope, before any component mounts, per expo-splash-screen's own contract. Swallowed on
// failure (e.g. called after already resolved) — a duplicate-call warning is harmless; a stuck
// splash is not.
SplashScreen.preventAutoHideAsync().catch(() => {});

// Root Error Boundary (product-owner-directed 2026-08-01) — the audit that started this session
// found none anywhere in the app: an uncaught render-time exception meant a fully blank screen,
// no message, nothing the user could act on. Class component because React error boundaries have
// no hook equivalent (getDerivedStateFromError/componentDidCatch are class-only APIs).
//
// "Repornește" resets ONLY this boundary's own state to remount the children fresh — it does NOT
// restart the OS process or the JS engine (this project has no expo-updates dependency, which is
// what that would require). Honest partial recovery: fixes a bad render caused by transient/stale
// state; won't help if the same root cause throws again immediately.
class RootErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[RootErrorBoundary] caught', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <View style={errorStyles.root}>
          <Text style={errorStyles.title}>BENSON a întâmpinat o eroare</Text>
          <Text style={errorStyles.message}>{this.state.error.message || String(this.state.error)}</Text>
          <TouchableOpacity
            style={errorStyles.button}
            onPress={() => this.setState({ error: null })}
            accessibilityLabel="Restart"
            accessibilityRole="button"
          >
            <Text style={errorStyles.buttonText}>Repornește</Text>
          </TouchableOpacity>
        </View>
      );
    }
    return this.props.children;
  }
}

const errorStyles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#2E3742', alignItems: 'center', justifyContent: 'center', padding: 24 },
  title: { color: '#E9E4D8', fontSize: 18, fontWeight: '700', marginBottom: 12, textAlign: 'center' },
  message: { color: '#9AA3AC', fontSize: 14, marginBottom: 24, textAlign: 'center' },
  button: { backgroundColor: '#C9A24B', paddingHorizontal: 24, paddingVertical: 12, borderRadius: 8 },
  buttonText: { color: '#2E3742', fontWeight: '700' },
});

export default function RootLayout() {
  return (
    <RootErrorBoundary>
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="index" />
        <Stack.Screen name="debug" />
        <Stack.Screen name="voicediag" />
      </Stack>
      <StatusBar style="light" backgroundColor="#0D1B2A" />
    </RootErrorBoundary>
  );
}

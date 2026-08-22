import { useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, Image, Animated, Easing, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import * as Location from 'expo-location';
import Svg, { Circle } from 'react-native-svg';
import { QuickContactsWidget } from './canvas/QuickContactsWidget';
import { fetchWeatherData, type WeatherData } from '../lib/contextEngine';
import type { QuickContact } from '../lib/quickContacts';

// ─────────────────────────────────────────────────────────────────────────────
// BENSON Main Screen — user-directed rebuild (2026-07-16): dark navy background, the medallion
// (assets/brand/layer-*.png — transparent background, arcs/hairlines rotating opposite
// directions, wordmark plate drawn last so it always sits in front of the moving rings) large at
// top, the dashboard card below it, per the approved mockup.
// ─────────────────────────────────────────────────────────────────────────────

const GOLD        = '#C9A24B';
const GREEN       = '#2E6B57';
const BG          = '#2E3742';
const BG_RAISED   = '#37414E';
const TEXT        = '#E9E4D8';
const TEXT_DIM    = '#9AA3AC';
const LINE        = 'rgba(201,162,75,0.35)';

function tap() {
  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(() => {});
}

const PLATE_SOURCE     = require('../assets/brand/layer-plate.png');
const ARCS_SOURCE      = require('../assets/brand/layer-arcs.png');
const HAIRLINES_SOURCE = require('../assets/brand/layer-hairlines.png');

const AnimatedImage = Animated.createAnimatedComponent(Image);

// Continuous single-direction spin — never resets to 0, so it never visibly jumps backward.
function useContinuousRotation(durationMs: number, clockwise: boolean) {
  const value = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(
      Animated.timing(value, { toValue: 1, duration: durationMs, easing: Easing.linear, useNativeDriver: true }),
    );
    loop.start();
    return () => loop.stop();
  }, [value]);
  return value.interpolate({ inputRange: [0, 1], outputRange: clockwise ? ['0deg', '360deg'] : ['0deg', '-360deg'] });
}

// Global "Silent / Fully Off" control — top-right, always visible. One tap fully silences BENSON
// (stops listening, wake word, and all sound) and it STAYS off until tapped again. When off it turns
// into a large, unmistakable red pill so the user always knows BENSON is muted and how to bring it
// back — critical for meetings / public places.
function SilenceButton({ silenced, onToggle }: { silenced: boolean; onToggle: () => void }) {
  if (silenced) {
    return (
      <TouchableOpacity
        style={s.silencedBanner}
        onPress={() => { tap(); onToggle(); }}
        accessibilityLabel="Benson este oprit complet. Atinge pentru a porni." accessibilityRole="button">
        <Ionicons name="volume-mute" size={20} color="#fff" />
        <Text style={s.silencedBannerText}>BENSON E OPRIT · atinge ca să pornești</Text>
      </TouchableOpacity>
    );
  }
  return (
    <TouchableOpacity
      style={s.silencePill}
      hitSlop={12}
      onPress={() => { tap(); onToggle(); }}
      accessibilityLabel="Oprește complet Benson, mod silențios" accessibilityRole="button">
      <Ionicons name="volume-mute-outline" size={16} color={GOLD} />
      <Text style={s.silencePillText}>SILENȚIOS</Text>
    </TouchableOpacity>
  );
}

// Small settings gear above the medallion — replaces the old bottom SYSTEM box. Spins slowly,
// continuously, independent of the medallion's own rotation.
function SettingsGear({ onOpenSettings }: { onOpenSettings: () => void }) {
  const rotate = useContinuousRotation(20_000, true);
  return (
    <View style={s.gearRow}>
      <TouchableOpacity hitSlop={14} onPress={() => { tap(); onOpenSettings(); }}
        accessibilityLabel="System settings" accessibilityRole="button">
        <Animated.View style={{ transform: [{ rotate }] }}>
          <Ionicons name="settings-outline" size={26} color={GOLD} />
        </Animated.View>
      </TouchableOpacity>
    </View>
  );
}

// Sized from useWindowDimensions() (read live, inside the component) rather than a module-scope
// Dimensions.get('window') call — the latter can run before the native bridge has reported real
// dimensions, producing an unconstrained size. Edge-to-edge, per spec.
function Medallion({ onToggleConvMode }: { onToggleConvMode: () => void }) {
  const { width } = useWindowDimensions();
  const size = Math.round(width);
  // 5042ms — the exact loop duration of the reference video (video (1).mp4, read from its mvhd
  // box: timescale 1000, duration 5042 units), so one full turn matches the original pacing.
  const arcsRotate = useContinuousRotation(5042, true);
  const hairlinesRotate = useContinuousRotation(5042, false);
  return (
    <View style={s.medallionZone}>
      <TouchableOpacity
        style={{ width: size, height: size }}
        activeOpacity={0.85}
        onPress={() => { tap(); onToggleConvMode(); }}
        accessibilityLabel="Toggle listening"
        accessibilityRole="button">
        {/* Rotating rings drawn first (behind); the plate goes last (in front) so the static
            wordmark/background — opaque everywhere except the two ring tracks — always covers
            any ring pixels that rotate into the letters, at every angle, not just at 0deg. */}
        <AnimatedImage
          source={ARCS_SOURCE}
          style={{ position: 'absolute', width: size, height: size, transform: [{ rotate: arcsRotate }] }}
          resizeMode="contain"
        />
        <AnimatedImage
          source={HAIRLINES_SOURCE}
          style={{ position: 'absolute', width: size, height: size, transform: [{ rotate: hairlinesRotate }] }}
          resizeMode="contain"
        />
        <Image source={PLATE_SOURCE} style={{ position: 'absolute', width: size, height: size }} resizeMode="contain" />
      </TouchableOpacity>
    </View>
  );
}

// Real Android PiP shrinks this SAME Activity/React tree — there's no separate native screen to
// point at instead, so this is what renders while isInPip is true. Confirmed live (2026-07-17):
// without this, the shrunk window just showed the full scrolled layout scaled down to the point
// of being blank/illegible. Just the plate (wordmark), centered, filling the tiny window — no
// rotating rings (not worth animating at that size), no clock/text/input, nothing that needs to
// be legible smaller than a thumbnail.
function PipLogoView() {
  const { width, height } = useWindowDimensions();
  const size = Math.round(Math.min(width, height));
  return (
    <View style={[s.root, s.pipRoot]}>
      <Image source={PLATE_SOURCE} style={{ width: size, height: size }} resizeMode="contain" />
    </View>
  );
}

const WEATHER_REFRESH_MS = 15 * 60 * 1000;

// Clock embedded in a weather widget, directly under the medallion — time on one side, current
// conditions (free, no-key open-meteo lookup via lib/contextEngine) on the other, one card.
function ClockWeatherWidget() {
  const [now, setNow] = useState(new Date());
  const [weather, setWeather] = useState<WeatherData | null>(null);

  useEffect(() => {
    const tick = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(tick);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      const perm = await Location.requestForegroundPermissionsAsync();
      if (!perm.granted) return;
      const pos = await Location.getCurrentPositionAsync({});
      const data = await fetchWeatherData(pos.coords.latitude, pos.coords.longitude);
      if (!cancelled) setWeather(data);
    }
    load();
    const refresh = setInterval(load, WEATHER_REFRESH_MS);
    return () => { cancelled = true; clearInterval(refresh); };
  }, []);

  const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const dateStr = now.toLocaleDateString([], { weekday: 'short', day: '2-digit', month: 'short' }).toUpperCase();

  return (
    <View style={s.weatherWidget}>
      <View>
        <Text style={s.clockTime}>{timeStr}</Text>
        <Text style={s.clockDate}>{dateStr}</Text>
      </View>
      {weather && (
        <View style={s.weatherReading}>
          <Ionicons name={weather.precipitation > 0 ? 'rainy-outline' : 'sunny-outline'} size={22} color={GOLD} />
          <Text style={s.weatherTemp}>{Math.round(weather.tempC)}°</Text>
        </View>
      )}
    </View>
  );
}

function RadialIndicator({ active }: { active: boolean }) {
  const pulse = useRef(new Animated.Value(0.45)).current;
  useEffect(() => {
    if (!active) { pulse.setValue(0.45); return; }
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(pulse, { toValue: 1, duration: 700, useNativeDriver: true }),
      Animated.timing(pulse, { toValue: 0.45, duration: 700, useNativeDriver: true }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [active, pulse]);

  return (
    <Animated.View style={{ opacity: pulse }}>
      <Svg width={64} height={64} viewBox="0 0 100 100">
        <Circle cx="50" cy="50" r="44" stroke={GOLD} strokeWidth="7" fill="none" strokeLinecap="round" strokeDasharray="90 190" />
        <Circle cx="50" cy="50" r="44" stroke={GREEN} strokeWidth="7" fill="none" strokeLinecap="round" strokeDasharray="90 190" strokeDashoffset="140" />
        <Circle cx="50" cy="50" r="28" stroke={GOLD} strokeWidth="1.5" fill="none" opacity={0.6} />
      </Svg>
    </Animated.View>
  );
}

// Per-bar shape weights (0-1) — a flat "all bars identical" meter driven by one live number still
// looks like a level meter, but this gives it the familiar EQ silhouette (taller in the middle)
// without faking the actual level each bar reports.
const WAVE_BAR_WEIGHTS = [0.35, 0.5, 0.7, 0.85, 1, 0.9, 1, 0.8, 0.85, 1, 0.9, 1, 0.75, 0.6, 0.45, 0.3];

// Active strictly when the master's voice is actually being recorded (listening) — not while
// thinking or speaking. This is the user's confirmation that BENSON is hearing them, so it must
// not fire for any other reason. `level` is the REAL, live mic amplitude (0-1, from the native
// STT/capture engine) — user-requested 2026-07-30: this used to be a Math.random() animation with
// no relationship to actual audio; now each bar's height is the real level scaled by a fixed
// per-bar weight (see WAVE_BAR_WEIGHTS), so the whole row genuinely rises and falls with your voice.
function ListeningWaveform({ active, level }: { active: boolean; level: number }) {
  const bars = useRef(WAVE_BAR_WEIGHTS.map(() => new Animated.Value(0.15))).current;
  useEffect(() => {
    if (!active) {
      bars.forEach((b) => Animated.timing(b, { toValue: 0.15, duration: 200, useNativeDriver: false }).start());
      return;
    }
    bars.forEach((b, i) => {
      const target = Math.max(0.15, Math.min(1, level * WAVE_BAR_WEIGHTS[i]));
      Animated.timing(b, { toValue: target, duration: 90, useNativeDriver: false }).start();
    });
  }, [active, level, bars]);

  return (
    <View style={s.waveRow}>
      {bars.map((b, i) => (
        <Animated.View key={i} style={[s.waveBar, { backgroundColor: GREEN, height: b.interpolate({ inputRange: [0, 1], outputRange: [3, 22] }) }]} />
      ))}
    </View>
  );
}

// Manual correction line (user-requested 2026-07-17): when STT mishears a name entirely (e.g.
// "Hannah" transcribed as "Ana" — not a fuzzy-match problem, the wrong word from the start), typed
// text goes through the exact same handleIncomingText pipeline a voice transcript does, so a
// command can be corrected/retried by typing instead of only by speaking.
function ManualTextInput({ onSubmitText }: { onSubmitText: (text: string) => void }) {
  const [value, setValue] = useState('');
  function submit() {
    const trimmed = value.trim();
    if (!trimmed) return;
    onSubmitText(trimmed);
    setValue('');
  }
  return (
    <View style={s.manualInputRow}>
      <TextInput
        style={s.manualInput}
        value={value}
        onChangeText={setValue}
        onSubmitEditing={submit}
        placeholder="Scrie o comandă sau un nume..."
        placeholderTextColor={TEXT_DIM}
        returnKeyType="send"
        accessibilityLabel="Manual command input"
      />
      <TouchableOpacity hitSlop={10} onPress={() => { tap(); submit(); }} accessibilityLabel="Send" accessibilityRole="button">
        <Ionicons name="send" size={20} color={GOLD} />
      </TouchableOpacity>
    </View>
  );
}

// No box — just the listening indicator, floating directly on the background. On strictly when
// the master's voice is actually being recorded, so seeing it move is the confirmation BENSON
// is hearing and about to act, never a decorative always-on animation.
function Dashboard({ listening, micVolume }: { listening: boolean; micVolume: number }) {
  return (
    <View style={s.listeningBlock}>
      <RadialIndicator active={listening} />
      <Text style={s.listeningLabel}>{listening ? 'LISTENING' : ''}</Text>
      <ListeningWaveform active={listening} level={micVolume} />
    </View>
  );
}

export function BensonMainScreen({
  listening, micVolume, loading, speaking, showQuickContacts,
  lastReply, quickContacts, isInPip, silenced,
  onToggleConvMode, onOpenSettings, onToggleQuickContacts,
  onQuickContactsChange, onSubmitText, onToggleSilence,
}: {
  listening: boolean;
  micVolume: number;
  loading: boolean;
  speaking: boolean;
  convMode: boolean;
  carMode: boolean;
  showQuickContacts: boolean;
  activeCard: unknown;
  lastReply: string;
  quickContacts: QuickContact[];
  isInPip?: boolean;
  silenced: boolean;
  onToggleConvMode: () => void;
  onOpenSettings: () => void;
  onToggleQuickContacts: () => void;
  onToggleCarMode: () => void;
  onToggleTodo: (id: string) => void;
  onClearCompletedTodo: () => void;
  onQuickContactsChange: (contacts: QuickContact[]) => void;
  onSubmitText: (text: string) => void;
  onToggleSilence: () => void;
}) {
  if (isInPip) return <PipLogoView />;

  return (
    <View style={s.root}>
      <SilenceButton silenced={silenced} onToggle={onToggleSilence} />

      <SettingsGear onOpenSettings={onOpenSettings} />

      <Medallion onToggleConvMode={onToggleConvMode} />

      <ClockWeatherWidget />

      {showQuickContacts && (
        <QuickContactsWidget contacts={quickContacts} onContactsChange={onQuickContactsChange} />
      )}

      <View style={s.replyField}>
        <Text style={s.replyText}>{lastReply}</Text>
      </View>

      <ManualTextInput onSubmitText={onSubmitText} />

      <Dashboard listening={listening} micVolume={micVolume} />
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: BG },
  pipRoot: { alignItems: 'center', justifyContent: 'center' },

  // Silent/off control — floats top-right, above everything.
  silencePill: {
    position: 'absolute', top: 48, right: 16, zIndex: 20,
    flexDirection: 'row', alignItems: 'center', gap: 5,
    borderWidth: 1, borderColor: LINE, borderRadius: 20,
    paddingVertical: 6, paddingHorizontal: 12, backgroundColor: BG_RAISED,
  },
  silencePillText: { color: GOLD, fontSize: 11, fontWeight: '700', letterSpacing: 1 },
  silencedBanner: {
    position: 'absolute', top: 44, left: 16, right: 16, zIndex: 20,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    backgroundColor: '#B23A3A', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 16,
  },
  silencedBannerText: { color: '#fff', fontSize: 13, fontWeight: '800', letterSpacing: 0.5 },

  // Gear sits above the medallion, centered — replaces the old bottom SYSTEM box.
  gearRow: { alignItems: 'center', paddingTop: 48, paddingHorizontal: 20 },
  medallionZone: { alignItems: 'center', justifyContent: 'center', marginTop: 12 },

  // Clock + weather, directly under the medallion. No box — floats on the background.
  weatherWidget: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginHorizontal: 24, marginTop: 12,
  },
  clockTime: { color: TEXT, fontSize: 22, fontWeight: '300' },
  clockDate: { color: TEXT_DIM, fontSize: 10, letterSpacing: 1.5 },
  weatherReading: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  weatherTemp: { color: TEXT, fontSize: 18, fontWeight: '400' },

  // Manual correction line — for when STT mishears a name entirely (e.g. "Hannah" -> "Ana").
  manualInputRow: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    marginHorizontal: 20, marginBottom: 14,
    borderBottomWidth: 1, borderBottomColor: LINE, paddingBottom: 8,
  },
  manualInput: { flex: 1, color: TEXT, fontSize: 14, paddingVertical: 4 },

  // The dialogue between the medallion and the listening indicator — what's being discussed.
  // Plain text, no box/field around it.
  replyField: {
    flex: 1, marginHorizontal: 20, marginTop: 4, marginBottom: 14,
    justifyContent: 'center',
  },
  replyText: { color: TEXT, fontSize: 15, lineHeight: 21, textAlign: 'center' },

  listeningBlock: { alignItems: 'center', marginBottom: 24 },
  listeningLabel: { color: TEXT_DIM, fontSize: 11, letterSpacing: 3, fontWeight: '700', minHeight: 14, marginTop: 6 },
  waveRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 3, height: 24, marginTop: 4 },
  waveBar: { width: 3, borderRadius: 2 },
});

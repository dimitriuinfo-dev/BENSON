import { useEffect, useRef, useState, useCallback } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, Image, Animated, Easing, useWindowDimensions, type LayoutChangeEvent } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import * as Location from 'expo-location';
import Svg, { Circle, Path } from 'react-native-svg';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { logAudioDiag } from 'benson-foreground-service';
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
const BACKGROUND_TEXTURE = require('../assets/brand/background-texture.png');

const AnimatedImage = Animated.createAnimatedComponent(Image);

// Continuous single-direction spin — never resets to 0, so it never visibly jumps backward.
function useContinuousRotation(durationMs: number, clockwise: boolean, active = true) {
  const value = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!active) {
      value.stopAnimation();
      value.setValue(0);
      return;
    }
    const loop = Animated.loop(
      Animated.timing(value, { toValue: 1, duration: durationMs, easing: Easing.linear, useNativeDriver: true }),
    );
    loop.start();
    return () => loop.stop();
  }, [active, durationMs, value]);
  return value.interpolate({ inputRange: [0, 1], outputRange: clockwise ? ['0deg', '360deg'] : ['0deg', '-360deg'] });
}

// Background rebuild (2026-09-23, user-directed, reference: LOGO GRAFIC/437393c4...png) — the
// prior flat `BG` fill is replaced by this same texture image, laid out adaptively instead of
// assuming one phone's dimensions:
//   - sized from useWindowDimensions() (live), never a fixed OnePlus resolution
//   - resizeMode="cover" preserves the image's own aspect ratio and crops symmetrically —
//     never stretches/distorts, never leaves an empty margin, on any screen ratio
//   - absolutely positioned behind the whole root View, so safe-area/notch/system-bar handling
//     already in the rest of this screen is completely unaffected (this layer never receives
//     touches: pointerEvents="none")
//   - user feedback (2026-09-23): the raw texture read as too dark, darker than the original
//     flat `BG` fill it replaced — drawn at reduced opacity over the still-present `s.root`
//     backgroundColor: BG (never removed, see below) so that original tone shows through and
//     lightens the result, instead of the texture's own near-black tone standing alone.
function BackgroundTexture() {
  const { width, height } = useWindowDimensions();
  return (
    <Image
      source={BACKGROUND_TEXTURE}
      style={[StyleSheet.absoluteFillObject, { width, height, opacity: 0.55 }]}
      resizeMode="cover"
    />
  );
}

type AnchorRect = { x: number; y: number; width: number; height: number };

// Reports this child's position relative to the SAME root View the Svg overlay below fills —
// onLayout's nativeEvent.layout is already relative to the immediate parent, and every anchored
// control here is a direct child of s.root, exactly like <ControlConduits> itself, so no
// separate measureInWindow() step (and its extra async round trip) is needed.
function useAnchorReport(onAnchor: (r: AnchorRect) => void) {
  return useCallback(
    (e: LayoutChangeEvent) => {
      const { x, y, width, height } = e.nativeEvent.layout;
      onAnchor({ x, y, width, height });
    },
    [onAnchor],
  );
}

// The texture's own baked-in circuit lines are decorative only — on a different screen size/ratio
// they land wherever `cover` cropping happens to leave them, never guaranteed to reach a real
// control. These two conduits are drawn fresh every render from each control's ACTUAL measured
// center (via useAnchorReport above), so they visibly terminate at the mute button and the
// settings gear on every phone, not just the reference device. Renders nothing until both anchors
// have reported at least once (avoids a flash of wrongly-routed lines before first layout).
function ControlConduits({ muteAnchor, gearAnchor }: { muteAnchor: AnchorRect | null; gearAnchor: AnchorRect | null }) {
  const { width } = useWindowDimensions();
  if (!muteAnchor || !gearAnchor) return null;

  const muteCx = muteAnchor.x + muteAnchor.width / 2;
  const muteCy = muteAnchor.y + muteAnchor.height / 2;
  const gearCx = gearAnchor.x + gearAnchor.width / 2;
  const gearCy = gearAnchor.y + gearAnchor.height / 2;

  // Simple two-segment "elbow" per conduit, matching the reference texture's own right-angle
  // routing style: straight down from the top edge, then straight across into the control.
  const mutePath = `M ${muteCx} 0 L ${muteCx} ${Math.max(0, muteCy - 18)} L ${muteCx + muteAnchor.width / 2 + 10} ${muteCy}`;
  const gearPath = `M ${gearCx} 0 L ${gearCx} ${Math.max(0, gearCy - 14)}`;

  return (
    <Svg width={width} pointerEvents="none" style={StyleSheet.absoluteFillObject}>
      <Path d={mutePath} stroke={GOLD} strokeWidth={1.5} fill="none" opacity={0.55} strokeLinecap="round" />
      <Circle cx={muteCx + muteAnchor.width / 2 + 10} cy={muteCy} r={3} fill={GOLD} opacity={0.7} />
      <Path d={gearPath} stroke={GREEN} strokeWidth={1.5} fill="none" opacity={0.55} strokeLinecap="round" />
      <Circle cx={gearCx} cy={Math.max(0, gearCy - 14)} r={3} fill={GREEN} opacity={0.7} />
    </Svg>
  );
}

// "Silent / Fully Off" — the only thing TopControls still renders is the way BACK on: a full-width
// red banner shown only while silenced. Its own trigger button ("ÎNCHIDE COMPLET") moved into
// Settings (user-directed 2026-08-23: invisible/easy to miss floating over the medallion) — this
// component now only ever appears once already off, and always as this unmistakable banner.
function TopControls({ silenced, onToggleSilence }: {
  silenced: boolean; onToggleSilence: () => void;
}) {
  if (!silenced) return null;
  return (
    <TouchableOpacity
      style={s.silencedBanner}
      onPress={() => { tap(); onToggleSilence(); }}
      accessibilityLabel="Benson este oprit complet. Atinge pentru a porni." accessibilityRole="button">
      <Ionicons name="volume-mute" size={20} color="#fff" />
      <Text style={s.silencedBannerText}>BENSON E OPRIT · atinge ca să pornești</Text>
    </TouchableOpacity>
  );
}

// Mute-only toggle — user-directed 2026-08-23: first tried anchored near the bottom, but on this
// device that landed right on top of the system nav bar/gesture area (the app renders edge-to-edge
// with no other safe-area handling). Moved to the top-left corner instead — floating, icon-only (a
// megaphone the user just taps, no pill/label) — using the REAL top inset so it sits below the
// status bar/notch on any phone, not a fixed guess. Keeps listening (wake word + commands still
// work) but makes no sound; distinct from "fully off", which stops listening entirely and now
// lives in Settings.
function MuteButton({ muted, onToggleMute, silenced, onAnchor }: { muted: boolean; onToggleMute: () => void; silenced: boolean; onAnchor: (r: AnchorRect) => void }) {
  const insets = useSafeAreaInsets();
  const reportAnchor = useAnchorReport(onAnchor);
  // Hidden while fully silenced — user-directed 2026-08-23: this button's top-left position
  // physically overlapped the "BENSON E OPRIT" banner (same corner, same zIndex, painted after it),
  // so tapping the banner was actually hitting this button instead — toggleMute() has no visible
  // effect while already silenced (sound is already off), which read as "the banner does nothing".
  // Mute is meaningless anyway when everything is already off, so hiding it removes the conflict
  // entirely instead of just nudging positions further apart.
  if (silenced) return null;
  return (
    <View style={[s.muteButtonCorner, { top: insets.top + 18, left: 26 }]} onLayout={reportAnchor}>
      <TouchableOpacity
        style={s.muteButton}
        hitSlop={14}
        onPress={() => { tap(); onToggleMute(); }}
        accessibilityLabel={muted ? 'Pornește sunetul' : 'Mod mut, ascultă fără sunet'} accessibilityRole="button"
        accessibilityState={{ selected: muted }}>
        <Ionicons name={muted ? 'megaphone' : 'megaphone-outline'} size={22} color={muted ? GREEN : GOLD} />
      </TouchableOpacity>
    </View>
  );
}

// Camera shortcut, top-right corner mirroring the mute button (user-directed 2026-09-23, revised
// to top-right per screen.jpg). Calls a DEDICATED direct-launch handler (onOpenCamera), not
// onSubmitText('deschide camera') — that text pipeline was tried first and found ambiguous
// on-device: several OTHER installed packages also contain "camera" in their name, and the
// on-screen fallback ended up re-clicking this same button's own accessibilityLabel instead of
// opening the app. See app/index.tsx's handleOpenCamera for the deterministic fix. Hidden while
// silenced, same reasoning as MuteButton just above.
function CameraButton({ silenced, onOpenCamera }: { silenced: boolean; onOpenCamera: () => void }) {
  const insets = useSafeAreaInsets();
  if (silenced) return null;
  return (
    <View style={[s.cameraButtonCorner, { top: insets.top + 18, right: 6 }]}>
      <TouchableOpacity
        style={s.muteButton}
        hitSlop={14}
        onPress={() => { tap(); onOpenCamera(); }}
        accessibilityLabel="Deschide camera" accessibilityRole="button">
        <Ionicons name="camera-outline" size={22} color={GOLD} />
      </TouchableOpacity>
    </View>
  );
}

// Small settings gear above the medallion — replaces the old bottom SYSTEM box. Spins slowly,
// continuously, independent of the medallion's own rotation.
function SettingsGear({ onOpenSettings, onAnchor, active }: { onOpenSettings: () => void; onAnchor: (r: AnchorRect) => void; active: boolean }) {
  const rotate = useContinuousRotation(20_000, true, active);
  const reportAnchor = useAnchorReport(onAnchor);
  return (
    <View style={s.gearRow} onLayout={reportAnchor}>
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
function Medallion({ onManualActivation, active }: { onManualActivation: () => void; active: boolean }) {
  const { width } = useWindowDimensions();
  const size = Math.round(width);
  // 5042ms — the exact loop duration of the reference video (video (1).mp4, read from its mvhd
  // box: timescale 1000, duration 5042 units), so one full turn matches the original pacing.
  const arcsRotate = useContinuousRotation(5042, true, active);
  const hairlinesRotate = useContinuousRotation(5042, false, active);
  return (
    <View style={s.medallionZone}>
      <TouchableOpacity
        style={{ width: size, height: size }}
        activeOpacity={0.85}
        onPress={() => { tap(); onManualActivation(); }}
        accessibilityLabel="Start voice command"
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
function ManualTextInput({ onSubmitText }: { onSubmitText: (text: string, requestId?: string) => void }) {
  const [value, setValue] = useState('');
  function submit() {
    // ROUND_INPUT_ROUTING_1 (2026-09-23) — the RAW `value` state at the exact moment of submit,
    // BEFORE trim(), tagged with a requestId that also appears in MISSION_INPUT (app/index.tsx) —
    // this is the real production field (the debug screen has its own separate copy of this same
    // trace), so a submitted-vs-received mismatch found here is the one that matters live.
    const requestId = `main-${Date.now()}`;
    logAudioDiag('DEBUG_FIELD_AT_SUBMIT', `requestId=${requestId} raw=${JSON.stringify(value)} length=${value.length}`);
    const trimmed = value.trim();
    if (!trimmed) return;
    onSubmitText(trimmed, requestId);
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

// No box — just the listening indicator, floating directly on the background. Passive wake uses
// the native reader state; the waveform moves only during command capture, when live mic level exists.
function Dashboard({ listening, wakeActive, micVolume }: { listening: boolean; wakeActive: boolean; micVolume: number }) {
  const active = listening || wakeActive;
  return (
    <View style={s.listeningBlock}>
      <RadialIndicator active={active} />
      <Text style={s.listeningLabel}>{listening ? 'LISTENING' : wakeActive ? 'ASCULT' : ''}</Text>
      <ListeningWaveform active={listening} level={micVolume} />
    </View>
  );
}

export function BensonMainScreen({
  listening, wakeActive, micVolume, loading, speaking, showQuickContacts,
  lastReply, quickContacts, isInPip, silenced, muted,
  onManualActivation, onOpenSettings, onToggleQuickContacts,
  onQuickContactsChange, onSubmitText, onToggleSilence, onToggleMute, onOpenCamera,
}: {
  listening: boolean;
  wakeActive: boolean;
  micVolume: number;
  loading: boolean;
  speaking: boolean;
  carMode: boolean;
  showQuickContacts: boolean;
  activeCard: unknown;
  lastReply: string;
  quickContacts: QuickContact[];
  isInPip?: boolean;
  silenced: boolean;
  muted: boolean;
  onManualActivation: () => void;
  onOpenSettings: () => void;
  onToggleQuickContacts: () => void;
  onToggleCarMode: () => void;
  onToggleTodo: (id: string) => void;
  onClearCompletedTodo: () => void;
  onQuickContactsChange: (contacts: QuickContact[]) => void;
  onSubmitText: (text: string, requestId?: string) => void;
  onToggleSilence: () => void;
  onToggleMute: () => void;
  onOpenCamera: () => void;
}) {
  // Declared before the isInPip early return — React hooks must run unconditionally, in the
  // same order, every render.
  const [muteAnchor, setMuteAnchor] = useState<AnchorRect | null>(null);
  const [gearAnchor, setGearAnchor] = useState<AnchorRect | null>(null);

  if (isInPip) return <PipLogoView />;

  return (
    <View style={s.root}>
      <BackgroundTexture />
      <ControlConduits muteAnchor={muteAnchor} gearAnchor={gearAnchor} />

      <TopControls silenced={silenced} onToggleSilence={onToggleSilence} />

      <MuteButton muted={muted} onToggleMute={onToggleMute} silenced={silenced} onAnchor={setMuteAnchor} />
      <CameraButton silenced={silenced} onOpenCamera={onOpenCamera} />

      <SettingsGear onOpenSettings={onOpenSettings} onAnchor={setGearAnchor} active={listening || wakeActive || loading || speaking} />

      <Medallion onManualActivation={onManualActivation} active={listening || wakeActive || loading || speaking} />

      <ClockWeatherWidget />

      {showQuickContacts && (
        <QuickContactsWidget contacts={quickContacts} onContactsChange={onQuickContactsChange} />
      )}

      <View style={s.replyField}>
        <Text style={s.replyText}>{lastReply}</Text>
      </View>

      <ManualTextInput onSubmitText={onSubmitText} />

      <Dashboard listening={listening} wakeActive={wakeActive} micVolume={micVolume} />
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: BG },
  pipRoot: { alignItems: 'center', justifyContent: 'center' },

  // "Fully off" way-back-on banner — the only thing left up top; its trigger button now lives in
  // Settings (see app/index.tsx).
  silencedBanner: {
    position: 'absolute', top: 44, left: 16, right: 16, zIndex: 20,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    backgroundColor: '#B23A3A', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 16,
  },
  silencedBannerText: { color: '#fff', fontSize: 13, fontWeight: '800', letterSpacing: 0.5 },

  // Mute-only icon button — alone, near the bottom, below the listening indicator. Icon-only by
  // design (a megaphone the user just taps), no pill/label.
  // Top-left, floating — `top` set inline from real safe-area insets (see MuteButton).
  muteButtonCorner: { position: 'absolute', left: 16, zIndex: 20 },
  // Camera shortcut — top-right corner, mirroring the mute button's top-left position.
  cameraButtonCorner: { position: 'absolute', right: 16, zIndex: 20 },
  // Keycap, not a circle — no drawn circle, styled as a squared-off key (thin border, small corner
  // radius).
  muteButton: {
    width: 44, height: 44, alignItems: 'center', justifyContent: 'center',
  },

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

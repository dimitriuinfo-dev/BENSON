import { useEffect, useRef } from 'react';
import { AppState, Image, StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  cancelAnimation,
  Easing,
} from 'react-native-reanimated';
import { green, greenBright } from '../../lib/theme';

const AnimatedImage = Animated.createAnimatedComponent(Image);

export type ChronometerState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'stopped';

// "The Butler's Instrument" — LAW 1: the medallion is the real, pixel-exact master asset
// (assets/benson-logo.png, sliced by tools/slice-logo.js into three layers), never a coded
// recreation. layer-plate is the static base (background + B·E·N·S·O·N wordmark + "AI BUTLER" +
// tick marks — never rotates, not one pixel). layer-arcs and layer-hairlines are the two
// counter-rotating annuli, stacked on top; their own transparent regions let the plate show
// through everywhere they don't draw, so stacking order between them doesn't matter visually —
// arcs is placed first (below) simply because it's the heavier/more visually dominant ring.
//
// LAW 2: rotation IS the liveness signal, never decoration. Speed changes only — the plate never
// rotates, scales, or opacity-pulses, so the signal stays unambiguous.
const ARCS_IDLE_MS = 60_000;
const HAIRLINES_IDLE_MS = 90_000;
const SPEED_MULTIPLIER_LISTENING = 2;
const SPEED_MULTIPLIER_THINKING_ARCS = 3;
const TRANSITION_MS = 300;

function arcsDurationFor(state: ChronometerState): number {
  if (state === 'listening') return ARCS_IDLE_MS / SPEED_MULTIPLIER_LISTENING;
  if (state === 'thinking') return ARCS_IDLE_MS / SPEED_MULTIPLIER_THINKING_ARCS;
  if (state === 'speaking') return ARCS_IDLE_MS / SPEED_MULTIPLIER_LISTENING;
  return ARCS_IDLE_MS;
}

function hairlinesDurationFor(state: ChronometerState): number {
  if (state === 'listening') return HAIRLINES_IDLE_MS / SPEED_MULTIPLIER_LISTENING;
  return HAIRLINES_IDLE_MS; // thinking/speaking leave the hairline layer at its idle drift
}

export function Chronometer({ state, size = 210 }: { state: ChronometerState; size?: number }) {
  const arcsRotate = useSharedValue(0);
  const hairlinesRotate = useSharedValue(0);
  const appActive = useRef(true);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      const active = next === 'active';
      appActive.current = active;
      if (!active) {
        cancelAnimation(arcsRotate);
        cancelAnimation(hairlinesRotate);
      }
    });
    return () => sub.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!appActive.current) return;
    // STOPPED — BENSON is dead or muted; the rings actually stop, never fake liveness.
    if (state === 'stopped') {
      cancelAnimation(arcsRotate);
      cancelAnimation(hairlinesRotate);
      return;
    }
    cancelAnimation(arcsRotate);
    cancelAnimation(hairlinesRotate);
    // Continuous single-direction spins, restarted from the current angle (not reset to 0) so a
    // state change never visibly jumps the rings backward — only the speed changes, per LAW 2.
    arcsRotate.value = withRepeat(
      withTiming(arcsRotate.value + 360, { duration: arcsDurationFor(state), easing: Easing.linear }),
      -1,
      false,
    );
    hairlinesRotate.value = withRepeat(
      withTiming(hairlinesRotate.value - 360, { duration: hairlinesDurationFor(state), easing: Easing.linear }),
      -1,
      false,
    );
    return () => {
      cancelAnimation(arcsRotate);
      cancelAnimation(hairlinesRotate);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  const arcsStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${arcsRotate.value}deg` }],
  }));
  const hairlinesStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${hairlinesRotate.value}deg` }],
  }));

  return (
    <Animated.View style={{ width: size, height: size }}>
      <Image
        source={require('../../assets/brand/layer-plate.png')}
        style={StyleSheet.absoluteFillObject}
        resizeMode="contain"
      />
      <AnimatedImage
        source={require('../../assets/brand/layer-arcs.png')}
        style={[StyleSheet.absoluteFillObject, arcsStyle]}
        resizeMode="contain"
      />
      <AnimatedImage
        source={require('../../assets/brand/layer-hairlines.png')}
        style={[StyleSheet.absoluteFillObject, hairlinesStyle]}
        resizeMode="contain"
      />
      {state === 'listening' && <ListeningWaveform size={size} />}
    </Animated.View>
  );
}

// LAW 2 — listening's one functional addition: a thin green waveform line below the medallion,
// the ONLY element allowed to appear/disappear. It shows voice input is being captured, not
// decoration — present exclusively in the listening state.
function ListeningWaveform({ size }: { size: number }) {
  const opacity = useSharedValue(0);
  useEffect(() => {
    opacity.value = withTiming(1, { duration: TRANSITION_MS });
    return () => { opacity.value = withTiming(0, { duration: TRANSITION_MS }); };
  }, [opacity]);
  const style = useAnimatedStyle(() => ({ opacity: opacity.value }));
  return (
    <Animated.View
      style={[
        { position: 'absolute', bottom: -size * 0.08, left: 0, right: 0, height: 2, borderRadius: 1, backgroundColor: greenBright },
        style,
      ]}
    />
  );
}

// Kept for any caller still importing the old export name — 'listening'/'thinking'/'speaking'
// deliberately match the previous ChronometerState so app/index.tsx's deriveChronometerState
// callers didn't need to change.
export { green };

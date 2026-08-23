import { useRef, useState } from 'react';
import { View, PanResponder, StyleSheet, type LayoutChangeEvent } from 'react-native';
import { GOLD, MUTED } from '../../lib/theme';

// Minimal drag-to-set slider using built-in PanResponder — no @react-native-community/slider
// dependency, so it can't introduce a native-module build risk (same reasoning as
// components/dashboard/Draggable.tsx).
export function SimpleSlider({
  min,
  max,
  value,
  onChange,
  onSlideEnd,
}: {
  min: number;
  max: number;
  value: number;
  onChange: (v: number) => void;
  onSlideEnd?: (v: number) => void;
}) {
  const widthRef = useRef(0);
  const [, forceLayout] = useState(0);

  function handleLayout(e: LayoutChangeEvent) {
    widthRef.current = e.nativeEvent.layout.width;
    forceLayout(w => w + 1);
  }

  function posToValue(x: number): number {
    const w = widthRef.current || 1;
    const ratio = Math.min(1, Math.max(0, x / w));
    return Math.round((min + ratio * (max - min)) * 100) / 100;
  }

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderMove: (evt) => onChange(posToValue(evt.nativeEvent.locationX)),
      onPanResponderRelease: (evt) => {
        const v = posToValue(evt.nativeEvent.locationX);
        onChange(v);
        onSlideEnd?.(v);
      },
    }),
  ).current;

  const ratio = Math.min(1, Math.max(0, (value - min) / (max - min)));

  return (
    <View style={s.track} onLayout={handleLayout} {...panResponder.panHandlers}>
      <View style={s.base} />
      <View style={[s.fill, { width: `${ratio * 100}%` }]} />
      <View style={[s.thumb, { left: `${ratio * 100}%` }]} />
    </View>
  );
}

const s = StyleSheet.create({
  track: { height: 32, justifyContent: 'center' },
  base:  { position: 'absolute', left: 0, right: 0, top: 14, height: 4, backgroundColor: MUTED, borderRadius: 2, opacity: 0.4 },
  fill:  { position: 'absolute', left: 0, top: 14, height: 4, backgroundColor: GOLD, borderRadius: 2 },
  thumb: { position: 'absolute', top: 6, width: 20, height: 20, borderRadius: 10, backgroundColor: GOLD, marginLeft: -10 },
});

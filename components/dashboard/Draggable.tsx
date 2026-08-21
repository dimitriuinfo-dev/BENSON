import { useEffect, useRef } from 'react';
import { Animated, PanResponder, type ViewStyle } from 'react-native';
import type { Vec2 } from '../../lib/dashboardLayout';

// Sticker-style drag: the child keeps its normal flex position, this only adds a persisted
// (dx, dy) transform on top. Uses built-in Animated + PanResponder — no react-native-gesture-handler
// dependency — so it can't introduce a native-module build failure like benson-accessibility did.
export function Draggable({
  id,
  offset,
  onDragEnd,
  style,
  children,
}: {
  id: string;
  offset: Vec2;
  onDragEnd: (id: string, pos: Vec2) => void;
  style?: ViewStyle;
  children: React.ReactNode;
}) {
  const pan = useRef(new Animated.ValueXY(offset)).current;
  const offsetRef = useRef(offset);

  useEffect(() => {
    if (offset.x !== offsetRef.current.x || offset.y !== offsetRef.current.y) {
      offsetRef.current = offset;
      pan.setValue(offset);
    }
  }, [offset.x, offset.y]);

  const panResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_evt, g) => Math.abs(g.dx) > 4 || Math.abs(g.dy) > 4,
      onPanResponderGrant: () => {
        pan.extractOffset();
      },
      onPanResponderMove: Animated.event([null, { dx: pan.x, dy: pan.y }], { useNativeDriver: false }),
      onPanResponderRelease: () => {
        pan.flattenOffset();
        const current = { x: (pan.x as any)._value, y: (pan.y as any)._value };
        offsetRef.current = current;
        onDragEnd(id, current);
      },
    }),
  ).current;

  return (
    <Animated.View
      style={[style, { transform: pan.getTranslateTransform() }]}
      {...panResponder.panHandlers}
    >
      {children}
    </Animated.View>
  );
}

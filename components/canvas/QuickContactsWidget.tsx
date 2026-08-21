import { useRef, useState } from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, ScrollView, Animated, PanResponder, Linking,
} from 'react-native';
import { gold, bg, bgRaised, textDim, line } from '../../lib/theme';
import { setPreferredChannel, type QuickContact, type Channel } from '../../lib/quickContacts';

type Props = {
  contacts: QuickContact[];
  onContactsChange: (contacts: QuickContact[]) => void;
};

const RING_RADIUS = 90;
const HIT_RADIUS  = 45;
const MIN_DRAG     = 30;

const ICONS: { channel: Channel; dx: number; dy: number; icon: string }[] = [
  { channel: 'phone',    dx: 0,            dy: -RING_RADIUS, icon: '📞' },
  { channel: 'whatsapp', dx: RING_RADIUS,  dy: 0,            icon: '💬' },
  { channel: 'sms',      dx: 0,            dy: RING_RADIUS,  icon: '✉' },
  { channel: 'email',    dx: -RING_RADIUS, dy: 0,            icon: '📧' },
];

function initials(name: string): string {
  return name.trim().split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();
}

function fireAction(contact: QuickContact, channel: Channel) {
  const phone = contact.phone;
  const email = contact.email;
  if (channel === 'phone' && phone) Linking.openURL(`tel:${phone}`).catch(() => {});
  if (channel === 'whatsapp' && phone) Linking.openURL(`whatsapp://send?phone=${phone.replace(/[^\d+]/g, '')}`).catch(() => {});
  if (channel === 'sms' && phone) Linking.openURL(`sms:${phone}`).catch(() => {});
  if (channel === 'email' && email) Linking.openURL(`mailto:${email}`).catch(() => {});
}

// Drupe-style radial contact widget: tap a bubble to reveal a 4-way action ring,
// drag toward an icon to fire it. Built on PanResponder — no gesture-handler setup needed.
export function QuickContactsWidget({ contacts, onContactsChange }: Props) {
  const [expanded, setExpanded] = useState<{ contact: QuickContact; x: number; y: number } | null>(null);
  const [drag, setDrag] = useState({ dx: 0, dy: 0 });
  const [highlight, setHighlight] = useState<Channel | null>(null);
  const ringScale = useRef(new Animated.Value(0)).current;
  const bubbleRefs = useRef<Record<string, View | null>>({});

  function openRing(contact: QuickContact) {
    const node = bubbleRefs.current[contact.id];
    node?.measureInWindow((x, y, width, height) => {
      setExpanded({ contact, x: x + width / 2, y: y + height / 2 });
      setDrag({ dx: 0, dy: 0 });
      setHighlight(null);
      ringScale.setValue(0);
      Animated.spring(ringScale, { toValue: 1, useNativeDriver: true, friction: 6 }).start();
    });
  }

  function closeRing() {
    setExpanded(null);
    setHighlight(null);
  }

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (_, gesture) => {
        setDrag({ dx: gesture.dx, dy: gesture.dy });
        let nearest: Channel | null = null;
        let best = Infinity;
        for (const opt of ICONS) {
          const d = Math.hypot(gesture.dx - opt.dx, gesture.dy - opt.dy);
          if (d < best) { best = d; nearest = opt.channel; }
        }
        setHighlight(best < HIT_RADIUS ? nearest : null);
      },
      onPanResponderRelease: (_, gesture) => {
        const dragDist = Math.hypot(gesture.dx, gesture.dy);
        if (expanded && dragDist > MIN_DRAG && highlight) {
          const target = expanded.contact;
          const usable = highlight === 'email' ? !!target.email : !!target.phone;
          if (usable) {
            fireAction(target, highlight);
            setPreferredChannel(target.id, highlight).then(onContactsChange);
          }
        }
        closeRing();
      },
    })
  ).current;

  if (!contacts.length) return null;

  return (
    <View style={s.overlay} pointerEvents="box-none">
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={s.strip}
        contentContainerStyle={s.stripContent}
      >
        {contacts.map(c => (
          <TouchableOpacity
            key={c.id}
            ref={(el) => { bubbleRefs.current[c.id] = el; }}
            style={s.bubble}
            onPress={() => openRing(c)}
            activeOpacity={0.8}
          >
            <Text style={s.bubbleText}>{initials(c.name)}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {expanded && (
        <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
          <TouchableOpacity style={StyleSheet.absoluteFill} activeOpacity={1} onPress={closeRing} />

          {ICONS.map(opt => {
            const disabled = opt.channel === 'email' ? !expanded.contact.email : !expanded.contact.phone;
            const isHighlighted = highlight === opt.channel;
            return (
              <Animated.View
                key={opt.channel}
                pointerEvents="none"
                style={[
                  s.ringIcon,
                  {
                    left: expanded.x + opt.dx - 22,
                    top:  expanded.y + opt.dy - 22,
                    opacity: disabled ? 0.35 : 1,
                    borderColor: isHighlighted ? gold : textDim,
                    backgroundColor: isHighlighted ? 'rgba(201,162,75,0.22)' : bgRaised,
                    transform: [{ scale: ringScale }],
                  },
                ]}
              >
                <Text style={s.ringIconText}>{opt.icon}</Text>
              </Animated.View>
            );
          })}

          <View
            style={[s.centerBubble, { left: expanded.x - 32 + drag.dx, top: expanded.y - 32 + drag.dy }]}
            {...panResponder.panHandlers}
          >
            <Text style={s.bubbleText}>{initials(expanded.contact.name)}</Text>
          </View>
        </View>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  overlay:      { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  strip:        { position: 'absolute', top: 108, left: 0, right: 0, height: 68 },
  stripContent: { paddingHorizontal: 16, gap: 12, alignItems: 'center' },
  // "Distinguished Butler" — slate cards with hairline gold borders, initials in gold. No
  // glow-bloom shadows (hard rule): press/highlight feedback is the hairline itself brightening,
  // handled inline above via borderColor, not a blurred shadow.
  bubble:       { width: 52, height: 52, borderRadius: 26, backgroundColor: bgRaised, borderWidth: 1, borderColor: line, alignItems: 'center', justifyContent: 'center' },
  bubbleText:   { color: gold, fontSize: 16, fontWeight: '700' },
  centerBubble: { position: 'absolute', width: 64, height: 64, borderRadius: 32, backgroundColor: bg, borderWidth: 1.5, borderColor: gold, alignItems: 'center', justifyContent: 'center' },
  ringIcon:     { position: 'absolute', width: 44, height: 44, borderRadius: 22, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  ringIconText: { fontSize: 18 },
});

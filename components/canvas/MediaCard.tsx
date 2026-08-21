import { View, Text, StyleSheet } from 'react-native';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'media' }> };

// Confirmation tile only — Benson never embeds a player, playback happens in the real app.
export function MediaCard({ card }: Props) {
  return (
    <View style={s.card}>
      <Text style={s.icon}>{card.provider === 'spotify' ? '♫' : '▶'}</Text>
      <Text style={s.nowPlaying}>NOW PLAYING ON {card.provider.toUpperCase()}</Text>
      <Text style={s.title} numberOfLines={2}>{card.title}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  card:       { alignItems: 'center', justifyContent: 'center', backgroundColor: PANEL, borderWidth: 1, borderColor: GOLD, borderRadius: 16, paddingVertical: 40, paddingHorizontal: 32, minWidth: 240 },
  icon:       { fontSize: 48, color: GOLD, marginBottom: 12 },
  nowPlaying: { color: MUTED, fontSize: 11, letterSpacing: 2, marginBottom: 8 },
  title:      { color: '#E8E8E8', fontSize: 16, textAlign: 'center', marginTop: 10, paddingHorizontal: 8 },
});

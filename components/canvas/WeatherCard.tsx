import { View, Text, StyleSheet } from 'react-native';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'weather' }> };

export function WeatherCard({ card }: Props) {
  return (
    <View style={s.card}>
      <Text style={s.place}>{card.place}</Text>
      <Text style={s.temp}>{Math.round(card.tempC)}°</Text>
      <Text style={s.desc}>{card.description}</Text>
      {card.precipitation > 0 && (
        <Text style={s.precip}>{card.precipitation} mm precipitation</Text>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  card:   { alignItems: 'center', justifyContent: 'center', backgroundColor: PANEL, borderWidth: 1, borderColor: GOLD, borderRadius: 16, paddingVertical: 40, paddingHorizontal: 32, minWidth: 240 },
  place:  { color: MUTED, fontSize: 14, letterSpacing: 2, textTransform: 'uppercase', marginBottom: 8 },
  temp:   { color: GOLD, fontSize: 64, fontWeight: '300' },
  desc:   { color: '#E8E8E8', fontSize: 16, marginTop: 4, textTransform: 'capitalize' },
  precip: { color: MUTED, fontSize: 12, marginTop: 10 },
});

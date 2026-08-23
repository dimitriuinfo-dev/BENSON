import { View, Text, StyleSheet } from 'react-native';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'note' }> };

const ICONS: Record<Props['card']['noteType'], string> = {
  calendar: '📅',
  reminder: '⏰',
  message:  '✉',
  feedback: '💬',
};

const LABELS: Record<Props['card']['noteType'], string> = {
  calendar: 'CALENDAR',
  reminder: 'REMINDER',
  message:  'MESSAGE',
  feedback: 'FEEDBACK SAVED',
};

export function NoteCard({ card }: Props) {
  return (
    <View style={s.card}>
      <Text style={s.icon}>{ICONS[card.noteType]}</Text>
      <Text style={s.label}>{LABELS[card.noteType]}</Text>
      <Text style={s.summary} numberOfLines={4}>{card.summary}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  card:    { alignItems: 'center', justifyContent: 'center', backgroundColor: PANEL, borderWidth: 1, borderColor: GOLD, borderRadius: 16, paddingVertical: 40, paddingHorizontal: 32, minWidth: 240 },
  icon:    { fontSize: 40, marginBottom: 12 },
  label:   { color: MUTED, fontSize: 11, letterSpacing: 2, marginBottom: 8 },
  summary: { color: '#E8E8E8', fontSize: 16, textAlign: 'center', paddingHorizontal: 8, lineHeight: 22 },
});

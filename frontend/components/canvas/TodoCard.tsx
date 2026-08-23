import { View, Text, StyleSheet, TouchableOpacity, ScrollView } from 'react-native';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = {
  card: Extract<ContentCard, { kind: 'todo' }>;
  onToggle: (id: string) => void;
  onClearCompleted: () => void;
};

export function TodoCard({ card, onToggle, onClearCompleted }: Props) {
  const hasCompleted = card.items.some(i => i.done);

  return (
    <View style={s.wrap}>
      <Text style={s.caption}>SHOPPING / TO-DO</Text>
      <ScrollView style={s.list} contentContainerStyle={{ gap: 8 }}>
        {card.items.map(item => (
          <TouchableOpacity key={item.id} style={s.row} onPress={() => onToggle(item.id)}>
            <View style={[s.checkbox, item.done && s.checkboxDone]}>
              {item.done && <Text style={s.check}>✓</Text>}
            </View>
            <Text style={[s.itemText, item.done && s.itemDone]} numberOfLines={2}>{item.text}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
      {hasCompleted && (
        <TouchableOpacity style={s.clearBtn} onPress={onClearCompleted}>
          <Text style={s.clearTxt}>Clear completed</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  wrap:       { width: '100%', paddingHorizontal: 16, maxHeight: '80%' },
  caption:    { color: MUTED, fontSize: 12, letterSpacing: 1, textAlign: 'center', marginBottom: 10, textTransform: 'uppercase' },
  list:       { backgroundColor: PANEL, borderRadius: 12, borderWidth: 1, borderColor: GOLD, padding: 12 },
  row:        { flexDirection: 'row', alignItems: 'center', gap: 10 },
  checkbox:   { width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, borderColor: MUTED, alignItems: 'center', justifyContent: 'center' },
  checkboxDone: { borderColor: GOLD, backgroundColor: 'rgba(201,168,76,0.25)' },
  check:      { color: GOLD, fontSize: 13, fontWeight: '700' },
  itemText:   { color: '#E8E8E8', fontSize: 15, flex: 1 },
  itemDone:   { color: MUTED, textDecorationLine: 'line-through' },
  clearBtn:   { alignSelf: 'center', marginTop: 12, borderWidth: 1, borderColor: MUTED, paddingVertical: 8, paddingHorizontal: 16, borderRadius: 8 },
  clearTxt:   { color: MUTED, fontSize: 12 },
});

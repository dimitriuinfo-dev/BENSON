import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Linking } from 'react-native';
import { Image } from 'expo-image';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'news' }> };

export function NewsCard({ card }: Props) {
  return (
    <View style={s.wrap}>
      <Text style={s.caption}>News — {card.query}</Text>
      <ScrollView contentContainerStyle={{ gap: 10 }}>
        {card.articles.map((a, i) => (
          <TouchableOpacity key={i} style={s.article} onPress={() => Linking.openURL(a.url).catch(() => {})}>
            {a.image && <Image source={{ uri: a.image }} style={s.thumb} contentFit="cover" />}
            <View style={s.textCol}>
              <Text style={s.title} numberOfLines={2}>{a.title}</Text>
              <Text style={s.snippet} numberOfLines={2}>{a.snippet}</Text>
            </View>
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  wrap:    { width: '100%', paddingHorizontal: 16, maxHeight: '90%' },
  caption: { color: MUTED, fontSize: 12, letterSpacing: 1, textAlign: 'center', marginBottom: 10, textTransform: 'uppercase' },
  article: { flexDirection: 'row', backgroundColor: PANEL, borderRadius: 10, borderLeftWidth: 2, borderLeftColor: GOLD, padding: 10, gap: 10 },
  thumb:   { width: 64, height: 64, borderRadius: 6, backgroundColor: '#1A1A2E' },
  textCol: { flex: 1, justifyContent: 'center' },
  title:   { color: '#E8E8E8', fontSize: 14, fontWeight: '600' },
  snippet: { color: MUTED, fontSize: 12, marginTop: 4 },
});

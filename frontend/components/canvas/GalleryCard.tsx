import { View, Text, StyleSheet, ScrollView, Dimensions } from 'react-native';
import { Image } from 'expo-image';
import { MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'gallery' }> };

const { width } = Dimensions.get('window');
const TILE = (width - 16 * 2 - 8 * 2) / 3;

export function GalleryCard({ card }: Props) {
  return (
    <View style={s.wrap}>
      {card.query && <Text style={s.caption}>{card.source === 'device' ? 'Your photos' : `Photos of ${card.query}`}</Text>}
      <ScrollView contentContainerStyle={s.grid}>
        {card.images.map((img, i) => (
          <Image key={i} source={{ uri: img.url }} style={s.tile} contentFit="cover" />
        ))}
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  wrap:    { width: '100%', paddingHorizontal: 16 },
  caption: { color: MUTED, fontSize: 12, letterSpacing: 1, textAlign: 'center', marginBottom: 10, textTransform: 'uppercase' },
  grid:    { flexDirection: 'row', flexWrap: 'wrap', gap: 8, justifyContent: 'center' },
  tile:    { width: TILE, height: TILE, borderRadius: 8, backgroundColor: '#1A1A2E' },
});

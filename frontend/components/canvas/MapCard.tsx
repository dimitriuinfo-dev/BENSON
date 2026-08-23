import { View, Text, StyleSheet } from 'react-native';
import { Image } from 'expo-image';
import { GOLD, PANEL, MUTED } from '../../lib/theme';
import type { ContentCard } from '../../lib/agents/contentTypes';

type Props = { card: Extract<ContentCard, { kind: 'map' }> };

const ZOOM = 13;
const TILE_SIZE = 256;
const CARD_SIZE = 220;

// Standard slippy-map tile math — a single static OSM tile, no interactive map engine.
function tileCoords(lat: number, lon: number, zoom: number) {
  const n = 2 ** zoom;
  const xFloat = ((lon + 180) / 360) * n;
  const latRad = (lat * Math.PI) / 180;
  const yFloat = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n;
  const x = Math.floor(xFloat);
  const y = Math.floor(yFloat);
  return { x, y, pxX: (xFloat - x) * TILE_SIZE, pxY: (yFloat - y) * TILE_SIZE };
}

export function MapCard({ card }: Props) {
  const { x, y, pxX, pxY } = tileCoords(card.latitude, card.longitude, ZOOM);
  const scale = CARD_SIZE / TILE_SIZE;

  return (
    <View style={s.wrap}>
      <View style={s.mapBox}>
        <Image
          source={{ uri: `https://tile.openstreetmap.org/${ZOOM}/${x}/${y}.png` }}
          style={{ width: CARD_SIZE, height: CARD_SIZE }}
          contentFit="cover"
        />
        <View style={[s.pin, { left: pxX * scale - 8, top: pxY * scale - 16 }]} />
      </View>
      <View style={s.caption}>
        <Text style={s.destination} numberOfLines={1}>{card.destination}</Text>
        {card.distanceKm !== undefined && (
          <Text style={s.distance}>{card.distanceKm} km away</Text>
        )}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  wrap:        { alignItems: 'center' },
  mapBox:      { width: CARD_SIZE, height: CARD_SIZE, borderRadius: 12, borderWidth: 1, borderColor: GOLD, backgroundColor: PANEL, overflow: 'hidden' },
  pin:         { position: 'absolute', width: 16, height: 16, borderRadius: 8, backgroundColor: GOLD, borderWidth: 2, borderColor: '#0D1B2A' },
  caption:     { alignItems: 'center', marginTop: 12, backgroundColor: PANEL, borderRadius: 10, paddingVertical: 10, paddingHorizontal: 16 },
  destination: { color: '#E8E8E8', fontSize: 16, fontWeight: '600' },
  distance:    { color: MUTED, fontSize: 12, marginTop: 4 },
});

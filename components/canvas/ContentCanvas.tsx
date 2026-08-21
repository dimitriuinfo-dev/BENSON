import { View, StyleSheet } from 'react-native';
import type { ContentCard } from '../../lib/agents/contentTypes';
import { IdleCard } from './IdleCard';
import { WeatherCard } from './WeatherCard';
import { MediaCard } from './MediaCard';
import { MapCard } from './MapCard';
import { GalleryCard } from './GalleryCard';
import { NewsCard } from './NewsCard';
import { NoteCard } from './NoteCard';
import { TodoCard } from './TodoCard';

type Props = {
  card: ContentCard | null;
  pulsing: boolean;
  onToggleTodo?: (id: string) => void;
  onClearCompletedTodo?: () => void;
};

// The main screen's content area — shows the active structured card centered,
// or the idle BENSON seal when there's nothing to display (plain conversation).
export function ContentCanvas({ card, pulsing, onToggleTodo, onClearCompletedTodo }: Props) {
  return (
    <View style={s.canvas}>
      {!card && <IdleCard pulsing={pulsing} />}
      {card?.kind === 'weather' && <WeatherCard card={card} />}
      {card?.kind === 'media'   && <MediaCard card={card} />}
      {card?.kind === 'map'     && <MapCard card={card} />}
      {card?.kind === 'gallery' && <GalleryCard card={card} />}
      {card?.kind === 'news'    && <NewsCard card={card} />}
      {card?.kind === 'note'    && <NoteCard card={card} />}
      {card?.kind === 'todo'    && (
        <TodoCard card={card} onToggle={onToggleTodo ?? (() => {})} onClearCompleted={onClearCompletedTodo ?? (() => {})} />
      )}
    </View>
  );
}

const s = StyleSheet.create({
  canvas: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});

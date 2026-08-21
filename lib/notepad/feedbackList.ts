import AsyncStorage from '@react-native-async-storage/async-storage';

export type FeedbackItem = { id: string; text: string; createdAt: string };

const FEEDBACK_KEY = 'benson_feedback_list_v1';

export async function loadFeedbackItems(): Promise<FeedbackItem[]> {
  try {
    const raw = await AsyncStorage.getItem(FEEDBACK_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export async function addFeedbackItem(text: string): Promise<FeedbackItem[]> {
  const items = await loadFeedbackItems();
  const updated = [...items, { id: Date.now().toString(), text, createdAt: new Date().toISOString() }];
  await AsyncStorage.setItem(FEEDBACK_KEY, JSON.stringify(updated));
  return updated;
}

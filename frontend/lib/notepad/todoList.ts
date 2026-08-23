import AsyncStorage from '@react-native-async-storage/async-storage';

export type TodoItem = { id: string; text: string; done: boolean };

const TODO_KEY = 'benson_todo_list_v1';

export async function loadTodoItems(): Promise<TodoItem[]> {
  try {
    const raw = await AsyncStorage.getItem(TODO_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

async function saveTodoItems(items: TodoItem[]): Promise<void> {
  await AsyncStorage.setItem(TODO_KEY, JSON.stringify(items));
}

export async function addTodoItem(text: string): Promise<TodoItem[]> {
  const items = await loadTodoItems();
  const updated = [...items, { id: Date.now().toString(), text, done: false }];
  await saveTodoItems(updated);
  return updated;
}

export async function toggleTodoItem(id: string): Promise<TodoItem[]> {
  const items = await loadTodoItems();
  const updated = items.map(i => (i.id === id ? { ...i, done: !i.done } : i));
  await saveTodoItems(updated);
  return updated;
}

export async function clearCompletedTodoItems(): Promise<TodoItem[]> {
  const items = await loadTodoItems();
  const updated = items.filter(i => !i.done);
  await saveTodoItems(updated);
  return updated;
}

import AsyncStorage from '@react-native-async-storage/async-storage';

// Free-form drag offsets for the bottom Dashboard panel (components/dashboard/BottomDashboard.tsx).
// Each draggable block (menu, radial indicator, ACTIVE card stack) keeps its normal flex position
// as a base and this stores just the (dx, dy) the user dragged it by — a sticker-style offset,
// not a full layout engine — so the panel structure never breaks, it just gets nudged around.
export type Vec2 = { x: number; y: number };
export type DashboardLayout = Record<string, Vec2>;

const KEY = 'benson_dashboard_layout_v1';

export async function loadDashboardLayout(): Promise<DashboardLayout> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export async function saveDashboardPosition(id: string, pos: Vec2): Promise<void> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const layout: DashboardLayout = raw ? JSON.parse(raw) : {};
    layout[id] = pos;
    await AsyncStorage.setItem(KEY, JSON.stringify(layout));
  } catch {}
}

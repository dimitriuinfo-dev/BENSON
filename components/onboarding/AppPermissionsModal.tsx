import { useEffect, useState } from 'react';
import { Modal, View, Text, TouchableOpacity, FlatList, Image, Switch, StyleSheet, ActivityIndicator } from 'react-native';
import * as Haptics from 'expo-haptics';
import { getInstalledApps, type InstalledApp } from 'benson-app-registry';
import { loadAppPermissions, saveAppPermissions, setOnboardingDone, type AppPermission } from '../../lib/appPermissions';
import { GOLD, NAVY, MUTED } from '../../lib/theme';

function tap() {
  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(() => {});
}

// BENSON 4 — lets the user pick which apps on the device BENSON is allowed to open/operate.
// Shown once automatically (app/index.tsx, phase === 'chat' + !isOnboardingDone()), and
// reachable anytime after from Settings ("App Permissions" row).
export function AppPermissionsModal({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const [apps, setApps] = useState<AppPermission[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    setLoading(true);
    (async () => {
      const [installed, saved] = await Promise.all([getInstalledApps(), loadAppPermissions()]);
      if (cancelled) return;
      const savedByPkg = new Map(saved.map(a => [a.packageName, a.allowed]));
      const merged: AppPermission[] = installed.map((app: InstalledApp) => ({
        ...app,
        allowed: savedByPkg.get(app.packageName) ?? false,
      }));
      setApps(merged);
      setLoading(false);
    })();
    return () => { cancelled = true; };
  }, [visible]);

  function toggle(packageName: string) {
    tap();
    setApps(prev => prev.map(a => a.packageName === packageName ? { ...a, allowed: !a.allowed } : a));
  }

  function setAll(allowed: boolean) {
    tap();
    setApps(prev => prev.map(a => ({ ...a, allowed })));
  }

  async function handleSave() {
    tap();
    await saveAppPermissions(apps);
    await setOnboardingDone();
    onClose();
  }

  const allowedCount = apps.filter(a => a.allowed).length;

  return (
    <Modal visible={visible} animationType="slide" transparent>
      <View style={s.bg}>
        <View style={s.box}>
          <View style={s.header}>
            <Text style={s.title}>APP PERMISSIONS</Text>
            <TouchableOpacity hitSlop={12} onPress={() => { tap(); onClose(); }}
              accessibilityLabel="Close app permissions" accessibilityRole="button">
              <Text style={s.closeX}>✕</Text>
            </TouchableOpacity>
          </View>
          <Text style={s.subtitle}>
            Choose which apps BENSON may open or act in. Nothing is enabled by default —
            you can change this anytime from here.
          </Text>

          <View style={s.quickRow}>
            <TouchableOpacity style={s.quickBtn} onPress={() => setAll(true)}
              accessibilityLabel="Select all apps" accessibilityRole="button">
              <Text style={s.quickBtnText}>Select all</Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.quickBtn} onPress={() => setAll(false)}
              accessibilityLabel="Clear all apps" accessibilityRole="button">
              <Text style={s.quickBtnText}>Clear all</Text>
            </TouchableOpacity>
            <Text style={s.countText}>{allowedCount}/{apps.length} allowed</Text>
          </View>

          {loading ? (
            <ActivityIndicator color={GOLD} style={{ marginTop: 40 }} />
          ) : (
            <FlatList
              data={apps}
              keyExtractor={item => item.packageName}
              renderItem={({ item }) => (
                <View style={s.row}>
                  <Image source={{ uri: item.icon }} style={s.icon} />
                  <View style={{ flex: 1 }}>
                    <Text style={s.appName} numberOfLines={1}>{item.appName}</Text>
                    <Text style={s.packageName} numberOfLines={1}>{item.packageName}</Text>
                  </View>
                  <Switch
                    value={item.allowed}
                    onValueChange={() => toggle(item.packageName)}
                    trackColor={{ true: GOLD, false: MUTED }}
                    thumbColor={NAVY}
                    accessibilityLabel={`Allow ${item.appName}`}
                    accessibilityRole="switch"
                  />
                </View>
              )}
            />
          )}

          <TouchableOpacity style={s.saveBtn} onPress={handleSave}
            accessibilityLabel="Save app permissions" accessibilityRole="button">
            <Text style={s.saveBtnText}>SAVE</Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
}

const s = StyleSheet.create({
  bg:    { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  box:   { backgroundColor: NAVY, borderTopWidth: 1, borderColor: GOLD, padding: 20, height: '82%' },
  header:{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  title: { color: GOLD, fontSize: 16, fontWeight: '700', letterSpacing: 2 },
  closeX:{ color: MUTED, fontSize: 20 },
  subtitle: { color: MUTED, fontSize: 12, lineHeight: 17, marginBottom: 12 },

  quickRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 8 },
  quickBtn: { borderWidth: 1, borderColor: GOLD, borderRadius: 6, paddingVertical: 5, paddingHorizontal: 10 },
  quickBtnText: { color: GOLD, fontSize: 11 },
  countText: { color: MUTED, fontSize: 11, marginLeft: 'auto' },

  row: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#1A1A2E' },
  icon: { width: 32, height: 32, borderRadius: 7 },
  appName: { color: '#E8E8E8', fontSize: 13, fontWeight: '600' },
  packageName: { color: MUTED, fontSize: 10 },

  saveBtn: { backgroundColor: GOLD, padding: 14, alignItems: 'center', marginTop: 10 },
  saveBtnText: { color: NAVY, fontWeight: '700', letterSpacing: 2, fontSize: 13 },
});

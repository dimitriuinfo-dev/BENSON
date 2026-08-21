import { useEffect, useRef, useState, useCallback } from 'react';
import { Modal, View, Text, TouchableOpacity, StyleSheet, AppState, PermissionsAndroid, ScrollView } from 'react-native';
import * as Haptics from 'expo-haptics';
import * as Notifications from 'expo-notifications';
import * as Contacts from 'expo-contacts';
import * as Location from 'expo-location';
import { requestMicPermission, checkMicPermission, speakNow } from '../../lib/agents/voiceAgent';
import { hasCallPhonePermission } from 'benson-app-registry';
import { getConnectionState, openAccessibilitySettings } from 'benson-accessibility';
import { isIgnoringBatteryOptimizations, requestIgnoreBatteryOptimizations } from 'benson-foreground-service';
import { isEnabled as isNotifListenerEnabled, openNotificationListenerSettings } from 'benson-notification-listener';
import { hasOverlayPermission, requestOverlayPermission } from 'benson-overlay';
import { GOLD, NAVY, MUTED, RED, GREEN, text as TEXT } from '../../lib/theme';
import { setSetupWizardDone } from '../../lib/setupWizard';

function tap() {
  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(() => {});
}

type Status = 'unknown' | 'checking' | 'granted' | 'missing';

type Step = {
  id: string;
  title: string;
  why: string;
  critical: boolean;
  check: () => boolean | Promise<boolean>;
  action: () => unknown;
  // true when action() itself navigates away (Settings screen) — re-check happens on AppState
  // 'active' return rather than immediately after action() resolves.
  navigatesAway: boolean;
};

async function ensureCallPhonePermission(): Promise<void> {
  await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CALL_PHONE, {
    title: 'BENSON',
    message: 'BENSON are nevoie de permisiunea de a suna direct, ca să poată apela contactele fără să deschidă telefonul manual.',
    buttonPositive: 'OK',
    buttonNegative: 'Nu, mulțumesc',
  });
}

const STEPS: Step[] = [
  {
    id: 'mic',
    title: 'Microfon',
    why: 'Am nevoie de microfon ca să te aud și să răspund la comenzile tale vocale. Este esențial pentru tot ce fac.',
    critical: true,
    check: checkMicPermission,
    action: requestMicPermission,
    navigatesAway: false,
  },
  {
    id: 'notifications',
    title: 'Notificări',
    why: 'Notificările îmi permit să-ți arăt starea mea și să te anunț discret, chiar și când telefonul e blocat.',
    critical: false,
    check: async () => (await Notifications.getPermissionsAsync()).status === 'granted',
    action: async () => { await Notifications.requestPermissionsAsync(); },
    navigatesAway: false,
  },
  {
    id: 'contacts',
    title: 'Contacte',
    why: 'Am nevoie de acces la contacte ca să pot suna sau scrie persoanelor pe care le numești, fără să caute tu manual.',
    critical: false,
    check: async () => (await Contacts.getPermissionsAsync()).status === 'granted',
    action: async () => { await Contacts.requestPermissionsAsync(); },
    navigatesAway: false,
  },
  {
    id: 'location',
    title: 'Locație',
    why: 'Locația îmi permite să navighez pentru tine și să te anunț când te apropii de o graniță fără vinietă.',
    critical: false,
    check: async () => {
      const fg = await Location.getForegroundPermissionsAsync();
      if (fg.status !== 'granted') return false;
      const bg = await Location.getBackgroundPermissionsAsync();
      return bg.status === 'granted';
    },
    action: async () => {
      const fg = await Location.requestForegroundPermissionsAsync();
      if (fg.status === 'granted') {
        await Location.requestBackgroundPermissionsAsync();
      }
    },
    navigatesAway: false,
  },
  {
    id: 'phone',
    title: 'Telefon / Apeluri',
    why: 'Cu această permisiune pot suna direct contactele tale, fără să deschid manual aplicația de telefon.',
    critical: false,
    check: hasCallPhonePermission,
    action: ensureCallPhonePermission,
    navigatesAway: false,
  },
  {
    id: 'accessibility',
    title: 'Serviciul de Accesibilitate',
    why: 'Acesta îmi permite să citesc ecranul și să apăs butoane în alte aplicații, precum WhatsApp sau Waze. Va trebui să mă activezi manual din lista de servicii.\n\nDacă butonul apare gri și nu poate fi apăsat: e o restricție a Android pentru aplicații instalate din afara Play Store. Se deblochează din Setări → Aplicații → BENSON → meniul cu trei puncte (⋮) din dreapta sus → „Permite setări restricționate".',
    critical: true,
    check: async () => (await getConnectionState()) === 'enabled_connected',
    action: openAccessibilitySettings,
    navigatesAway: true,
  },
  {
    id: 'overlay',
    title: 'Afișare peste alte aplicații',
    why: 'Cu acest permis pot arăta bula plutitoare și inelul de trezire peste orice altă aplicație ai avea deschisă, fără să te întrerup.',
    critical: false,
    check: hasOverlayPermission,
    action: requestOverlayPermission,
    navigatesAway: true,
  },
  {
    id: 'battery',
    title: 'Optimizare Baterie',
    why: 'Fără această excepție, Android mă poate opri în fundal și nu te voi mai auzi când mă strigi.',
    critical: true,
    check: isIgnoringBatteryOptimizations,
    action: requestIgnoreBatteryOptimizations,
    navigatesAway: true,
  },
  {
    id: 'notif_access',
    title: 'Acces la Notificări (WhatsApp)',
    why: 'Acest acces îmi va permite, în curând, să citesc mesajele WhatsApp primite. Poți sări peste acest pas acum.',
    critical: false,
    check: isNotifListenerEnabled,
    action: openNotificationListenerSettings,
    navigatesAway: true,
  },
];

export function SetupWizard({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const [stepIndex, setStepIndex] = useState(0);
  const [statuses, setStatuses] = useState<Record<string, Status>>({});
  const statusesRef = useRef(statuses);
  statusesRef.current = statuses;
  const stepIndexRef = useRef(stepIndex);
  stepIndexRef.current = stepIndex;
  const onDashboard = stepIndex >= STEPS.length;

  const runCheck = useCallback(async (step: Step) => {
    setStatuses(prev => ({ ...prev, [step.id]: 'checking' }));
    const granted = await Promise.resolve(step.check()).catch(() => false);
    setStatuses(prev => ({ ...prev, [step.id]: granted ? 'granted' : 'missing' }));
    return granted;
  }, []);

  // Re-check whichever step is current (or every step, on the dashboard) whenever the app comes
  // back to foreground — the only way to know whether the user actually granted something in the
  // system Settings screen we just deep-linked them to.
  useEffect(() => {
    if (!visible) return;
    const sub = AppState.addEventListener('change', (next) => {
      if (next !== 'active') return;
      if (stepIndexRef.current >= STEPS.length) {
        STEPS.forEach(s => runCheck(s));
      } else {
        runCheck(STEPS[stepIndexRef.current]);
      }
    });
    return () => sub.remove();
  }, [visible, runCheck]);

  // Speak + check the current step's explanation each time we land on it.
  useEffect(() => {
    if (!visible) return;
    if (onDashboard) {
      const criticalMissing = STEPS.filter(s => s.critical && statusesRef.current[s.id] !== 'granted');
      const summary = criticalMissing.length === 0
        ? 'Sunt gata.'
        : `Îmi lipsește încă: ${criticalMissing.map(s => s.title).join(', ')}.`;
      speakNow(summary, { language: 'ro-RO' });
      return;
    }
    const step = STEPS[stepIndex];
    speakNow(step.why, { language: 'ro-RO' });
    runCheck(step);
  }, [visible, stepIndex, onDashboard, runCheck]);

  async function handleAction(step: Step) {
    tap();
    await step.action();
    if (!step.navigatesAway) {
      await runCheck(step);
    }
  }

  function next() {
    tap();
    setStepIndex(i => i + 1);
  }

  async function finish() {
    tap();
    await setSetupWizardDone();
    onClose();
  }

  function redo(step: Step) {
    tap();
    handleAction(step);
  }

  if (!visible) return null;

  return (
    <Modal visible={visible} animationType="slide" transparent>
      <View style={s.bg}>
        <View style={s.box}>
          {!onDashboard ? (
            <StepScreen
              step={STEPS[stepIndex]}
              index={stepIndex}
              total={STEPS.length}
              status={statuses[STEPS[stepIndex].id] ?? 'unknown'}
              onAction={() => handleAction(STEPS[stepIndex])}
              onNext={next}
            />
          ) : (
            <Dashboard statuses={statuses} onRedo={redo} onFinish={finish} />
          )}
        </View>
      </View>
    </Modal>
  );
}

function StepScreen({ step, index, total, status, onAction, onNext }: {
  step: Step; index: number; total: number; status: Status;
  onAction: () => void; onNext: () => void;
}) {
  return (
    <View style={{ flex: 1 }}>
      <Text style={s.progress}>PASUL {index + 1} DIN {total}{step.critical ? ' · ESENȚIAL' : ' · OPȚIONAL'}</Text>
      <Text style={s.title}>{step.title}</Text>
      <Text style={s.why}>{step.why}</Text>

      <StatusBadge status={status} />

      <TouchableOpacity style={s.actionBtn} onPress={onAction}
        accessibilityLabel={`Configurează ${step.title}`} accessibilityRole="button">
        <Text style={s.actionBtnText}>DESCHIDE</Text>
      </TouchableOpacity>

      <TouchableOpacity style={s.nextBtn} onPress={onNext}
        accessibilityLabel="Continuă la pasul următor" accessibilityRole="button">
        <Text style={s.nextBtnText}>{index + 1 < total ? 'CONTINUĂ →' : 'VEZI STATUSUL →'}</Text>
      </TouchableOpacity>
    </View>
  );
}

function StatusBadge({ status }: { status: Status }) {
  const label = status === 'granted' ? 'ACTIVAT' : status === 'checking' ? 'SE VERIFICĂ...' : status === 'missing' ? 'NEACTIVAT' : '—';
  const color = status === 'granted' ? GREEN : status === 'missing' ? RED : MUTED;
  return (
    <View style={[s.badge, { borderColor: color }]}>
      <Text style={[s.badgeText, { color }]}>{label}</Text>
    </View>
  );
}

function Dashboard({ statuses, onRedo, onFinish }: {
  statuses: Record<string, Status>; onRedo: (step: Step) => void; onFinish: () => void;
}) {
  return (
    <View style={{ flex: 1 }}>
      <Text style={s.title}>STATUS BENSON</Text>
      <ScrollView style={{ flex: 1 }}>
        {STEPS.map(step => {
          const st = statuses[step.id] ?? 'unknown';
          const color = st === 'granted' ? GREEN : RED;
          return (
            <View key={step.id} style={s.dashRow}>
              <View style={{ flex: 1 }}>
                <Text style={[s.dashLabel, { color }]}>
                  {st === 'granted' ? '● ' : '○ '}{step.title}{step.critical ? ' *' : ''}
                </Text>
              </View>
              {st !== 'granted' && (
                <TouchableOpacity style={s.redoBtn} onPress={() => onRedo(step)}
                  accessibilityLabel={`Repetă ${step.title}`} accessibilityRole="button">
                  <Text style={s.redoBtnText}>REPETĂ</Text>
                </TouchableOpacity>
              )}
            </View>
          );
        })}
      </ScrollView>
      <Text style={s.factLine}>* esențial — restul sunt opționale, Benson funcționează și fără ele.</Text>
      <TouchableOpacity style={s.actionBtn} onPress={onFinish}
        accessibilityLabel="Finalizează configurarea" accessibilityRole="button">
        <Text style={s.actionBtnText}>GATA</Text>
      </TouchableOpacity>
    </View>
  );
}

const s = StyleSheet.create({
  bg:  { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  box: { backgroundColor: NAVY, borderTopWidth: 1, borderColor: GOLD, padding: 20, height: '78%' },

  progress: { color: MUTED, fontSize: 11, letterSpacing: 1.5, marginBottom: 6 },
  title:    { color: GOLD, fontSize: 18, fontWeight: '700', letterSpacing: 1, marginBottom: 10 },
  why:      { color: TEXT, fontSize: 14, lineHeight: 20, marginBottom: 18 },

  badge: { alignSelf: 'flex-start', borderWidth: 1, borderRadius: 6, paddingVertical: 5, paddingHorizontal: 12, marginBottom: 24 },
  badgeText: { fontSize: 12, fontWeight: '700', letterSpacing: 1 },

  actionBtn: { backgroundColor: GOLD, padding: 14, alignItems: 'center', marginTop: 'auto' },
  actionBtnText: { color: NAVY, fontWeight: '700', letterSpacing: 2, fontSize: 13 },
  nextBtn: { padding: 14, alignItems: 'center', marginTop: 10 },
  nextBtnText: { color: MUTED, fontWeight: '600', letterSpacing: 1, fontSize: 12 },

  dashRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: 'rgba(201,162,75,0.15)' },
  dashLabel: { fontSize: 14, fontWeight: '600' },
  redoBtn: { borderWidth: 1, borderColor: GOLD, borderRadius: 6, paddingVertical: 4, paddingHorizontal: 10 },
  redoBtnText: { color: GOLD, fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  factLine: { color: MUTED, fontSize: 11, lineHeight: 15, marginVertical: 10 },
});

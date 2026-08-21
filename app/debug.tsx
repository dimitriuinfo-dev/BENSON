// BENSON Action Engine — Debug Panel.
// Deliberately plain/unstyled: this is a diagnostic screen for live-testing the Action Engine
// (Module 6, App Governance Engine), not a designed part of the product UI. Shows the exact
// fields the orchestrator directive requires, sourced from the Governance Engine's own
// in-memory log — no new styling, no touches to BensonMainScreen or any existing design file.

import { useCallback, useEffect, useState } from 'react';
import { View, Text, ScrollView, TouchableOpacity, TextInput } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import {
  isAudioDiagnosticsEnabled, setAudioDiagnosticsEnabled, getRecoveryEventsLog,
} from 'benson-foreground-service';
import { getLastActionLogs, type GovernanceLogEntry } from '../src/core/action-engine';
import { runMission, resumePendingTask, getLastMissionPlan, getRecentEvents, type MissionPlan, type OrchestratorEvent } from '../src/core/orchestrator';
import { getActiveMission, confirmActiveMission, cancelActiveMission, resolveActiveMissionFromUtterance, type Mission } from '../src/core/mission';
import type { TrustedContact } from '../src/core/contacts';

// Same stand-in contact BENSON's live voice pipeline uses (app/index.tsx's TEST_CONTACTS) until
// real contact memory is wired in — duplicated here rather than exported from a screen component
// file, since this box exists only to drive real end-to-end tests through the real pipeline.
const TEST_CONTACTS: TrustedContact[] = [
  { id: 'test-hannah', displayName: 'Hannah', aliases: ['Hannah'], phoneNumbers: ['+49123456789'] },
];

const YES_PATTERN = /\b(da|yes|sigur|sure|ok|okay)\b/i;

const DEBUG_KEEP_AWAKE_TAG = 'benson-debug-panel';

function fieldLine(label: string, value: string | number | undefined): string {
  return `${label}: ${value === undefined || value === '' ? '—' : value}`;
}

function MissionPanel({ plan, events }: { plan: MissionPlan | undefined; events: OrchestratorEvent[] }) {
  return (
    <View style={{ borderBottomWidth: 2, borderColor: '#4da6ff', paddingVertical: 8, marginBottom: 8 }}>
      <Text style={{ color: '#4da6ff', fontSize: 16, marginBottom: 4 }}>MISSION ORCHESTRATOR</Text>
      {!plan ? (
        <Text style={{ color: '#888' }}>No mission planned yet.</Text>
      ) : (
        <>
          <Text style={{ color: '#fff' }}>{fieldLine('LAST MISSION ID', plan.id)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('MISSION STATUS', plan.status)}</Text>
          <Text style={{ color: '#fff' }}>GOALS: {plan.goals.map((g) => `${g.type}(${g.confidence})`).join(', ') || '—'}</Text>
          {plan.tasks.map((task) => (
            <Text key={task.id} style={{ color: '#fff' }}>
              TASK: {task.type} [{task.status}] {task.resultMessage ?? task.errorMessage ?? ''}
            </Text>
          ))}
        </>
      )}
      <Text style={{ color: '#4da6ff', marginTop: 6 }}>RECENT EVENTS</Text>
      {events.length === 0 ? (
        <Text style={{ color: '#888' }}>No events yet.</Text>
      ) : (
        events.slice(0, 10).map((event) => (
          <Text key={event.id} style={{ color: '#aaa' }}>
            {new Date(event.timestamp).toLocaleTimeString()} — {event.type}
          </Text>
        ))
      )}
    </View>
  );
}

function LogEntryView({ entry }: { entry: GovernanceLogEntry }) {
  return (
    <View style={{ borderBottomWidth: 1, borderColor: '#444', paddingVertical: 8 }}>
      <Text style={{ color: '#fff' }}>{fieldLine('RAW TRANSCRIPT', entry.rawTranscript)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('NORMALIZED', entry.normalizedText)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('CLEANED', entry.cleanedText)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('INTENT', entry.intent)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('CONFIDENCE', entry.confidence)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('TARGET APP', entry.targetApp)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('PACKAGE', entry.packageName)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('DESTINATION', entry.destination)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('CONTACT', entry.contact)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('MESSAGE', entry.message)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('ACTION', entry.action)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('PERMISSION', entry.permission)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('RESULT', entry.result)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('ERROR', entry.error)}</Text>
      <Text style={{ color: '#fff' }}>{fieldLine('TIMESTAMP', new Date(entry.timestamp).toLocaleTimeString())}</Text>
    </View>
  );
}

function GovernedMissionPanel({ mission }: { mission: Mission | null }) {
  return (
    <View style={{ borderBottomWidth: 2, borderColor: '#c9a84c', paddingVertical: 8, marginBottom: 8 }}>
      <Text style={{ color: '#c9a84c', fontSize: 16, marginBottom: 4 }}>MISSION GOVERNANCE (Waze/WhatsApp)</Text>
      {!mission ? (
        <Text style={{ color: '#888' }}>No governed mission active.</Text>
      ) : (
        <>
          <Text style={{ color: '#fff' }}>{fieldLine('MISSION ID', mission.id)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('STATE', mission.state)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('TOOL', mission.request.tool)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('ACTION', mission.request.action)}</Text>
          <Text style={{ color: '#fff' }}>PARAMS: {JSON.stringify(mission.request.params)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('REQUIRES CONFIRMATION', String(mission.request.requiresConfirmation))}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('VALIDATION STATUS', mission.request.validationStatus)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('REASON', mission.reason)}</Text>
          <Text style={{ color: '#fff' }}>{fieldLine('USER MESSAGE', mission.userMessage)}</Text>
        </>
      )}
    </View>
  );
}

// Test-only command box (Phase 1 verification) — routes through the SAME functions the live
// voice pipeline uses (runMission/resumePendingTask/confirmActiveMission/...), never a mock or a
// second parser. Diagnostic screen only, no product UI touched.
function TestCommandBox({ onResult }: { onResult: () => void }) {
  const [text, setText] = useState('');
  const [lastReply, setLastReply] = useState('');
  const [pendingTask, setPendingTask] = useState<{ plan: MissionPlan; taskIndex: number } | null>(null);
  const [sending, setSending] = useState(false);

  async function send() {
    // Guards against rapid repeated taps firing overlapping runMission/confirmActiveMission
    // calls — confirmed live (2026-07-14) that multiple fast "da" taps could each spawn their
    // own mission/confirmation cycle, leaving stray state (e.g. an unrelated PREPARE_CALL
    // confirmation) mixed in with the one actually being tested.
    if (sending) return;
    const msg = text.trim();
    if (!msg) return;
    setText('');
    setSending(true);

    try {
    if (pendingTask) {
      const resumeResult = await resumePendingTask(pendingTask, TEST_CONTACTS);
      setPendingTask(resumeResult.pendingTask ?? null);
      setLastReply(resumeResult.message);
      onResult();
      return;
    }

    const governed = getActiveMission();
    if (governed?.state === 'WaitingConfirmation') {
      if (YES_PATTERN.test(msg)) {
        const outcome = await confirmActiveMission(TEST_CONTACTS);
        setLastReply(outcome?.message ?? '(no outcome)');
      } else {
        await cancelActiveMission();
        setLastReply('Cancelled the pending WhatsApp/Waze confirmation.');
      }
      onResult();
      return;
    }
    if (governed?.state === 'WaitingUser') {
      const resolved = await resolveActiveMissionFromUtterance(msg);
      if (resolved) {
        setLastReply(resolved.message);
        onResult();
        return;
      }
    }

    const result = await runMission(msg, { source: 'text', contacts: TEST_CONTACTS });
    if (!result.handled) {
      setLastReply('(Mission Orchestrator did not recognize this — would fall through to Claude in the live app.)');
      onResult();
      return;
    }
    setLastReply(result.message);
    setPendingTask(result.pendingTask ?? null);
    onResult();
    } finally {
      setSending(false);
    }
  }

  return (
    <View style={{ borderBottomWidth: 2, borderColor: '#4da6ff', paddingVertical: 8, marginBottom: 8 }}>
      <Text style={{ color: '#4da6ff', fontSize: 16, marginBottom: 4 }}>TEST COMMAND (Phase 1 verification only)</Text>
      <TextInput
        value={text}
        onChangeText={setText}
        onSubmitEditing={send}
        placeholder="e.g. navigate to Munich Airport"
        placeholderTextColor="#888"
        style={{ color: '#fff', borderWidth: 1, borderColor: '#444', padding: 8, marginBottom: 6 }}
      />
      <TouchableOpacity onPress={send} disabled={sending} style={{ paddingVertical: 6, opacity: sending ? 0.4 : 1 }}
        accessibilityLabel="Send test command" accessibilityRole="button">
        <Text style={{ color: '#4da6ff' }}>{sending ? 'Sending…' : 'Send'}</Text>
      </TouchableOpacity>
      <Text style={{ color: '#fff', marginTop: 4 }}>REPLY: {lastReply || '—'}</Text>
    </View>
  );
}

// BENSON_AUDIO diagnostics toggle + Guardian recovery-events readout — native-backed (see
// AudioDiag.kt / BensonWatchdogReceiver.kt), not a duplicate JS-only flag.
function AudioDiagnosticsPanel() {
  const [enabled, setEnabledState] = useState<boolean | null>(null);
  const [recoveryEvents, setRecoveryEvents] = useState('');

  const refresh = useCallback(() => {
    Promise.resolve(isAudioDiagnosticsEnabled()).then(setEnabledState).catch(() => {});
    try { setRecoveryEvents(getRecoveryEventsLog()); } catch {}
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  async function toggle() {
    const next = !enabled;
    await setAudioDiagnosticsEnabled(next);
    setEnabledState(next);
  }

  return (
    <View style={{ borderBottomWidth: 2, borderColor: '#4da6ff', paddingVertical: 8, marginBottom: 8 }}>
      <Text style={{ color: '#4da6ff', fontSize: 16, marginBottom: 4 }}>AUDIO DIAGNOSTICS</Text>
      <Text style={{ color: '#fff' }}>AUDIO DIAGNOSTICS: {enabled === null ? '…' : enabled ? 'ON' : 'OFF'}</Text>
      <TouchableOpacity onPress={toggle} style={{ paddingVertical: 6 }}
        accessibilityLabel="Toggle audio diagnostics" accessibilityRole="button">
        <Text style={{ color: '#4da6ff' }}>Toggle</Text>
      </TouchableOpacity>
      <Text style={{ color: '#4da6ff', marginTop: 6 }}>GUARDIAN RECOVERY EVENTS</Text>
      <Text style={{ color: '#fff' }}>{recoveryEvents || 'none recorded'}</Text>
    </View>
  );
}

export default function DebugScreen() {
  const [logs, setLogs] = useState<GovernanceLogEntry[]>([]);
  const [missionPlan, setMissionPlan] = useState<MissionPlan | undefined>(undefined);
  const [events, setEvents] = useState<OrchestratorEvent[]>([]);
  const [governedMission, setGovernedMission] = useState<Mission | null>(null);

  const refresh = useCallback(() => {
    setLogs(getLastActionLogs());
    setMissionPlan(getLastMissionPlan());
    setEvents(getRecentEvents());
    setGovernedMission(getActiveMission());
  }, []);

  useFocusEffect(
    useCallback(() => {
      refresh();
      activateKeepAwakeAsync(DEBUG_KEEP_AWAKE_TAG).catch(() => {});
      return () => deactivateKeepAwake(DEBUG_KEEP_AWAKE_TAG);
    }, [refresh]),
  );

  return (
    <View style={{ flex: 1, backgroundColor: '#000', paddingTop: 48, paddingHorizontal: 12 }}>
      <TouchableOpacity onPress={refresh} style={{ paddingVertical: 8 }}
        accessibilityLabel="Refresh debug panel" accessibilityRole="button">
        <Text style={{ color: '#4da6ff', fontSize: 16 }}>Refresh</Text>
      </TouchableOpacity>
      <ScrollView>
        <AudioDiagnosticsPanel />
        <TestCommandBox onResult={refresh} />
        <GovernedMissionPanel mission={governedMission} />
        <MissionPanel plan={missionPlan} events={events} />
        {logs.length === 0 ? (
          <Text style={{ color: '#888' }}>No commands logged yet.</Text>
        ) : (
          logs.map((entry, i) => <LogEntryView key={i} entry={entry} />)
        )}
      </ScrollView>
    </View>
  );
}

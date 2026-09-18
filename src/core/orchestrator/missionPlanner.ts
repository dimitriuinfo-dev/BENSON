// BENSON Mission Orchestrator — Mission Planner.
// Goal[] -> MissionPlan. Decomposition only — never executes anything, never asks anything
// (that's the orchestrator's job when it walks the plan). Compound goal arrays produce multiple
// tasks; sensitive tasks are marked requiresConfirmation=true for the orchestrator to gate on.

import type { AppCapability, Goal, MissionPlan, MissionTask, MissionTaskType } from './orchestratorTypes';

let idCounter = 0;
function nextId(prefix: string): string {
  idCounter += 1;
  return `${prefix}_${Date.now()}_${idCounter}`;
}

function makeTask(
  type: MissionTaskType,
  appCapability: AppCapability,
  input: Record<string, unknown>,
  requiresConfirmation: boolean,
): MissionTask {
  return {
    id: nextId('task'),
    type,
    appCapability,
    input,
    requiresConfirmation,
    status: 'PENDING',
  };
}

function buildTaskForGoal(goal: Goal): MissionTask {
  switch (goal.type) {
    case 'TRAVEL': {
      if (goal.entities.destination) {
        const preferredApp = goal.entities.app?.toLowerCase().includes('maps') ? 'google_maps' : 'waze';
        return makeTask('NAVIGATE', 'navigation', { destination: goal.entities.destination, preferredApp }, false);
      }
      return makeTask('OPEN_APP', 'navigation', { appName: goal.entities.app ?? 'Waze' }, false);
    }

    case 'COMMUNICATION': {
      // No confirmation gate — placing a call costs nothing to undo (the other side just doesn't
      // answer, or the user hangs up), and requiring "yes" on every single call made the product
      // unusable hands-free (user-directed 2026-07-16). Messaging below stays gated: a sent
      // message can't be recalled the way an unanswered call can.
      //
      // A plain "sună-o pe X" (no "pe WhatsApp") used to route here as PREPARE_CALL, the native
      // Intent.ACTION_CALL path — confirmed live (2026-07-17) as unreliable on this device: without
      // CALL_PHONE granted it falls back to a bare tel: link, which Android resolves through its
      // own multi-dialer chooser (this phone has several dialer apps installed) instead of just
      // calling. User-directed the same day: always use WhatsApp's calling automation instead —
      // it's already proven working end-to-end — rather than debug the native dialer path further.
      // Same task shape "sună X pe WhatsApp" already produces (PREPARE_MESSAGE/mode=voice_call),
      // just reached from the plain phrasing too now.
      if (goal.sourceIntent === 'CALL_CONTACT' && goal.entities.contact) {
        return makeTask(
          'PREPARE_MESSAGE',
          'messaging',
          { contactName: goal.entities.contact, message: '', mode: 'voice_call' },
          false,
        );
      }
      if (goal.entities.contact) {
        // ROUND_WA_GOVERNANCE_ROUTING — carry sourceIntent + the utterance so toGovernedCall can
        // tell an explicit open-chat intent apart from a message intent with an empty body
        // (→ MESSAGE_BODY_MISSING), and never downgrade one to the other.
        return makeTask(
          'PREPARE_MESSAGE',
          'messaging',
          {
            contactName: goal.entities.contact,
            message: goal.entities.message ?? '',
            mode: goal.entities.mode,
            intent: goal.sourceIntent,
            rawText: goal.normalizedText,
          },
          true,
        );
      }
      return makeTask('STUB_NOT_IMPLEMENTED', 'messaging', {}, false);
    }

    case 'MEDIA':
      return makeTask(
        'PLAY_MEDIA',
        'media',
        { mediaType: goal.entities.mediaType ?? 'music', stationName: goal.entities.stationName },
        false,
      );

    case 'SCHEDULE': {
      // Problem Solver's MEMORY_PROBLEM case ("nu vreau să uit vigneta") sets entities.message
      // and has no dedicated reminder task type yet — honest stub rather than misusing
      // READ_CALENDAR (a read) for what's actually a write. A plain calendar query
      // (CALENDAR_ACTION intent) has no message and maps to the real READ_CALENDAR task.
      if (goal.sourceIntent === 'INFERRED' && goal.entities.message) {
        return makeTask('STUB_NOT_IMPLEMENTED', 'calendar', { reminderContent: goal.entities.message }, false);
      }
      return makeTask('READ_CALENDAR', 'calendar', { readOnly: true }, false);
    }

    case 'FAMILY_CHECK':
      return makeTask('CHECK_FAMILY_LOCATION', 'familyLocation', { contactName: goal.entities.contact }, false);

    // Item 1 — read-only contacts lookup. Never gated: nothing is written, dialed, or messaged
    // here, so there's no action for the confirmation gate to protect.
    case 'SEARCH':
      return goal.entities.contact
        ? makeTask('SEARCH_CONTACTS', 'contacts', { contactName: goal.entities.contact }, false)
        : makeTask('LIST_CONTACTS', 'contacts', {}, false);

    case 'DEVICE_CONTROL': {
      if (goal.sourceIntent === 'RETURN_TO_BENSON') return makeTask('RETURN_TO_BENSON', 'media', {}, false);
      if (goal.sourceIntent === 'CLOSE_APP') {
        return makeTask('RETURN_TO_BENSON', 'media', { appName: goal.entities.app }, false);
      }
      return makeTask('OPEN_APP', 'media', { appName: goal.entities.app }, false);
    }

    case 'SOS':
      return makeTask('STUB_NOT_IMPLEMENTED', 'phone', {}, true);

    case 'HELP':
      return makeTask('SHOW_HELP', 'help', {}, false);

    default:
      return makeTask('STUB_NOT_IMPLEMENTED', 'media', {}, false);
  }
}

export function planMission(goals: Goal[]): MissionPlan {
  const now = Date.now();
  const tasks = goals.map((goal) => buildTaskForGoal(goal));

  return {
    id: nextId('mission'),
    status: 'PLANNED',
    goals,
    tasks,
    createdAt: now,
    updatedAt: now,
  };
}

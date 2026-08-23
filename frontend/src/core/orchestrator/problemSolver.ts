// BENSON Mission Orchestrator — Problem Solver / Reasoning Layer.
// Sits between Goal Extractor and Mission Planner. Two modes:
//   1. Goal Extractor already produced goal(s) -> pass through unchanged (zero behavior change
//      for every phrasing the Intent Engine already recognizes, including BENSON 20's baseline).
//   2. Goal Extractor found nothing (Intent Engine's narrower cascade returned CHAT) -> try a
//      separate, looser set of "problem statement" patterns this layer owns. If one matches but
//      is missing a required entity, ask instead of guessing. If nothing matches at all, this is
//      genuinely unrecognized — Mission Orchestrator falls through to the existing Claude path,
//      same invariant as today (deterministic-first, Claude only for real ambiguity).
//
// Pure deterministic regex, no LLM call — matches this codebase's existing style.

import type { Goal, ProblemSolverInput, ProblemSolverResult, ProblemType, GoalType } from './orchestratorTypes';

let goalCounter = 0;
function nextGoalId(): string {
  goalCounter += 1;
  return `goal_${Date.now()}_${goalCounter}`;
}

function buildGoal(rawText: string, normalizedText: string, type: GoalType, entities: Goal['entities'], confidence: number): Goal {
  return {
    id: nextGoalId(),
    type,
    rawText,
    normalizedText,
    entities,
    confidence,
    sourceIntent: 'INFERRED',
  };
}

const GOAL_TYPE_TO_PROBLEM_TYPE: Record<GoalType, ProblemType> = {
  TRAVEL: 'NAVIGATION_PROBLEM',
  COMMUNICATION: 'COMMUNICATION_PROBLEM',
  MEDIA: 'MEDIA_PROBLEM',
  SCHEDULE: 'SCHEDULE_PROBLEM',
  FAMILY_CHECK: 'FAMILY_PROBLEM',
  SEARCH: 'UNKNOWN',
  SHOPPING: 'UNKNOWN',
  SOS: 'UNKNOWN',
  DEVICE_CONTROL: 'DEVICE_CONTROL_PROBLEM',
  HELP: 'UNKNOWN',
  UNKNOWN: 'UNKNOWN',
};

const NEXT_CLAUSE_BOUNDARY = '(?=\\s+(?:și|si|apoi|and|und)\\s+|$)';

const NAVIGATION_NEED_PATTERN = new RegExp(
  `\\btrebuie\\s+s[ăa]\\s+ajung\\s+(?:la\\s+|spre\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
); // "trebuie să ajung la Sibiu"

const LOST_PATTERN = /\bsunt\s+pierdut[ăa]?\b/i; // "sunt pierdut" — no destination, needs a question

const MEMORY_PATTERN = new RegExp(`\\bnu\\s+vreau\\s+s[ăa]\\s+uit\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'); // "nu vreau să uit vigneta"

const COMMUNICATION_NEED_PATTERN = new RegExp(
  `\\bvreau\\s+s[ăa]-i\\s+spun\\s+(?:lui\\s+)?(.+?)\\s+c[ăa]\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
); // "vreau să-i spun lui Hannah că întârzii"

function tryFallbackPatterns(rawText: string, normalizedText: string): ProblemSolverResult | null {
  const lostMatch = LOST_PATTERN.test(normalizedText);
  if (lostMatch) {
    return {
      problemType: 'NAVIGATION_PROBLEM',
      inferredGoals: [],
      missingInfo: ['destination'],
      suggestedNextStep: 'Deschid navigația sau îți arăt locația?',
      confidence: 0.6,
    };
  }

  const needMatch = normalizedText.match(NAVIGATION_NEED_PATTERN);
  if (needMatch && needMatch[1]?.trim()) {
    const destination = needMatch[1].trim();
    return {
      problemType: 'NAVIGATION_PROBLEM',
      inferredGoals: [buildGoal(rawText, normalizedText, 'TRAVEL', { destination }, 0.8)],
      missingInfo: [],
      confidence: 0.8,
    };
  }

  const memoryMatch = normalizedText.match(MEMORY_PATTERN);
  if (memoryMatch && memoryMatch[1]?.trim()) {
    const message = memoryMatch[1].trim();
    return {
      problemType: 'MEMORY_PROBLEM',
      inferredGoals: [buildGoal(rawText, normalizedText, 'SCHEDULE', { message }, 0.8)],
      missingInfo: [],
      confidence: 0.8,
    };
  }

  const commMatch = normalizedText.match(COMMUNICATION_NEED_PATTERN);
  if (commMatch && commMatch[1]?.trim()) {
    const contact = commMatch[1].trim();
    const message = (commMatch[2] ?? '').trim();
    return {
      problemType: 'COMMUNICATION_PROBLEM',
      inferredGoals: [buildGoal(rawText, normalizedText, 'COMMUNICATION', { contact, message }, 0.8)],
      missingInfo: message ? [] : ['message'],
      confidence: 0.8,
    };
  }

  return null;
}

export function solveProblem(input: ProblemSolverInput): ProblemSolverResult {
  if (input.goals.length > 0) {
    // Goal Extractor already succeeded — pass through, problemType derived from the primary
    // goal's type for the debug panel/logging, nothing else changes.
    const problemType = GOAL_TYPE_TO_PROBLEM_TYPE[input.goals[0].type] ?? 'UNKNOWN';
    return {
      problemType,
      inferredGoals: input.goals,
      missingInfo: [],
      confidence: input.goals[0].confidence,
    };
  }

  const fallback = tryFallbackPatterns(input.rawText, input.normalizedText);
  if (fallback) return fallback;

  // Neither the Intent Engine nor this layer's own broader patterns recognized anything —
  // genuinely unrecognized input. Mission Orchestrator falls through to the existing Claude path.
  return { problemType: 'UNKNOWN', inferredGoals: [], missingInfo: [], confidence: 0 };
}

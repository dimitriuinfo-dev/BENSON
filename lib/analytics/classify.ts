import type { OrchestratorResult } from '../agents/orchestrator';

// Maps a routed result to the analytics command_type taxonomy — the card kind wins when present
// (more specific than the agent name), otherwise falls back to the agent itself.
export function classifyCommand(result: OrchestratorResult): string {
  const card = result.card;
  if (card) {
    if (card.kind === 'map') return 'navigation';
    if (card.kind === 'note') return card.noteType;
    return card.kind; // 'media' | 'weather' | 'gallery' | 'news' | 'todo'
  }
  switch (result.agent) {
    case 'contacts':    return 'call';
    case 'appLauncher': return 'app_launch';
    case 'search':      return 'search';
    case 'claude':      return 'conversation';
    case 'notepad':     return 'notepad_clarify';
    default:            return result.agent;
  }
}

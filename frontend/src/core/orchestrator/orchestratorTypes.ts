// BENSON Mission Orchestrator — shared types. Pure types only, no runtime logic.

export type GoalType =
  | 'TRAVEL'
  | 'COMMUNICATION'
  | 'MEDIA'
  | 'SCHEDULE'
  | 'FAMILY_CHECK'
  | 'SEARCH'
  | 'SHOPPING'
  | 'SOS'
  | 'DEVICE_CONTROL'
  | 'HELP'
  | 'UNKNOWN';

export interface GoalEntities {
  destination?: string;
  contact?: string;
  message?: string;
  time?: string;
  app?: string;
  mediaType?: string;
  stationName?: string;
  mode?: string;
}

export interface Goal {
  id: string;
  type: GoalType;
  rawText: string;
  normalizedText: string;
  entities: GoalEntities;
  confidence: number;
  sourceIntent: string;
}

export type ProblemType =
  | 'NAVIGATION_PROBLEM'
  | 'COMMUNICATION_PROBLEM'
  | 'MEMORY_PROBLEM'
  | 'SCHEDULE_PROBLEM'
  | 'FAMILY_PROBLEM'
  | 'MEDIA_PROBLEM'
  | 'DEVICE_CONTROL_PROBLEM'
  | 'UNKNOWN';

export interface ProblemSolverInput {
  rawText: string;
  normalizedText: string;
  goals: Goal[];
  context: OrchestratorContext;
}

export interface ProblemSolverResult {
  problemType: ProblemType;
  inferredGoals: Goal[];
  missingInfo: string[];
  suggestedNextStep?: string;
  confidence: number;
}

export type MissionStatus = 'PLANNED' | 'RUNNING' | 'WAITING_FOR_CONFIRMATION' | 'COMPLETED' | 'FAILED' | 'PARTIAL';

export type MissionTaskType =
  | 'OPEN_APP'
  | 'NAVIGATE'
  | 'RETURN_TO_BENSON'
  | 'PREPARE_MESSAGE'
  | 'PREPARE_CALL'
  | 'READ_CALENDAR'
  | 'PLAY_MEDIA'
  | 'CHECK_FAMILY_LOCATION'
  | 'ASK_CONFIRMATION'
  | 'SHOW_HELP'
  | 'LIST_CONTACTS'
  | 'SEARCH_CONTACTS'
  | 'STUB_NOT_IMPLEMENTED';

export type AppCapability = 'navigation' | 'messaging' | 'calendar' | 'media' | 'familyLocation' | 'phone' | 'help' | 'contacts';

export type MissionTaskStatus = 'PENDING' | 'RUNNING' | 'WAITING' | 'DONE' | 'FAILED' | 'SKIPPED';

export interface MissionTask {
  id: string;
  type: MissionTaskType;
  appCapability: AppCapability;
  input: Record<string, unknown>;
  requiresConfirmation: boolean;
  status: MissionTaskStatus;
  dependsOn?: string[];
  resultMessage?: string;
  errorMessage?: string;
}

export interface MissionPlan {
  id: string;
  status: MissionStatus;
  goals: Goal[];
  tasks: MissionTask[];
  createdAt: number;
  updatedAt: number;
}

export interface DefaultApps {
  navigation: string;
  messaging: string;
  media: string;
  email: string;
}

export interface OrchestratorContext {
  activeMissionId?: string;
  lastMissionId?: string;
  currentApp?: string;
  lastApp?: string;
  lastDestination?: string;
  lastContact?: string;
  lastMessageDraft?: string;
  defaultApps: DefaultApps;
  savedPlaces: Record<string, string | undefined>;
  favoriteContacts: string[];
}

export interface OrchestratorEvent {
  id: string;
  type: string;
  timestamp: number;
  missionId?: string;
  taskId?: string;
  payload?: Record<string, unknown>;
}

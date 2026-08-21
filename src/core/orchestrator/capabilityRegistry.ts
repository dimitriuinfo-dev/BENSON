// BENSON Mission Orchestrator — Capability Registry.
// Answers "what capability do we need, which apps satisfy it, what's preferred, what's the
// fallback, what's actually implemented" — one layer above the App Registry (which only knows
// packages/deep links). References App Registry entries by name rather than duplicating
// packageName data, so the two can't drift out of sync.

import { findAppRegistryEntry } from '../action-engine';
import type { AppCapability } from './orchestratorTypes';

export interface CapabilityProvider {
  name: string;
  appName?: string;
  packageName?: string;
  implemented: boolean | 'partial';
  supportsDeepLink: boolean;
  requiresPermission: string[];
  canExecuteDirectly: boolean;
  requiresConfirmation: boolean;
}

export interface CapabilityEntry {
  capability: AppCapability | 'email' | 'search' | 'shopping' | 'sos' | 'deviceControl';
  preferredProvider?: string;
  providers: CapabilityProvider[];
  fallbackOrder: string[];
}

function fromAppRegistry(name: string, opts: Partial<CapabilityProvider> = {}): CapabilityProvider {
  const entry = findAppRegistryEntry(name);
  return {
    name,
    appName: entry?.name,
    packageName: entry?.packageName,
    implemented: Boolean(entry?.packageName),
    supportsDeepLink: Boolean(entry?.deeplinkTemplate),
    requiresPermission: [],
    canExecuteDirectly: true,
    requiresConfirmation: false,
    ...opts,
  };
}

export const CAPABILITY_REGISTRY: CapabilityEntry[] = [
  {
    capability: 'navigation',
    preferredProvider: 'Waze',
    providers: [fromAppRegistry('Waze'), fromAppRegistry('Google Maps')],
    fallbackOrder: ['Waze', 'Google Maps'],
  },
  {
    capability: 'messaging',
    preferredProvider: 'WhatsApp',
    providers: [
      fromAppRegistry('WhatsApp', { implemented: 'partial', requiresConfirmation: true }),
      { name: 'SMS', implemented: 'partial', supportsDeepLink: true, requiresPermission: [], canExecuteDirectly: true, requiresConfirmation: true },
      { name: 'Email', implemented: false, supportsDeepLink: false, requiresPermission: [], canExecuteDirectly: false, requiresConfirmation: true },
    ],
    fallbackOrder: ['WhatsApp', 'SMS', 'Email'],
  },
  {
    capability: 'phone',
    preferredProvider: 'Phone',
    providers: [
      { name: 'Phone', implemented: true, supportsDeepLink: true, requiresPermission: [], canExecuteDirectly: true, requiresConfirmation: true },
    ],
    fallbackOrder: ['Phone'],
  },
  {
    capability: 'calendar',
    providers: [
      { name: 'Google Calendar', implemented: false, supportsDeepLink: false, requiresPermission: ['calendar'], canExecuteDirectly: false, requiresConfirmation: false },
    ],
    fallbackOrder: ['Google Calendar'],
  },
  {
    capability: 'media',
    providers: [
      { name: 'Music', implemented: true, supportsDeepLink: false, requiresPermission: [], canExecuteDirectly: true, requiresConfirmation: false },
      { name: 'Radio', implemented: true, supportsDeepLink: false, requiresPermission: [], canExecuteDirectly: true, requiresConfirmation: false },
    ],
    fallbackOrder: ['Music', 'Radio'],
  },
  {
    capability: 'familyLocation',
    providers: [
      { name: 'Life360', implemented: false, supportsDeepLink: false, requiresPermission: ['location'], canExecuteDirectly: false, requiresConfirmation: false },
    ],
    fallbackOrder: ['Life360'],
  },
];

export function getCapability(capability: string): CapabilityEntry | undefined {
  return CAPABILITY_REGISTRY.find((c) => c.capability === capability);
}

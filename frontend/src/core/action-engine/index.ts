// BENSON Action Engine — public surface. Everything downstream (App Launcher, Navigation,
// Contact Resolver, WhatsApp, Phone Call, Confirmation Gate executors) imports from here.

export * from './actionTypes';
export * from './actionRequest';
export * from './actionResult';
export * from './executorTypes';
export * from './transcriptNormalizer';
export * from './discourseCleaner';
export * from './appRegistry';
export * from './androidActionExecutor';
export * from './commandParser';
export * from './contactActionBridge';
// actionDispatcher is exported LAST on purpose: it imports the executors, which in turn import
// the exports above (actionTypes/actionRequest/actionResult/executorTypes) from this same barrel
// file — a genuine circular import. Keeping this line last means every export the executors
// actually need is already attached to this module's exports by the time their side of the
// cycle runs, so the circular require resolves correctly instead of seeing `undefined`.
export * from './actionDispatcher';
export * from './actionEngineDryRun';
// appGovernanceEngine composes actionDispatcher + the safety gate; exported after both so
// nothing it needs is still unresolved.
export * from './appGovernanceEngine';

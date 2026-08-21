import * as Location from 'expo-location';
import {
  requestPermissionAsync as requestBtPermission,
  startMonitoring as startBtMonitoring,
  stopMonitoring as stopBtMonitoring,
  addDeviceConnectedListener,
  addDeviceDisconnectedListener,
} from 'benson-car-bluetooth';

const SPEED_ON_KMH        = 25;
const SPEED_ON_SUSTAIN_MS = 30_000;
const SPEED_OFF_KMH        = 5;
const SPEED_OFF_SUSTAIN_MS = 60_000;

export type CarAutoDetectHandle = { stop: () => void };

// Car Mode auto-detection — two independent real signals, no manual button/voice command:
//   1. Bluetooth: the saved "car" device connects/disconnects (any profile — A2DP/HFP/etc).
//   2. GPS speed: sustained >25km/h for 30s turns it on; sustained <5km/h for 60s turns it off.
// (A third accelerometer/gyroscope signal was considered but dropped — a reliable
// car-vs-walk-vs-bike classifier needs a trained model, not a hand-rolled heuristic.)
export async function startCarAutoDetection(params: {
  carDeviceAddress: string;
  onAutoOn: () => void;
  onAutoOff: () => void;
}): Promise<CarAutoDetectHandle> {
  let connectedSub: { remove: () => void } | null = null;
  let disconnectedSub: { remove: () => void } | null = null;
  let locationSub: Location.LocationSubscription | null = null;

  try {
    await requestBtPermission();
    connectedSub = addDeviceConnectedListener((device) => {
      if (params.carDeviceAddress && device.address === params.carDeviceAddress) {
        params.onAutoOn();
      }
    });
    disconnectedSub = addDeviceDisconnectedListener((device) => {
      if (params.carDeviceAddress && device.address === params.carDeviceAddress) {
        params.onAutoOff();
      }
    });
    startBtMonitoring();
  } catch {}

  let aboveSince: number | null = null;
  let belowSince: number | null = null;
  let onFiredForWindow = false;
  let offFiredForWindow = false;

  try {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (perm.granted) {
      locationSub = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.Balanced, timeInterval: 5000, distanceInterval: 20 },
        (loc) => {
          const speedKmh = (loc.coords.speed ?? 0) * 3.6;
          const now = Date.now();

          if (speedKmh > SPEED_ON_KMH) {
            if (aboveSince === null) { aboveSince = now; onFiredForWindow = false; }
            if (!onFiredForWindow && now - aboveSince >= SPEED_ON_SUSTAIN_MS) {
              onFiredForWindow = true;
              params.onAutoOn();
            }
          } else {
            aboveSince = null;
          }

          if (speedKmh < SPEED_OFF_KMH) {
            if (belowSince === null) { belowSince = now; offFiredForWindow = false; }
            if (!offFiredForWindow && now - belowSince >= SPEED_OFF_SUSTAIN_MS) {
              offFiredForWindow = true;
              params.onAutoOff();
            }
          } else {
            belowSince = null;
          }
        },
      );
    }
  } catch {}

  return {
    stop() {
      connectedSub?.remove();
      disconnectedSub?.remove();
      stopBtMonitoring();
      locationSub?.remove();
    },
  };
}

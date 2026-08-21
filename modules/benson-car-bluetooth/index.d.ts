export type BluetoothDeviceInfo = { name: string; address: string };

export declare function requestPermissionAsync(): Promise<{ granted: boolean }>;
export declare function getBondedDevices(): Promise<BluetoothDeviceInfo[]>;
export declare function startMonitoring(): void;
export declare function stopMonitoring(): void;
export declare function addDeviceConnectedListener(
  listener: (device: BluetoothDeviceInfo) => void
): { remove: () => void };
export declare function addDeviceDisconnectedListener(
  listener: (device: BluetoothDeviceInfo) => void
): { remove: () => void };

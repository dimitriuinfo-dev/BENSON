import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonCarBluetooth');
const emitter = new EventEmitter(NativeModule);

export function requestPermissionAsync() {
  return NativeModule.requestPermissionsAsync();
}

export function getBondedDevices() {
  return NativeModule.getBondedDevices();
}

export function startMonitoring() {
  return NativeModule.startMonitoring();
}

export function stopMonitoring() {
  return NativeModule.stopMonitoring();
}

// Fires when any bonded device connects (any Bluetooth profile — A2DP, HFP, etc).
export function addDeviceConnectedListener(listener) {
  return emitter.addListener('onDeviceConnected', listener);
}

export function addDeviceDisconnectedListener(listener) {
  return emitter.addListener('onDeviceDisconnected', listener);
}

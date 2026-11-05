import { registerPlugin } from '@capacitor/core';

export interface UsbDrive {
  deviceId: number;
  name: string;
  manufacturer: string;
  product: string;
}

export interface RecoveryProgress {
  bytesScanned: number;
  totalBytes: number;
  filesFound: number;
  percent: number;
}

export interface RecoveredFile {
  name: string;
  path: string;
  size: number;
  kind: string;
  complete: boolean;
}

export interface OtgRecoveryPlugin {
  listDrives(): Promise<{ drives: UsbDrive[] }>;
  connect(options: { deviceId: number }): Promise<{ connected: boolean }>;
  startScan(options: { deviceId: number; maxBytes?: number }): Promise<{ started: boolean }>;
  stopScan(): Promise<{ stopped: boolean }>;
  listRecoveredFiles(): Promise<{ files: RecoveredFile[] }>;
  addListener(
    eventName: 'scanProgress',
    listener: (progress: RecoveryProgress) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: 'fileRecovered',
    listener: (file: RecoveredFile) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: 'scanComplete',
    listener: (result: { filesFound: number; cancelled: boolean; message: string }) => void,
  ): Promise<{ remove: () => void }>;
}

export const OtgRecovery = registerPlugin<OtgRecoveryPlugin>('OtgRecovery');

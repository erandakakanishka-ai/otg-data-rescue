import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'app.otgrescue',
  appName: 'OTG Data Rescue',
  webDir: 'dist',
  android: {
    allowMixedContent: false
  }
};

export default config;

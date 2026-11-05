# OTG Data Rescue

An **experimental, read-only Android recovery prototype** for USB flash drives connected over OTG. Built with Capacitor 7, TypeScript, and a native Android USB/SCSI plugin.

> **Important:** Recovery is never guaranteed. Deleted data that has been overwritten or discarded by the device cannot be restored. Do not write to or format the source drive before recovery.

## What this prototype does

- Detects Android USB mass-storage devices without root.
- Requests Android USB permission and submits standard SCSI commands.
- Reads source sectors sequentially without issuing write commands.
- Carves common contiguous JPG, PNG, GIF, PDF, ZIP-based, and MP4 files.
- Saves recovered output to app-private storage, never to the source drive.
- Builds a debug APK through GitHub Actions.

## Limitations

- This is an early engineering prototype, not a validated forensic product.
- File carving often loses original names and folder structures.
- Fragmented files, encrypted volumes, unsupported filesystems, and unusual USB bridges can fail.
- MP4 and ZIP recovery needs additional structural validation before production use.
- Use only on drives you own or have permission to examine.

## Development

Capacitor 7 requires a current Node.js release. Android builds use JDK 17.

```bash
npm ci
npm run build
npx cap sync android
cd android
./gradlew assembleDebug
```

The APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Safety and future work

The USB implementation must remain read-only. Planned production work includes a validated native recovery engine, filesystem-aware FAT32/exFAT parsing, fragmentation handling, output-folder selection, cancellation tests, malformed-device handling, and corpus-based recovery tests.

## License

GPL-2.0-or-later. See `LICENSE`.

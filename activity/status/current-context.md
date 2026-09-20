# Current Context - TrustNet Android App

**Last Updated**: September 17, 2026  
**Status**: Header duplication FIXED ✅ | NFC data read NOT WORKING ⚠️

---

## What Works ✅

- **App Launch**: SplashActivity displays correctly with version at bottom
- **Navigation**: Can navigate through Document Type → Camera/OCR
- **UI Headers**: Single purple header with "TrustNet" + "v0.1.0-dev" on all workflow screens
- **Version System**: Properly injected via gradle, displays everywhere
- **Build System**: Clean builds successfully in ~1m 16s
- **Installation**: APK installs on device (Huawei P10, Android 8.1.0)

## What Doesn't Work ⚠️

- **NFC Data Reading**: Passport/ID card data not being extracted
  - App reaches NFC Progress screen
  - Cannot read card data with JMRTD
  - BAC (Basic Access Control) key derivation or communication issue
  - Likely: JMRTD initialization, MRZ-to-BAC flow, or NFC protocol handling

## Current Build

- **Package**: com.trustnetid.app.debug
- **Version**: 0.1.0-dev
- **Target**: Android 8.1+ (API 28+)
- **Last Build**: September 17, 2026, 1m 16s

## Files Modified Today

1. CameraActivity.kt - Added supportActionBar?.hide()
2. ConfirmationActivity.kt - Added supportActionBar?.hide()
3. NFCProgressActivity.kt - Added supportActionBar?.hide() + setHeaderVersion() method
4. PassportConfirmationActivity.kt - Added supportActionBar?.hide()
5. MainActivity.kt - Added supportActionBar?.hide()

## Next Priority

**NFC Data Reading** - Get passport NFC communication working
- Check NFCProgressActivity.kt (lines 57-496) for JMRTD integration
- Review BAC key derivation algorithm
- Monitor logcat for NFC errors: `adb logcat | grep "TrustNet"`
- Reference: previous NFC debugging sessions in ~/GitProjects/TrustNet/activity/sprints/

## Quick Commands

```bash
cd ~/GitProjects/TrustNet/android-app
./gradlew clean build -x test        # Full build
./gradlew installDebug               # Install on device
adb logcat | grep "TrustNet"         # View logs
./gradlew cleanBuildCache            # Clear cache if needed
```

## Screenshots Available

- Splash screen: `~/GitProjects/TrustNet/screenshots/splash_screenshot.png`
- Document Type: `~/GitProjects/TrustNet/screenshots/doctype_screenshot.png`
- Main Activity: `~/GitProjects/TrustNet/screenshots/check_screenshot.png`

---
**Ready for next session** ✅

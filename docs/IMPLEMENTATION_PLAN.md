# TrustNet Rebuild Plan - Implementation Steps

**Date**: July 22, 2026  
**Status**: Ready for implementation  
**Estimated Duration**: 5-7 hours  

## Phase 1: Embedded Camera2 Implementation

### Step 1.1: Update build.gradle (add Camera2 extensions)
```gradle
dependencies {
    // Camera2
    implementation "androidx.camera:camera-camera2:1.2.3"
    implementation "androidx.camera:camera-core:1.2.3"
    implementation "androidx.camera:camera-lifecycle:1.2.3"
    implementation "androidx.camera:camera-view:1.2.3"
    
    // ML Kit (already have, but verify)
    implementation 'com.google.mlkit:vision-common:17.3.0'
    implementation 'com.google.mlkit:text-recognition-latin:16.0.0'
}
```

### Step 1.2: Update activity_camera.xml layout
```xml
<!-- Keep existing title and status -->
<!-- Add SurfaceView for camera preview -->
<SurfaceView
    android:id="@+id/surfaceView"
    android:layout_width="match_parent"
    android:layout_height="400dp"
    android:layout_marginTop="20dp" />

<!-- Add progress bar for OCR processing -->
<ProgressBar
    android:id="@+id/processingProgressBar"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center" />

<!-- Add status text view (update with OCR results) -->
<!-- Keep TAKE PHOTO button? Or remove since auto-capture? -->
```

### Step 1.3: Rewrite CameraActivity.kt
**Key functionality**:
- Create SurfaceView with Camera2 API
- Capture frames continuously
- Run ML Kit OCR on frames
- Detect MRZ in frame
- Auto-capture when conditions met
- Extract CAN
- Pass to NFCProgressActivity

**Methods needed**:
- `setupCamera()` - Initialize Camera2
- `onFrameAvailable()` - Process each camera frame
- `extractCAN()` - ML Kit + MRZParser
- `autoCapture()` - Proceed to NFC

**NO CHANGES to MRZParser** - keep working code

## Phase 2: App-Controlled NFC

### Step 2.1: Update NFCProgressActivity.kt
**Remove**:
- All intent filter handling
- `onNewIntent()` method
- Manual intent parsing

**Add**:
- NFC callback implementation
- `enableReaderMode()` in onResume()
- Tag discovery callback
- BAC key derivation before NFC read

**Methods needed**:
- `onTagDiscovered(tag: Tag)` - NFC callback
- `initializeNFC()` - Setup enableReaderMode()
- `performBACAuthentication(tag: Tag)` - Call with BAC key

**NO CHANGES to GovernmentIDNFCReader** - keep working code

### Step 2.2: Create BACKeyService.kt (NEW FILE)
**Purpose**: Generate BAC key from MRZ data

**Methods**:
```kotlin
fun deriveBACKey(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): ByteArray {
    // Concatenate MRZ components
    // SHA-1 hash
    // Derive Kenc/Kmac
    // Return BAC key
}
```

## Phase 3: Integration Points

### Step 3.1: DocumentTypeActivity.kt
No changes - continue to launch CameraActivity

### Step 3.2: MainActivity.kt  
No changes - continue to receive NFC results

### Step 3.3: AndroidManifest.xml
**Remove**:
- Manual NFC intent filters from NFCProgressActivity

**Keep**:
- NFC permission
- CAMERA permission
- All activity registrations

## Branding Preservation Checklist

| Component | Status | Verify |
| --------- | ------ | ------ |
| Splash logo | ✓ Keep | SplashActivity unchanged |
| Purple theme | ✓ Keep | colors.xml unchanged |
| App icon | ✓ Keep | ic_launcher unchanged |
| TrustNet title | ✓ Keep | strings.xml "TrustNet" unchanged |
| Camera screen title | ✓ Keep | "Capture ID Document" text |
| Buttons (color/style) | ✓ Keep | button_color.xml unchanged |
| Layout files (non-camera) | ✓ Keep | document_type, nfc_progress, splash |

## Files Modified Summary

| File | Changes | Type |
| ---- | ------- | ---- |
| build.gradle | Add Camera2 deps | Dependency |
| activity_camera.xml | Add SurfaceView | Layout |
| CameraActivity.kt | Major rewrite | Implementation |
| NFCProgressActivity.kt | Rewrite NFC handling | Implementation |
| BACKeyService.kt | NEW | Implementation |
| AndroidManifest.xml | Remove intent filters | Config |

## Files NOT Changed (Preserved)

- MRZParser.kt ✓
- GovernmentIDNFCReader.kt ✓
- PACEAuthenticator.kt ✓
- MainActivity.kt ✓ (mostly)
- DocumentTypeActivity.kt ✓
- SplashActivity.kt ✓
- All other layout files ✓
- All string resources ✓
- All color resources ✓
- All themes ✓

## Testing Checklist

After rebuild:
- [ ] App launches with logo and splash
- [ ] Select document type → camera preview shows
- [ ] Camera preview displays real-time (not frozen)
- [ ] Move document in frame → preview updates
- [ ] Auto-capture triggers when MRZ detected
- [ ] CAN extracted correctly (shown in status)
- [ ] NFCProgressActivity appears automatically
- [ ] Hold phone on NFC chip
- [ ] NFC callback fires (check logcat)
- [ ] Personal data displays (no "[Protected - Requires PACE]")
- [ ] All branding intact (colors, logo, text)

## Command Reference

```bash
# Build
cd ~/GitProjects/TrustNet/android-app
./gradlew clean build

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Logcat
adb logcat -v threadtime | grep -i "Camera\|NFC\|CAN\|ERROR"

# Test
adb shell am start -n com.trustnet.app/.MainActivity
```

---

**READY TO IMPLEMENT**: All documentation saved. No branding will be removed. Will preserve all working code.

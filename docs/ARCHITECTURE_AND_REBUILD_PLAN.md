# TrustNet NFC Identity Verification - Architecture Overview

## Current Problem (July 22, 2026)

**What's NOT working**:
- Camera launches system app (not embedded in TrustNet)
- User control leaves the app - can cancel, modify, or never return
- NFC uses intent filters (unreliable on some devices like Huawei)
- No automatic capture detection
- No BAC key derivation from MRZ

**User requirements** (from actual Spanish ID card test):
```
Document Type: ID Card (TD1 format)
Name: JOSÉ MARIA GARCIA MARTINEZ
Address: PRINCES SQUARE 60-61, LONDON, REINO UNIDO
DOB: July 11, 1929 (position in MRZ: 290711)
Expiry: September 4, 2081 (position in MRZ: 810940)
Document Number: IDESPBK1169706 (starts MRZ)

MRZ (Machine Readable Zone - 3 lines):
Line 1: IDESPBK1169706411409168H<<<<<<<
Line 2: 65103188M29071181ESP<<<<<<<<<<<3
Line 3: GARCIA<MARTINEZ<<JULIO<CESAR<
```

**CAN (Card Access Number) extraction formula**:
```
CAN = MRZ[Line2][5:11]  (6 characters from line 2, positions 5-10, 0-indexed)
     = "103188"  (from: "65103188M29071181ESP...")
```

**BAC Key derivation** (from CAN):
```
1. Extract from MRZ:
   - Document Number: IDESPBK1169706
   - Date of Birth: 290711 (July 11, 1929)
   - Expiry: 810940 (September 4, 2081)

2. Concatenate: "IDESPBK1169706" + "290711" + "810940"

3. Hash with SHA-1: BAC_key = SHA1(concatenated_string)

4. Derive encryption keys:
   Kseed = SHA1(MRZ_concatenated)
   Kenc = KDF(Kseed, "Encryption")
   Kmac = KDF(Kseed, "Authentication")

5. Use Kenc/Kmac for NFC encryption/decryption
```

## Professional App Architecture (ReadID, IDnow, Onfido pattern)

### Phase 1: Embedded Camera with Auto-Capture
```
1. CameraActivity displays live preview (NOT system camera)
   - SurfaceView or TextureView shows camera feed
   - User sees TrustNet UI, not system camera app

2. ML Kit OCR runs on every frame
   - Detects MRZ text in real-time
   - Detects document edges

3. Auto-capture triggers when:
   - Document edges aligned in frame
   - MRZ text clearly visible
   - User does NOT need to tap button

4. Photo captured → immediately extract CAN
   - Parse MRZ from OCR result
   - Derive BAC key

5. Proceed to NFC automatically
```

### Phase 2: App-Controlled NFC (enableReaderMode)
```
1. MainActivity registers NFC callback:
   NfcAdapter.enableReaderMode(this, callback, flags, options)

2. System detects NFC tag → direct callback
   (NOT through intent, NOT through manifest filters)

3. App callback receives tag immediately:
   onTagDiscovered(tag: Tag) {
       // App has full control
       // Perform BAC authentication with CAN
       // Read DG1 (biography) + DG2 (photo)
   }

4. No system dialogs, no external apps
   Complete control within TrustNet UI
```

### Phase 3: BAC Authentication
```
1. NFC opens IsoDep connection to chip
2. Sends PACE authentication with derived BAC key
3. If successful: DG1/DG2 files are readable
4. If failed: "[Protected - Requires PACE]" error

Current issue: Using PACE instead of BAC causes failures
Fix: Implement proper BAC key generation from MRZ
```

## Current Codebase Status

| Component | Status | Issue |
|-----------|--------|-------|
| MRZParser.kt | ✓ Working | Correctly extracts CAN from MRZ |
| GovernmentIDNFCReader.kt | ✓ Working | Implements PACE authentication |
| PACEAuthenticator.kt | ✓ Working | ISO/IEC 11770-4 protocol |
| CameraActivity.kt | ❌ Broken | Uses Intent.ACTION_IMAGE_CAPTURE (system camera) |
| NFCProgressActivity.kt | ⚠ Unreliable | Uses intent filters instead of enableReaderMode() |
| MainActivity.kt | ✓ Mostly working | Needs NFC callback handler |
| Branding | ✓ Preserved | Logo in splash, purple theme intact |

## Files to Rebuild

### 1. CameraActivity.kt (MAJOR REWRITE)
**Current approach** (WRONG):
- Launches system camera via Intent
- Waits for user to take photo
- Photo returns (unreliably)

**New approach** (CORRECT):
- Embedded Camera2 API with SurfaceView
- Live preview in TrustNet UI
- ML Kit OCR on every frame
- Auto-capture when MRZ detected
- Extract CAN immediately
- Proceed to NFC without user action

**Key changes**:
- Add Camera2 API implementation
- Add SurfaceView for preview
- Add ML Kit frame processing loop
- Add edge detection + MRZ detection
- Remove Intent-based camera

### 2. NFCProgressActivity.kt (REWRITE)
**Current approach** (UNRELIABLE):
- Uses manifest intent filters
- System may or may not deliver intent
- No direct app control

**New approach** (CORRECT):
- Use NfcAdapter.enableReaderMode()
- Direct callback to app
- Complete control within activity

**Key changes**:
- Remove intent filters from manifest
- Add enableReaderMode() in onResume()
- Add direct NFC tag callback
- Implement BAC key derivation before NFC read

### 3. BAC Key Service (NEW)
**Create**: BACKeyService.kt
- SHA-1 hashing of MRZ data
- Key derivation for Kenc/Kmac
- Use with NFC authentication

**Dependencies**:
- java.security.MessageDigest (SHA-1)
- javax.crypto for key derivation

### 4. AndroidManifest.xml (MINOR UPDATE)
**Changes**:
- Keep NFC permissions (unchanged)
- Keep CAMERA permission (unchanged)
- Remove manual intent filters (handled by code now)
- Keep branding intact

## Preserved Components (NO CHANGES)

✓ **Branding**:
- SplashActivity with logo
- Purple theme color (#7B1FA2)
- TrustNet title on all screens
- App icon

✓ **Working Code** (Never touch):
- MRZParser.kt
- GovernmentIDNFCReader.kt
- PACEAuthenticator.kt
- MainActivity.kt (mostly)
- Layout files (will add SurfaceView)

✓ **Layout Files**:
- activity_splash.xml - logo display
- activity_document_type.xml - document selection
- activity_nfc_progress.xml - NFC ready screen
- activity_camera.xml - will add SurfaceView

## Implementation Timeline

| Phase | Duration | What |
|-------|----------|------|
| Phase 1 | 2-3 hours | Embedded Camera2 + auto-capture |
| Phase 2 | 1 hour | enableReaderMode() + callbacks |
| Phase 3 | 1 hour | BAC key service |
| Testing | 1-2 hours | End-to-end workflow validation |

**Total**: 5-7 hours for complete rebuild

## Success Criteria

After rebuild, app must:
- ✓ Launch with TrustNet logo (branding preserved)
- ✓ Show live camera preview inside app (not system camera)
- ✓ Auto-capture when document detected
- ✓ Extract CAN from MRZ automatically
- ✓ Proceed to NFC screen without user action
- ✓ NFC scan with direct app callback (no intents)
- ✓ Display personal data without "[Protected - Requires PACE]"
- ✓ All branding and colors preserved

## Document Photo Reference (User Provided)

Spanish National ID Card with:
- Hologram visible (top left)
- Name: JOSÉ MARIA GARCIA MARTINEZ
- Address: PRINCES SQUARE 60-61, LONDON, REINO UNIDO
- MRZ clearly readable in OCR testing
- NFC chip visible (contacts at bottom right)

This is the exact card being used for testing.

---

**Status**: Architecture documented. Ready to rebuild CameraActivity and NFC handling.
**Date**: July 22, 2026
**Priority**: HIGH - Camera and NFC must be app-controlled, not system-controlled

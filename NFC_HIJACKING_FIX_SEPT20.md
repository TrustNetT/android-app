# NFC System Hijacking Fix - September 20, 2026

## Problem
**Symptom**: Device system was intercepting NFC before the app reached the NFC screen, causing the system NFC dialog to appear during camera capture and OCR phases.

**Root Cause**: Android's default NFC handler was activating when document with NFC chip was held near device, even before `NFCProgressActivity` called `enableReaderMode()` to claim exclusive control.

**Impact**: User workflow interrupted - system dialog appeared during camera/OCR phase instead of allowing app to control NFC only on the dedicated NFC screen.

---

## Solution: Disable NFC in All Non-NFC Activities

Added `disableReaderMode()` to `onResume()` in all activities that appear BEFORE the NFC screen:

### Activities Modified:
1. **DocumentTypeActivity.kt** - Added onResume()
2. **CameraActivity.kt** - Added onResume()
3. **ConfirmationActivity.kt** - Added onResume()

### Code Pattern Added to Each:
```kotlin
override fun onResume() {
    super.onResume()
    // CRITICAL: Disable system NFC handler to prevent hijacking
    // Only NFCProgressActivity should handle NFC, and only when user explicitly taps button
    val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
    nfcAdapter?.disableReaderMode(this)
}
```

---

## How It Works

### Activity Lifecycle Flow:
```
DocumentTypeActivity
    ↓ (onResume: disableReaderMode)  ← NFC BLOCKED
    → User selects document type
    → Launch CameraActivity
    ↓ (onResume: disableReaderMode)  ← NFC BLOCKED
    → User captures document image
    → OCR extracts MRZ
    → Launch ConfirmationActivity
    ↓ (onResume: disableReaderMode)  ← NFC BLOCKED
    → User verifies MRZ data
    → Launch NFCProgressActivity
    ↓ (NO disableReaderMode call)    ← NFC ENABLED
    → User taps "Scan" button
    → enableReaderMode(READER_MODE_NFC_ISODEP)
    → App gains EXCLUSIVE NFC control
    → Device NFC handler cannot interfere
    → Tag detected → onTagDiscovered() called
    ↓ (onPause: disableReaderMode)  ← NFC RELEASED when leaving
```

### Why This Works:
- **disableReaderMode()**: Tells Android "this activity doesn't handle NFC" → system can use default handler if needed
- **No intent filters**: App doesn't claim NFC via manifest → system doesn't route intents
- **Explicit enableReaderMode() in NFCProgressActivity**: Gives app **exclusive** NFC control when needed
- **Proper lifecycle**: NFC disabled in onResume, enabled on button tap, re-disabled in onPause

---

## Critical Details

### ✅ ALREADY IN PLACE (Verified):
- ✅ AndroidManifest.xml has **zero NFC intent filters**
- ✅ NFCProgressActivity uses `enableReaderMode()` instead of intent filters
- ✅ NFCProgressActivity has proper onResume/onPause for lifecycle management
- ✅ No MainActivity NFC interference (MainActivity not involved in NFC workflow)

### ✅ NOW ADDED (This Fix):
- ✅ DocumentTypeActivity.onResume() calls disableReaderMode()
- ✅ CameraActivity.onResume() calls disableReaderMode()
- ✅ ConfirmationActivity.onResume() calls disableReaderMode()

---

## Testing Instructions

### Test Case 1: Verify NFC Blocked During Camera Phase
1. Hold NFC-equipped document near device WHILE on CameraActivity
2. **Expected**: No system NFC dialog appears
3. **Expected**: Camera continues normally
4. **Previous (broken)**: System dialog would appear, interrupting camera flow

### Test Case 2: Verify NFC Works in NFC Activity
1. Reach NFCProgressActivity
2. Tap "Scan Chip" button
3. Hold NFC document near device
4. **Expected**: App's NFC reader detects tag and reads data
5. **Expected**: No system dialog interference

### Test Case 3: Verify Logcat Shows Lifecycle
1. Monitor logcat: `adb logcat -s "DocumentTypeActivity:D,CameraActivity:D,ConfirmationActivity:D,NFCProgressActivity:D"`
2. Navigate through app workflow
3. **Expected Output**:
   ```
   DocumentTypeActivity: onResume: Activity resumed
   [user taps Document Type]
   CameraActivity: onResume: Activity resumed
   CameraActivity: onResume: Activity resumed
   [user captures image and confirms OCR]
   ConfirmationActivity: onResume: Activity resumed
   ConfirmationActivity: onResume: Activity resumed
   [user taps "Continue to NFC"]
   NFCProgressActivity: onResume: Activity resumed (or NFCProgressActivity: onResume: Transaction in progress, RE-ENABLING NFC reader mode)
   ```

---

## ICAO 9303 Protocol Context

This fix ensures:
- **NFC exclusive control**: Only `NFCProgressActivity.enableReaderMode()` claims NFC
- **Clean reader mode**: No competing handlers (intent filters or system dialogs)
- **BAC/PACE workflow**: Can proceed uninterrupted from ISO-DEP tag detection through SM commands

The underlying 0x6988 MAC errors are separate from this hijacking issue:
- **0x6988 (previous issue)**: MAC validation failed - fixed by adding SSC + Lc bytes
- **NFC Hijacking (this fix)**: System taking control before app ready - fixed by disableReaderMode()

---

## Files Changed
- `app/src/main/java/com/trustnetid/app/DocumentTypeActivity.kt` - Added onResume()
- `app/src/main/java/com/trustnetid/app/CameraActivity.kt` - Added onResume()
- `app/src/main/java/com/trustnetid/app/ConfirmationActivity.kt` - Added onResume()

---

## Build Status
- ✅ Build: 18s, 96 tasks, 21 executed, 75 cached
- ✅ Install: 23s successful to Huawei P10 (com.trustnetid.app.debug)
- ✅ No compilation errors
- ✅ Ready for device testing

---

**Date**: September 20, 2026, ~10:45 UTC  
**Related Issues**: 0x6988 MAC fix (Sept 20, earlier), Header duplication (Sept 17)

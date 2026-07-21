# CAN Input Dialog - Feature Design (Plan Only - NOT IMPLEMENTED YET)

**Status**: 🔷 **DESIGN PHASE ONLY** - Awaiting passport testing before implementation  
**Date Created**: July 21, 2026  
**Implementation Timeline**: After successful passport scan testing  

---

## Overview

**Purpose**: Allow users to provide Card Access Number (CAN) when document authentication is required  
**Trigger**: Automatic detection of 6986 "Security Status Not Satisfied" error  
**User Interaction**: Simple dialog to enter 6-digit CAN  
**Result**: Retry file reads with PACE authentication using provided CAN  

---

## User Flow

### Current Flow (No Authentication)
```
┌─────────────┐
│ Scan Button │ 
└──────┬──────┘
       │
       ▼
  ┌──────────────────┐
  │ NFC Tag Detected │
  │ ICAO App Selected│
  │ (9000 success)   │
  └────────┬─────────┘
           │
           ▼
    ┌─────────────────┐
    │ Read Files      │
    │ (6986 error)    │
    └────────┬────────┘
             │
             ▼
    ┌──────────────────────┐
    │ Show Results Screen  │
    │ "[Protected - ...] " │
    └──────────────────────┘
```

### Planned Flow (With Authentication)
```
┌─────────────┐
│ Scan Button │ 
└──────┬──────┘
       │
       ▼
  ┌──────────────────┐
  │ NFC Tag Detected │
  │ ICAO App Selected│
  │ (9000 success)   │
  └────────┬─────────┘
           │
           ▼
    ┌─────────────────────┐
    │ Read Files (no CAN) │
    │ (6986 error)        │
    └────────┬────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ [NEW] Show CAN Dialog    │
    │ "Enter Card Access #"    │
    └────────┬─────────────────┘
             │
      ┌──────┴─────────┐
      │                │
      ▼                ▼
  Cancel           User Enters
                      CAN
                      │
                      ▼
              ┌─────────────────┐
              │ Retry with PACE │
              │ & CAN           │
              └────────┬────────┘
                       │
              ┌────────┴────────┐
              │                 │
              ▼                 ▼
          Success            Failure
              │                 │
              ▼                 ▼
    ┌──────────────────┐  ┌──────────────┐
    │ Show Real Data   │  │ Error Dialog │
    │ (files unlocked) │  │ Retry Option │
    └──────────────────┘  └──────────────┘
```

---

## Dialog Specifications

### Layout: activity_can_prompt.xml

**Visual Design**:
```
┌─────────────────────────────────────┐
│     TrustNet                        │
│  (Purple header, match main UI)     │
├─────────────────────────────────────┤
│                                     │
│   Authentication Required           │
│                                     │
│   Your document is protected.       │
│   Enter the Card Access Number      │
│   (CAN) to unlock information.      │
│                                     │
│   ┌─────────────────────────────┐   │
│   │ [XXXXXX      ] CAN (6 digits)  │  ← Input field
│   └─────────────────────────────┘   │
│                                     │
│   ℹ Where to find CAN:             │
│   Spanish ID: First line of MRZ*   │
│   Passport: Various locations      │
│   *MRZ = Machine Readable Zone     │
│                                     │
├─────────────────────────────────────┤
│  [AUTHENTICATE]  [CANCEL / SKIP]    │
└─────────────────────────────────────┘
```

### Components

#### 1. Header
- Purple background (#FF6200EE)
- White text "TrustNet"
- Consistent with activity_main.xml

#### 2. Content Area
- Title: "Authentication Required" (16sp, bold, purple)
- Explanation text (14sp, dark gray):
  ```
  "Your document is protected.
   Enter the Card Access Number (CAN)
   to unlock information."
  ```
- Help text (12sp, teal):
  ```
  "ℹ Where to find CAN:
   Spanish ID: First 6 chars of MRZ*
   *MRZ = Machine Readable Zone"
  ```

#### 3. Input Field
- EditText with:
  - Hint: "XXXXXX"
  - Input type: `android:inputType="number"`
  - Max length: 6 characters
  - Teal background (#FF03DAC5)
  - Dark text
  - Numeric keyboard only (Android auto)

#### 4. Buttons
- **AUTHENTICATE** button:
  - Background: Purple (#FF6200EE)
  - Text: White, bold
  - Only enabled when exactly 6 digits entered
  - Action: Call PACE authentication
  
- **CANCEL / SKIP** button:
  - Background: Gray
  - Text: White
  - Action: Return to scan screen (skip authentication)

---

## Implementation Strategy (NOT YET CODED)

### Trigger Point
**Location**: `MainActivity.kt` → `processNfcTag()` method

**Current Code**:
```kotlin
val governmentIdData = nfcReader.readFromTag(tag, lastScannedCAN)

if (governmentIdData != null) {
    showScanResult(governmentIdData)
} else {
    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
}
```

**Proposed Change** (pseudocode):
```kotlin
val governmentIdData = nfcReader.readFromTag(tag, lastScannedCAN)

if (governmentIdData != null) {
    showScanResult(governmentIdData)
} else if (lastScannedCAN.isEmpty()) {
    // Check if authentication was required (not just failed reads)
    val wasAuthenticationRequired = checkFor6986Error(tag)
    
    if (wasAuthenticationRequired) {
        // Show CAN prompt dialog
        showCANPromptDialog(tag)
    } else {
        // Generic error
        Toast.makeText(this, "Failed to read document", Toast.LENGTH_LONG).show()
    }
} else {
    // CAN was provided but failed
    Toast.makeText(this, "Authentication failed. Verify CAN and try again", Toast.LENGTH_LONG).show()
}
```

### Error Detection Method
**Concept**: Detect if 6986 error occurs (security status)

**Implementation Options**:

**Option A**: Return status code from reader
```kotlin
data class NfcReadResult(
    val data: GovernmentIDData?,
    val statusCode: Int? = null,  // 6986, 9000, etc.
    val requiresAuthentication: Boolean = false
)
```

**Option B**: Check with first file attempt
```kotlin
// Add method to reader
fun requiresAuthentication(tag: Tag): Boolean {
    // Try to read EF_COM without PACE
    // Return true if 6986, false otherwise
}
```

**Option C**: Use GovernmentIDData null + logging
```kotlin
// Current approach: if null + 6986 in logs
// Simple but requires log parsing
```

**Recommended**: Option A (cleanest, most explicit)

---

## Dialog Implementation Details

### File Structure (To Be Created)
```
app/src/main/res/layout/
└── dialog_can_prompt.xml              [NEW]

app/src/main/java/com/trustnet/app/
└── CANPromptDialog.kt                 [NEW] (optional)
   OR add methods to MainActivity       [Alternative]
```

### Dialog Methods to Add (To MainActivity or separate class)

```kotlin
private fun showCANPromptDialog(tag: Tag) {
    // Build dialog
    // Show EditText for CAN input
    // Setup AUTHENTICATE button with validation
    // Setup CANCEL button
    // Handle input
}

private fun onCANEntered(can: String, tag: Tag) {
    // Validate: must be exactly 6 digits
    if (can.length != 6 || !can.all { it.isDigit() }) {
        showError("CAN must be exactly 6 digits")
        return
    }
    
    // Retry with PACE
    attemptAuthenticationAndRetry(tag, can)
}

private fun attemptAuthenticationAndRetry(tag: Tag, can: String) {
    // Call nfcReader.readFromTag(tag, can)
    // If success: showScanResult()
    // If failure: showError() with retry option
}

private fun showCANPromptError(message: String) {
    // Toast or dialog with error message
    // Offer retry or cancel
}
```

### Input Validation
- **Before button enabled**: 
  - `input.length == 6`
  - `input.all { it.isDigit() }`
- **Real-time feedback**: Show character counter "1/6", "2/6", etc.
- **Auto-dismiss keyboard**: After CAN entered and validated

---

## Error Handling Strategy

### Possible Outcomes After CAN Entry

| Outcome | Behavior | UX |
| --- | --- | --- |
| **Correct CAN** | File reads succeed, data extracted | Show results screen with real data |
| **Wrong CAN** | PACE fails, stays 6986 | Error message, offer retry or cancel |
| **Network loss** | NFC connection drops | Error message, offer rescan |
| **Device removed** | Tag moved away | Error message, offer rescan |
| **PACE timeout** | Authentication takes too long | Error message with wait time hint |

### Error Messages
- ❌ "Authentication failed. CAN may be incorrect. Try again?"
- ❌ "CAN must be 6 digits"
- ❌ "Document disconnected. Hold ID closer and try again"
- ❌ "Authentication timeout. Please try again"
- ✅ "Authenticating..." (during PACE attempt)
- ✅ "Authentication successful!" (brief, then show results)

---

## CAN Format Reference

### Spanish National ID (DNI electrónico)
**Location**: Machine Readable Zone (MRZ) - second line  
**Format**: First 6 characters of MRZ  
**Example**: `DNI123456` → CAN is `123456`  
**Help Text**: "Look at the bottom of the card (machine readable zone)"

### Passport (ePassport)
**Location**: MRZ - last line  
**Format**: Characters 15-20  
**Example**: MRZ line ends in `12345<7` → CAN is `123457`  
**Help Text**: "Look at the MRZ at the bottom of your passport"

### Other Documents
- Can vary by country
- May use different names (PIN, PSK, etc.)
- App should guide user to correct location

---

## Design Considerations

### Accessibility
- Large text for input field (18sp minimum)
- High contrast (dark text on teal background)
- Clear error messages
- Keyboard auto-appears on Android

### Security
- ❌ **Do NOT**: Store CAN in SharedPreferences
- ❌ **Do NOT**: Log CAN value
- ✅ **Do**: Clear EditText after submission
- ✅ **Do**: Keep CAN in memory only during PACE attempt
- ✅ **Do**: Clear memory after PACE completes/fails

### UX Flow
1. User sees dialog appears after failed file read
2. User knows exactly what they need (CAN)
3. User knows where to find it (help text)
4. User enters 6 digits
5. App automatically retries with PACE
6. Result appears (data or error)

### Retry Logic
- **First failure**: "CAN incorrect? Try again or skip"
- **Second failure**: "Would you like to scan a different document?"
- **Third failure**: Suggest contacting support

---

## Testing Strategy (After Implementation)

### Unit Tests
- [ ] Input validation (6 digits, numeric only)
- [ ] Dialog appears on 6986 error
- [ ] PACE called with correct CAN
- [ ] Results display after success
- [ ] Error handling for wrong CAN

### Integration Tests
- [ ] Spanish ID with valid CAN
- [ ] Spanish ID with invalid CAN
- [ ] Passport (if requires PACE)
- [ ] Network interruption during PACE
- [ ] Device disconnection during PACE

### User Tests
- [ ] Dialog is clear and understandable
- [ ] Finding CAN on ID is easy
- [ ] Entering CAN is straightforward
- [ ] Error messages are helpful
- [ ] Retry flow makes sense

---

## Future Enhancements (Not in MVP)

### Phase 2 Improvements
- [ ] QR code scanning for CAN (instead of typing)
- [ ] Camera integration to read MRZ automatically
- [ ] Multi-language support for help text
- [ ] CAN history (remember last used, with option to clear)

### Phase 3 Improvements
- [ ] Biometric authentication (fingerprint instead of CAN)
- [ ] Document photo scanning for validation
- [ ] Signature verification workflow
- [ ] Blockchain submission

---

## Decision Point

**BEFORE IMPLEMENTING**:

This design should be reviewed AFTER passport testing to confirm:
1. ✅ Passport testing works (with or without auth)
2. ✅ User wants CAN dialog (vs other auth method)
3. ✅ Spanish ID testing confirms CAN format
4. ✅ PACE crypto works correctly
5. ✅ File parsing displays real data

**If passport works without auth**:
- May not need CAN dialog at all
- Can mark feature as "future enhancement"

**If passport requires CAN**:
- Implement this design exactly
- Test with valid CAN before shipping

**If user prefers QR scanning**:
- Modify design to add camera button
- Add QR code reading library

---

## Summary

| Aspect | Status | Ready? |
| --- | --- | --- |
| **Design** | ✅ Complete | Yes |
| **Layout Mockup** | ✅ Complete | Yes |
| **UX Flow** | ✅ Complete | Yes |
| **Error Handling** | ✅ Complete | Yes |
| **Implementation Ready** | ✅ Complete | Yes |
| **Implementation Status** | ⏳ Waiting | **NO - Hold for passport test** |

---

**Next Action**: Test with passport first, then proceed with implementation  
**Estimated Implementation Time**: 2-3 hours (if proceeding)  
**Estimated Testing Time**: 1-2 hours  
**Total Timeline**: 3-5 hours once approved

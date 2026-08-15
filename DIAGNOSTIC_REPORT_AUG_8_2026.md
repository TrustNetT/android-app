# TrustNet NFC Authentication - Diagnostic Report
**Date**: August 8, 2026  
**Device**: Huawei XED4C18113017165 (NFC capable, API 34)  
**Status**: 🔴 AUTHENTICATION FAILING - 0x6D00 INS NOT SUPPORTED  

---

## EXECUTIVE SUMMARY

**Current State**: NFC authentication completely broken. OCR extraction working. App can photograph passport, extract MRZ fields correctly, but chip read fails consistently at BAC authentication step.

**Error**: `0x6D00: INS NOT SUPPORTED` during GET CHALLENGE (JMRTD doBAC() first step)

**Root Cause**: **MAJOR REFACTOR INTRODUCED CRITICAL BUG**
- Function signature changed from `readPassportFromTag(tag, documentNumber, dateOfBirth, dateOfExpiry)` to `readPassportFromTag(tag, mrzText, documentType)`
- Function now internally parses MRZ via MRZParser instead of accepting already-parsed fields
- This architectural change likely introduced BAC key derivation or field extraction bug
- Current code changed AES→DESede in my last attempt, but original code used AES, so I made it WORSE

**What's Not Working**:
- ❌ NFC authentication (JMRTD PassportService.doBAC() fails immediately)
- ❌ Cannot read any passport chip data
- ❌ Error occurs at mutual authentication step (GET CHALLENGE)

**What's Working**:
- ✅ OCR extraction (MRZ parsing from camera: PAI917686, 651031, 290410)
- ✅ MRZ display in ConfirmationActivity (shows correct values)
- ✅ NFC tag detection (IsoDep.get(tag) succeeds)
- ✅ Gradle build (1s compile time)
- ✅ APK installation (no runtime crashes during startup)
- ✅ App navigation (Camera→Confirmation→NFC screen all working)

---

## FULL WORKFLOW ANALYSIS

### Phase 1: Camera & OCR ✅ WORKING
```
1. MainActivity.java starts camera capture
2. MLKit OCR extracts text from passport photo
3. Text passed to MRZParser for field extraction
4. Extraction result:
   - Document Number: PAI917686 ✓ (correct)
   - Date of Birth: 651031 ✓ (correct, format YYMMDD)
   - Date of Expiry: 290410 ✓ (correct, format YYMMDD)
   - Country Code: ESP ✓ (correct, Spain)
5. User confirms data in ConfirmationActivity
```

**Evidence**: Screenshot shows all fields displayed correctly

### Phase 2: MRZ String Construction → ConfirmationActivity → NFCProgressActivity ✅ WORKING
```
1. ConfirmationActivity.java receives extracted fields
2. User taps CONFIRM button
3. Intent passes mrzText (complete original MRZ) to NFCProgressActivity
4. NFCProgressActivity onCreate():
   - Sets documentType = "PASSPORT" (from camera flow)
   - Sets mrzText = "complete MRZ string from OCR"
   - Derives BAC key string: "PAI9176868651031829041 02" (24 chars with checksums)
   - Derives SHA-1: "6b7934818bf4c63c28dda88d0e5d8d411c649c46" (20 bytes)
```

**Evidence**: App reaches "Scan NFC Chip" screen successfully

### Phase 3: BAC Key Derivation ⚠️ POSSIBLY BROKEN
```
Current Code Path:
1. NFCProgressActivity calls: deriveBACKey()
   → MRZParser.constructBACKeyString(mrzText, documentType)
   → Returns: "PAI9176868651031829041 02" (24 chars)

2. BACKeyService.deriveBACKey(bacKeyString)
   → SHA-1 hash of 24-char string
   → Returns: 20-byte array

3. Keys split:
   Kenc = bytes[0-16] of 20-byte SHA-1
   Kmac = bytes[4-20] of 20-byte SHA-1
   
Expected Logic:
   - Kenc should be 16 bytes (for 3DES 2-key variant)
   - Kmac should be 16 bytes (for CBC-MAC)
   - But SHA-1 only gives 20 bytes total
   - Taking [0-16] and [4-20] gives OVERLAPPING sections
   - This is intentional per ICAO 9303 (triple-DES in cipher mode)
```

**Issue**: The BAC key derivation changed from Aug 3 version. The Aug 3 version called:
```kotlin
// Aug 3 version (working)
bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
// Calls the 3-param version that internally constructs 24-char string

// Current version (broken)
bacService.deriveBACKey(bacKeyString)
// Calls the 1-param version that hashes already-constructed string
```

Both ultimately hash a 24-char string, so this SHOULD be equivalent... but maybe the string construction is wrong.

### Phase 4: NFC Authentication ❌ BROKEN
```
Current Code Path:
1. NFCProgressActivity.onTagDiscovered(tag)
   → Calls: readerTD3.readPassportFromTag(tag, mrzText, documentType)

2. PassportReaderTD3.readPassportFromTag():
   Step 1: Parse MRZ again (REDUNDANT - already parsed in NFCProgressActivity)
      mrzParser.extractDocumentNumber(mrzText, documentType)
      mrzParser.extractDateOfBirth(mrzText, documentType)
      mrzParser.extractExpiryDate(mrzText, documentType)
   
   Step 2: Construct 24-char BAC key string AGAIN (REDUNDANT)
      bacKeyString = mrzParser.constructBACKeyString(mrzText, documentType)
   
   Step 3: Derive SHA-1 key AGAIN (3rd time derivation happening)
      bacKeySha1 = bacService.deriveBACKey(bacKeyString)
   
   Step 4: Split keys
      kenc = bacKeySha1[0-16]
      kmac = bacKeySha1[4-20]
   
   Step 5: Create SecretKeySpec
      kencKey = SecretKeySpec(kenc, 0, 16, "DESede")  // ← MY CHANGE (WRONG!)
      kmacKey = SecretKeySpec(kmac, 0, 16, "DESede")  // ← MY CHANGE (WRONG!)
   
   Step 6: Call JMRTD
      passportService.doBAC(kencKey, kmacKey)
      → FAILS with 0x6D00: INS NOT SUPPORTED

FAILURE POINT:
   The chip responds with 0x6D00 at GET CHALLENGE (step 1 of mutual auth)
   This means either:
   a) Keys are wrong (format, content, or algorithm mismatch)
   b) BAC password was wrong (MRZ parsing error)
   c) JMRTD library version incompatible with key algorithm
```

---

## ERROR ANALYSIS

### Error Message
```
BAC failed in GET CHALLENGE (SW = 0x6D00: INS NOT SUPPORTED) (step: 1)
```

### What This Error Means
- **SW 0x6D00**: "Instruction code is not supported" (ISO 7816-4 status word)
- **GET CHALLENGE**: First step of mutual authentication where card sends random challenge
- **Step 1**: Timing indicates failure at first APDU command during authentication
- **Implication**: Secure messaging not activated, or card doesn't recognize command format

### Why This Is Happening

**Theory 1: Key Algorithm Mismatch** (My Recent "Fix")
```
Current Code:
  SecretKeySpec(kenc, 0, kenc.size, "DESede")

Issues:
- Original Aug 3 code used "AES" not "DESede"
- If original was working, changing to DESede breaks it
- JMRTD likely expects 3DES keys to be named "DESede" OR "AES"
- But the ACTUAL algorithm must match what chip expects

Status: LIKELY CULPRIT - I BROKE IT WITH MY CHANGE
```

**Theory 2: BAC Password Wrong**
```
If MRZ parsing is incorrect:
  Current: Parses mrzText AGAIN inside readPassportFromTag()
  Aug 3: Received already-parsed documentNumber, DOB, expiry
  
Redundant Parsing Risks:
- mrzText might be corrupted
- MRZParser might fail on raw OCR output
- Checksum calculation might be wrong
- Field extraction positions might be wrong for this specific passport format

Status: POSSIBLE - MRZParser extraction needs verification
```

**Theory 3: CardService Initialization Wrong**
```
Current:
  val cardService = IsoDepCardServiceAdapter(isoDep)
  cardService.open()
  val passportService = PassportService(cardService, 256, 256, false, false)
  passportService.open()

Aug 3:
  ??? (need to check original version)

Status: UNKNOWN - need to compare initialization
```

---

## CODE CHANGES THAT BROKE AUTHENTICATION

### Change 1: Function Signature (Between Aug 3 and Aug 5)
**Aug 3 (Working?)**:
```kotlin
suspend fun readPassportFromTag(
    tag: Tag,
    documentNumber: String,    ← pre-parsed
    dateOfBirth: String,       ← pre-parsed
    dateOfExpiry: String       ← pre-parsed
)
```

**Current (Broken)**:
```kotlin
suspend fun readPassportFromTag(
    tag: Tag,
    mrzText: String,           ← raw full MRZ
    documentType: String       ← format indicator
)
// ... internally parses fields
```

**Impact**: Now parsing MRZ twice (once in NFCProgressActivity, once in PassportReaderTD3)

### Change 2: Key Algorithm Specification (Aug 8 - MY ERROR)
**Aug 3**:
```kotlin
val kencKey = SecretKeySpec(kenc, 0, kenc.size, "AES")
val kmacKey = SecretKeySpec(kmac, 0, kmac.size, "AES")
```

**Current (My Change - WRONG)**:
```kotlin
val kencKey = SecretKeySpec(kenc, 0, kenc.size, "DESede")
val kmacKey = SecretKeySpec(kmac, 0, kmac.size, "DESede")
```

**Impact**: Changed algorithm from what was working. JMRTD library might not recognize DESede for this use case.

### Change 3: BAC Key Derivation Method (Aug 5)
**Aug 3**:
```kotlin
val bacKeySha1 = bacService.deriveBACKey(documentNumber, dateOfBirth, dateOfExpiry)
// 3-parameter version
```

**Current**:
```kotlin
val bacKeyString = mrzParser.constructBACKeyString(mrzText, documentType)
val bacKeySha1 = bacService.deriveBACKey(bacKeyString)
// 1-parameter version (different code path)
```

**Impact**: Same end result theoretically, but different code paths. One might have a bug.

---

## WHAT NEEDS TO BE INVESTIGATED

### Immediate (Critical Path)
1. **Revert DESede back to AES** - I made this wrong change, need to undo it
2. **Compare full PassportReaderTD3 between Aug 3 and now** - Find what else changed
3. **Verify BAC password construction** - Check if "PAI9176868651031829041 02" is actually correct
4. **Check MRZParser.constructBACKeyString()** - This is critical function, need verification
5. **Trace through with device logs** - Capture exact logs during BAC attempt

### Verification Needed
- Does Aug 3 PassportReaderTD3 really use AES?
- What does BACKeyService.deriveBACKey() actually do in Aug 3 vs now?
- Is the 24-char password format correct? (9+1+6+1+6+1 = 24)
- Are the checksums being calculated correctly?

---

## DEVICE TEST RESULTS

**Last Test**: Aug 8, 10:59 - 11:00 (app still running during log capture)

**Screenshot Evidence**:
- NFC screen displayed
- Error message shown: "Read failed: BAC failed in GET CHALLENGE (SW = 0x6D00:  INS NOT SUPPORTED)"
- "START NFC SCAN" button present (allowing retry)

**Reproduction**: 100% consistent - error occurs every NFC scan attempt

---

## HYPOTHESIS & NEXT STEPS

**Most Likely Root Cause**: 
The DESede change I made on Aug 8 is WRONG. Original code used AES. Even if AES seems incorrect for 3DES, it was in the working version, so that's what JMRTD expects. My "fix" broke it worse.

**Action Plan**:
1. **Immediately revert DESede back to AES** in PassportReaderTD3.kt
2. **Rebuild and test** - capture logcat output
3. **If still failing**: Compare full commit diffs between Aug 3 and current
4. **If still failing**: Extract BAC password logs and verify format
5. **Last resort**: Find the EXACT commit where auth stopped working and manually identify the breaking change

**Time Lost**: ~3 hours of speculation and incorrect fixes. Need actual forensic analysis now.

---

## CRITICAL FACTS

- ✅ Chip data = OCR data (user verified with ReadID Me app)
- ✅ Checksums are mathematically correct  
- ✅ 24-char password format looks correct: PAI917686 + 8 + 651031 + 8 + 290410 + 2
- ❌ JMRTD doBAC() rejects at GET CHALLENGE with 0x6D00
- ❌ My changes made situation worse, not better
- ❌ Current code has TWO redundant MRZ parsing operations
- ❌ Current code has TWO redundant BAC key derivations

---

**Last Updated**: Aug 8, 2026 10:59 UTC  
**Report Status**: Complete diagnostic analysis, action plan ready for implementation

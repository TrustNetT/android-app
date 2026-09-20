# Diagnostic Report: 0x6988 Still Failing After APDU Fix - September 20, 2026

## Status
**Fix applied but still failing**: 0x6988 (MAC validation error) persists after correcting APDU payload extraction.

**Device behavior**: After 0x6988 error, device NFC system takes control.

---

## What Changed
Applied fix to `BacAndSmSession.wrapInSM()`:
```kotlin
// OLD (WRONG):
if (plainApdu.size > 4) {
    dataPayload = plainApdu.copyOfRange(4, plainApdu.size)  // Includes Lc!
}

// NEW (SHOULD BE RIGHT):
if (plainApdu.size > 5) {
    val lc = plainApdu[4].toInt() and 0xFF
    require(plainApdu.size >= 5 + lc) { "APDU length (${plainApdu.size}) smaller than Lc ($lc)" }
    dataPayload = plainApdu.copyOfRange(5, 5 + lc)  // Extract only data bytes
}
```

---

## Expected Flow for SELECT EF.COM

**Plain APDU passed to wrapInSM():**
```
[0x00, 0xA4, 0x02, 0x0C, 0x02, 0x01, 0x1E]
 CLA   INS   P1    P2    Lc    Data1 Data2
 [0]   [1]   [2]   [3]   [4]   [5]   [6]
```

**With the fix, extraction should be:**
```
plainApdu.size = 7
Check: 7 > 5 ? YES
lc = plainApdu[4].toInt() and 0xFF = 0x02
plainApdu.copyOfRange(5, 5+2) = plainApdu.copyOfRange(5, 7)
Result: dataPayload = [0x01, 0x1E] ✓ (correct)
```

**Encryption/MAC wrapping:**
```
1. Encrypt [0x01, 0x1E] with Kenc → encryptedData (likely ~8 bytes with PKCS5Padding)
2. Build DO87 = 0x87 || len(encryptedData) || encryptedData
3. Compute MAC:
   macInput = SSC || 0x00 || 0xA4 || 0x02 || 0x0C || len(DO87) || DO87
   MAC = first 8 bytes of 3DES-CBC-MAC(macInput, Kmac, IV)
4. Build DO8E = 0x8E || 0x08 || MAC
5. Final SM APDU = 0x0C || 0xA4 || 0x02 || 0x0C || len(DO87||DO8E) || DO87 || DO8E
```

---

## Hypothesis 1: Fix Didn't Get Published Correctly

**Check**: Did the fix actually make it into the device binary?

**Evidence**:
- WIP repo: Fixed ✓
- Commit: Created ✓
- Publish: Run ✓
- Rebuild: `clean assembleDebug` ✓
- Install: `install -r app-debug.apk` ✓

**Likelihood**: HIGH - Possible the fix wasn't actually compiled in.

**Verification**: Need to check compiled bytecode or add logging to confirm.

---

## Hypothesis 2: The Lc Condition is Wrong

**Current code:**
```kotlin
if (plainApdu.size > 5) {
    val lc = plainApdu[4].toInt() and 0xFF
    if (plainApdu.size >= 5 + lc) {
        dataPayload = plainApdu.copyOfRange(5, 5 + lc)
    }
}
```

**Problem**: What if `plainApdu.size == 5`?
- Condition `5 > 5` = false
- dataPayload stays empty `[]`
- DO87 wraps empty data
- MAC computed over SSC || header || empty DO87
- Chip expects MAC over non-empty DO87
- 0x6988 error

**For SELECT EF.COM:**
- plainApdu.size = 7 (not 5)
- So condition passes
- But what about READ BINARY commands with different sizes?

---

## Hypothesis 3: Issue is in Session Key Derivation, Not APDU Wrapping

**Current flow:**
```kotlin
fun deriveSessionKey(bacKey: ByteArray, rndIfc: ByteArray, rndIfd: ByteArray): ByteArray {
    val rndCombined = rndIfc + rndIfd  // 8 + 8 = 16 bytes
    val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
    val zeroIv = ByteArray(8)  // IV = zeros for key derivation
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(bacKey, ...), IvParameterSpec(zeroIv))
    val paddedRnd = ByteArray(24) { i -> if (i < rndCombined.size) rndCombined[i] else 0x00 }
    val encrypted = cipher.doFinal(paddedRnd)
    val sessionKey16 = encrypted.copyOfRange(0, 16)
    val sessionKey24 = expandDESKeyTo24Bytes(sessionKey16)
    return sessionKey24
}
```

**Potential issues:**
1. **paddedRnd construction**: Padding with zeros (0x00) for bytes 16-23 might be wrong
2. **sessionKey extraction**: Using first 16 bytes of encrypted output might be wrong
3. **Expansion pattern**: `A+B+A` pattern might not match chip's implementation

**JMRTD reference**: Would use standard key derivation from ICAO 9303

**Likelihood**: MEDIUM - But this would fail for both BAC and SM, and BAC succeeded

---

## Hypothesis 4: SelectCom() Plaintext Construction is Wrong

**Current code:**
```kotlin
val plainSelect = byteArrayOf(
    0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02, 0x01, 0x1E
)
```

**Potential issue**: Is this the CORRECT plain SELECT command?

**ISO/IEC 7816-4 SELECT FILE:**
```
CLA INS P1  P2  Lc  Data
00  A4  02  0C  02  01 1E
```

- CLA = 0x00 (No SM, command class standard)
- INS = 0xA4 (SELECT)
- P1 = 0x02 (Select by file ID)
- P2 = 0x0C (Return nothing / first occurrence)
- Lc = 0x02 (2 bytes of file ID follow)
- Data = 0x01 0x1E (EF.COM file ID)

**This looks correct per ISO standard.**

But wait - should CLA be 0x00 or something else for passport SM?

**ICAO 9303 requirement**: The original (plaintext) CLA should be whatever the command requires. When wrapped, CLA becomes 0x0C (SM bit set).

**So 0x00 is correct for plaintext.**

---

## Hypothesis 5: Condition Check Changed the Logic Unintentionally

**Old logic:**
```kotlin
if (plainApdu.size > 4) {
    dataPayload = plainApdu.copyOfRange(4, plainApdu.size)
}
```
- If size=5: extract [4] (only Lc byte, no data)
- If size=7: extract [4,5,6] (Lc + 2 data bytes) ✗

**New logic:**
```kotlin
if (plainApdu.size > 5) {
    val lc = plainApdu[4].toInt() and 0xFF
    dataPayload = plainApdu.copyOfRange(5, 5 + lc)
}
```
- If size=5: skip (leave dataPayload empty)
- If size=7: extract [5,6] (only 2 data bytes) ✓

**But**: What if there's a path that creates a plainApdu with size <= 5?

**For SELECT EF.COM**: size=7, so condition is met ✓

---

## Hypothesis 6: The Check Requires Size > 5 But Should Allow == 5

**Current check:**
```kotlin
if (plainApdu.size > 5) {  // Strictly greater than 5
```

**What if it should be:**
```kotlin
if (plainApdu.size >= 5) {  // Greater than or equal to 5
```

**Scenario**: A command with Lc=0 (no data) would have:
- plainApdu = [CLA, INS, P1, P2, Lc=0x00]
- plainApdu.size = 5
- Current code: Skips wrapping (condition false)
- Should code: Wraps with empty data (condition true)

**For SELECT EF.COM**: plainApdu.size=7, so not affected

---

## Critical Issue: No Logcat Output

**Problem**: The app isn't logging anything from BacAndSmSession or PassportReaderTD3.

**Possible causes:**
1. App crashed before reaching NFC code
2. Log tags are filtered out  
3. App process isn't running
4. DEX/bytecode wasn't recompiled

**Verification needed**: Check if app actually runs and reaches NFC logic.

---

## Verification Checklist

- [ ] Confirm fix is in compiled APK (decompile or add logging)
- [ ] Confirm app reaches `selectCom()` (add early Log statement)
- [ ] Confirm dataPayload extraction is correct (log the bytes)
- [ ] Confirm encrypted data matches expectations
- [ ] Confirm DO87 construction is correct
- [ ] Confirm MAC computation is correct
- [ ] Compare with JMRTD's PassportService implementation

---

## Next Steps

### 1. Add Diagnostic Logging to `wrapInSM()`

Add immediately after payload extraction:
```kotlin
Log.d(TAG, "DEBUG-PAYLOAD: plainApdu.size=${plainApdu.size}")
Log.d(TAG, "DEBUG-PAYLOAD: plainApdu=${plainApdu.toHexString()}")
if (plainApdu.size > 5) {
    val lc = plainApdu[4].toInt() and 0xFF
    Log.d(TAG, "DEBUG-PAYLOAD: lc=$lc")
    Log.d(TAG, "DEBUG-PAYLOAD: dataPayload=${dataPayload.toHexString()}")
}
```

### 2. Verify Plaintext APDU Construction

In `selectCom()`:
```kotlin
Log.d(TAG, "DEBUG-SELECT-COM: plainSelect=${plainSelect.toHexString()}")
// Should output: plainSelect=00a40202c0201e (or similar hex string)
```

### 3. Capture Wrapped APDU and Response

After `wrapInSM()`:
```kotlin
val smApdu = wrapInSM(plainSelect)
Log.d(TAG, "DEBUG-SM-WRAPPED: ${smApdu.toHexString()}")
val response = isoDep.transceive(smApdu)
Log.d(TAG, "DEBUG-SM-RESPONSE: ${response.toHexString()}")
```

### 4. Rebuild, Publish, Test

```bash
cd ~/GitProjects/TrustNet/trustnet-wip/android-app
git add -A && git commit -m "Diagnostic: Add SM wrapping logs"
cd ~/GitProjects/TrustNet/trustnet-wip
./tools/publish-trustnet -c
cd ~/GitProjects/TrustNet/android-app
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.trustnetid.app.debug/com.trustnetid.app.SplashActivity
# Go through workflow to NFC screen
adb logcat -d -s BacAndSmSession:D | grep DEBUG-
```

---

## Summary

| Aspect | Status |
|--------|--------|
| APDU payload extraction fix | Applied ✓ |
| Expected to work for SELECT EF.COM | Theory ✓ |
| Actual result on device | 0x6988 ✗ |
| Root cause identified | NO - needs diagnostic logs |
| Likely culprit | Session keys, IV, MAC algorithm, or fix not compiled |

---

**Report Generated**: September 20, 2026, ~11:30 UTC  
**Action Required**: Add diagnostic logging and re-test  
**Blocked By**: Need logcat output to proceed

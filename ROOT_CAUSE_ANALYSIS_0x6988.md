# Root Cause Analysis: 0x6988 MAC Error - September 20, 2026

## Executive Summary
The 0x6988 (MAC validation failure) error persists despite the September 20 fixes. Root cause analysis reveals a **critical bug in APDU payload extraction** that causes the Secure Messaging wrapper to include incorrect data in the MAC calculation.

**Status**: Root cause identified but NOT yet fixed  
**Severity**: CRITICAL - Prevents all SM-wrapped commands from succeeding  
**Impact**: SELECT EF.COM fails on first SM command, blocking all data extraction

---

## Problem Statement

When `selectCom()` is called, it sends a SM-wrapped SELECT command:
```
0C A4 02 0C [Lc] DO87 DO8E
```

The chip responds with `0x6988` (Security-related status words), indicating MAC validation failure.

**What this means**: The MAC (Message Authentication Code) computed by our app doesn't match the MAC the chip computed from the same data. This means:
1. Wrong key is used (unlikely - BAC completed successfully with 0x9000)
2. Wrong data is being MACed (likely)
3. Wrong IV is used (unlikely - derived from SSC which we control)
4. MAC algorithm is wrong (unlikely - using standard 3DES-CBC)

---

## Root Cause: APDU Payload Extraction Bug

### The Bug

In `BacAndSmSession.wrapInSM()`:

```kotlin
private fun wrapInSM(plainApdu: ByteArray): ByteArray {
    // Extract APDU components
    if (plainApdu.size < 4) throw IllegalArgumentException("APDU too short")
    
    val cla = plainApdu[0]   // 0x00
    val ins = plainApdu[1]   // 0xA4
    val p1 = plainApdu[2]    // 0x02
    val p2 = plainApdu[3]    // 0x0C
    
    var dataPayload = ByteArray(0)
    if (plainApdu.size > 4) {
        dataPayload = plainApdu.copyOfRange(4, plainApdu.size)  // ← BUG IS HERE
    }
    
    val encryptedData = encryptData(dataPayload, kenc, iv)
    val do87 = buildTLV(DO87_TAG, encryptedData)
    
    val lcByte = do87.size.toByte()
    val macInput = ssc!! + byteArrayOf(cla, ins, p1, p2, lcByte) + do87
    // ...
}
```

### What Happens

When `selectCom()` passes this plainApdu:
```
plainSelect = [0x00, 0xA4, 0x02, 0x0C, 0x02, 0x01, 0x1E]
             =  CLA   INS   P1    P2    Lc    Data1 Data2
             = [0]    [1]   [2]   [3]   [4]   [5]   [6]
```

Current code does: `plainApdu.copyOfRange(4, plainApdu.size)` = `[0x02, 0x01, 0x1E]`

**This includes the Lc byte (0x02) as part of the data!**

So the code encrypts: `[0x02, 0x01, 0x1E]` instead of just `[0x01, 0x1E]`

### Why This Causes 0x6988

The chip expects this for the MAC calculation:
```
MAC input = SSC || CLA || INS || P1 || P2 || Lc(DO87) || DO87
where DO87 wraps: ENC_Kenc([0x01, 0x1E])
```

But our app sends:
```
MAC input = SSC || CLA || INS || P1 || P2 || Lc(DO87') || DO87'
where DO87' wraps: ENC_Kenc([0x02, 0x01, 0x1E])  ← WRONG DATA!
```

The encrypted data is different, so:
- DO87' has different content
- Length of DO87' is different
- MAC computed over different DO87' value
- Chip recomputes MAC with CORRECT data [0x01, 0x1E], gets different result
- MACs don't match → 0x6988 error

---

## Comparison: What JMRTD Does Correctly

JMRTD's implementation properly extracts APDU fields:

```java
// JMRTD: Correct APDU parsing
int lc = plainCommandAPDU.getNc();  // Get Lc field explicitly
byte[] data = plainCommandAPDU.getData();  // Get data bytes
// Then: DO87 = ENC_Kenc(data)
// NOT: DO87 = ENC_Kenc([Lc, data])
```

Our code should do the same - extract Lc explicitly, then extract data separately.

---

## ICAO 9303 Standard Requirement

Per ICAO 9303 Section 7.2.4 (SM-protected commands):

```
Input APDU structure:
  Byte[0]    = CLA
  Byte[1]    = INS
  Byte[2]    = P1
  Byte[3]    = P2
  Byte[4]    = Lc (length of data)
  Byte[5..N] = Data (Lc bytes)

Secure Messaging wrapping:
  1. Extract: CLA, INS, P1, P2, data ONLY (NOT Lc)
  2. Encrypt data → EncData
  3. Build DO87 = 0x87 || len(EncData) || EncData
  4. Compute MAC input = SSC || CLA || INS || P1 || P2 || len(DO87) || DO87
  5. Compute MAC over MAC input → MAC (first 8 bytes)
  6. Build DO8E = 0x8E || 0x08 || MAC
  7. Build SM APDU = 0x0C || INS || P1 || P2 || len(DO87||DO8E) || DO87 || DO8E
```

**Critical**: Data excludes the Lc byte from the original APDU.

---

## The Fix (Not Yet Applied)

In `BacAndSmSession.wrapInSM()`, replace:

```kotlin
var dataPayload = ByteArray(0)
if (plainApdu.size > 4) {
    dataPayload = plainApdu.copyOfRange(4, plainApdu.size)  // ← WRONG
}
```

With:

```kotlin
var dataPayload = ByteArray(0)
if (plainApdu.size > 4) {
    val lc = plainApdu[4].toInt() and 0xFF
    if (plainApdu.size >= 5 + lc) {
        dataPayload = plainApdu.copyOfRange(5, 5 + lc)  // ← CORRECT
    }
}
```

### Why This Fixes It

- Now we skip the Lc byte (index 4)
- We extract exactly Lc bytes starting at index 5
- Encrypted data matches what chip expects
- MAC computed over correct data
- Chip's MAC validation passes
- 0x6988 error resolved

---

## Evidence

### Symptom
```
Select Command Sent to Chip:
  CLA=0x0C, INS=0xA4, P1=0x02, P2=0x0C
  DO87 contains: ENC_Kenc([0x02, 0x01, 0x1E])  ← INCLUDES Lc!
  DO8E (MAC) computed with wrong encrypted data

Chip Response:
  0x6988 - "Security-related status words"
  = "MAC received by the card cannot be checked / MAC incorrect"
```

### Why BAC Succeeded But SM Fails

- **BAC uses mutual authentication**, which doesn't wrap the command in DO87
- BAC sends: `GET CHALLENGE`, `EXTERNAL AUTHENTICATE` as raw APDUs
- We only extract CLA, INS, P1, P2 for these (no data parsing)
- So the bug doesn't affect BAC
- **After BAC, ALL commands must be wrapped in SM**
- SELECT EF.COM is the FIRST SM command, so the bug manifests here

---

## Timeline

| Date | Event |
|------|-------|
| Sept 17 | Header duplication fixed |
| Sept 18 | NFC analysis report generated |
| Sept 20 | SSC + Lc byte fixes applied (but Lc calculation was still wrong) |
| Sept 20 | NFC hijacking fix applied (disableReaderMode in pre-NFC activities) |
| Sept 20 | Device tested - still getting 0x6988 |
| Sept 20 | **ROOT CAUSE IDENTIFIED**: APDU parsing includes Lc byte in data |

---

## Verification Plan

### After Fix Applied:
1. Rebuild app
2. Install on device
3. Run full workflow: Document Type → Camera → OCR → Confirm → NFC Scan
4. Expected: SELECT EF.COM should succeed with 0x9000
5. If 0x6988 still occurs: Other SM issue exists (IV, MAC algorithm, key derivation)

### Logcat Monitoring:
```bash
adb logcat -s "BacAndSmSession:D,PassportReaderTD3:D" -d | grep -E "DO87|MAC|lcByte|dataPayload"
```

Expected after fix:
```
dataPayload should be [0x01, 0x1E] (NOT [0x02, 0x01, 0x1E])
DO87 size should be ~15 bytes (NOT ~17 bytes)
MAC should be computed over different data
```

---

## Related Code Files

| File | Issue |
|------|-------|
| [BacAndSmSession.kt](../nfc/BacAndSmSession.kt#L441) | Line 445: Incorrect dataPayload extraction |
| [PassportReaderTD3.kt](../nfc/PassportReaderTD3.kt#L124) | Calls selectCom() with plainSelect APDU |

---

## Summary Table

| Aspect | Details |
|--------|---------|
| **Error Code** | 0x6988 (MAC validation failure) |
| **Location** | BacAndSmSession.wrapInSM() line 445 |
| **Root Cause** | APDU payload extraction includes Lc byte |
| **Impact** | All SM-wrapped commands fail with 0x6988 |
| **Symptom** | Chip rejects MAC because we encrypted wrong data |
| **Fix** | Extract data starting at index 5 (after Lc), use Lc as length |
| **Complexity** | LOW - Single line fix |
| **Risk** | LOW - Only affects SM wrapping, not BAC or other components |

---

**Report Generated**: September 20, 2026, ~11:00 UTC  
**Status**: Ready for fix implementation  
**Next Action**: Apply fix, rebuild, test on device

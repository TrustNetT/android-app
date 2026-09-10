# ICAO 9303 Secure Messaging Implementation - Research Findings

**Date:** September 6, 2026  
**Purpose:** Reference guide for implementing SM APDU wrapping for TD3 passport DG file reading  
**Scope:** Post-BAC secure messaging for EF.COM, EF.SOD, DG1, DG11, etc.

---

## 1. Send Sequence Counter (SSC) - Section 7.2

### Initialization
After successful BAC mutual authentication:
- **SSC is 8 bytes**
- **Initial value:** Starts at `0x0000000000000001` (NOT zero)
- **Increment:** After each protected APDU (SELECT, READ BINARY, etc.), increment by 1 as 64-bit big-endian counter

### Increment Pattern
```
SSC[0] SSC[1] SSC[2] SSC[3] SSC[4] SSC[5] SSC[6] SSC[7]
(MSB)                                          (LSB)

Example:
0x0000000000000001 → 0x0000000000000002 → 0x0000000000000003 ...
```

### Important Notes
- SSC is NOT reset between file operations (it's a session-wide counter)
- If SSC reaches `0xFFFFFFFFFFFFFFFF`, rollover to `0x0000000000000001` (or error, depending on implementation)
- SSC state must be preserved across multiple readBinary() calls on same file

---

## 2. Initialization Vector (IV) Derivation - Section 7.2.1

### Standard Approach
**IV = SSC (first 8 bytes)**

Most passport implementations use the SSC directly as the CBC IV:
```
IV (8 bytes) = SSC (8 bytes)
```

This is simpler than deriving via additional key operations and is widely adopted.

### Cipher Parameters
- **Algorithm:** 3DES-CBC (also called TripleDES or TDEA)
- **Key size:** 24 bytes (168 bits)
- **Block size:** 8 bytes
- **Mode:** CBC (Cipher Block Chaining)
- **Padding:** PKCS#5 (same as PKCS#7 for 8-byte blocks)

---

## 3. Encrypted Data Object (DO87) - Section 7.2.2

### Structure
```
DO87 = 0x87 | Length | EncryptedData
```

### Encryption Details
- **Tag:** `0x87` (1 byte)
- **Length:** Length of EncryptedData (1-4 bytes, TLV short/long form)
  - For small files: 1 byte (value < 128)
  - For larger files: Multi-byte length encoding (BER long form)
- **EncryptedData:** Output of 3DES-CBC encryption

### Data to Encrypt
**For SELECT APDU (e.g., SELECT EF.COM):**
- Encrypt the **command data** part of the APDU
- Plain APDU: `00 A4 02 0C 02 01 1E` (SELECT FILE)
  - Header: `00 A4 02 0C` (CLA, INS, P1, P2)
  - Data: `02 01 1E` (length, file ID high, file ID low)
  - Encrypt: `02 01 1E` (the data portion)

**For READ BINARY APDU:**
- Plain APDU: `00 B0 [offset_hi] [offset_lo] [length]`
  - READ BINARY has no data portion (it's just header + length)
  - **Important:** If plainApdu has no data, EncryptedData is empty or omitted
  - Some implementations send DO87 with empty data, others omit DO87 entirely
  - (See Section 7.2.3 for exact rule)

### 3DES Encryption Example (Pseudocode)
```
cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kenc, 0, 24, "DESede"), new IvParameterSpec(iv))
encryptedData = cipher.doFinal(plainDataPayload)
do87 = buildTLV(0x87, encryptedData)
```

---

## 4. MAC Data Object (DO8E) - Section 7.2.3

### Structure
```
DO8E = 0x8E | 0x08 | MAC[0:8]
```

### MAC Computation Details
- **Tag:** `0x8E` (1 byte)
- **Length:** Always `0x08` (8 bytes of MAC)
- **MAC:** First 8 bytes of 3DES-CBC ciphertext (MAC is NOT truncated separately, it's the first block of CBC encryption)

### Input to MAC (Critical Order)
The MAC is computed over the **modified APDU header + DO87**:

```
MAC_Input = CLA | INS | P1 | P2 | DO87_tag | DO87_length | DO87_value
```

**Breaking it down:**
1. **CLA, INS, P1, P2:** From the original plainApdu (1 byte each = 4 bytes)
2. **DO87:** Complete TLV object (tag + length + value)
   - If plainApdu has no data, DO87 may be present but empty, or omitted entirely
   - (Specification is ambiguous here; common practice: include DO87 even if empty)

### 3DES-CBC MAC Example (Pseudocode)
```
cipher = Cipher.getInstance("DESede/CBC/NoPadding")
// Pad macInput to multiple of 8 bytes (PKCS#5)
paddedInput = padPKCS5(macInput, 8)
cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kmac, 0, 24, "DESede"), new IvParameterSpec(iv))
ciphertext = cipher.doFinal(paddedInput)
macValue = ciphertext.copyOfRange(0, 8)  // First 8 bytes
do8e = buildTLV(0x8E, macValue)
```

### Why MAC Input Includes Modified CLA
The MAC incorporates the **final (SM-enabled) CLA**, not the original CLA:
- **Not:** MAC over original plainApdu CLA
- **Yes:** MAC over SM-modified CLA (see Section 5 below)

This means:
1. Decide SM CLA first (see Section 5)
2. Compute DO87
3. Use **SM CLA** (not original CLA) in MAC_Input
4. Compute DO8E

---

## 5. Secure Messaging CLA Modification - Section 7.3

### CLA Byte Structure
```
Bit 7 | Bit 6 | Bit 5 | Bit 4 | Bit 3 | Bit 2 | Bit 1 | Bit 0
 -    |  -    |  -    | Secure Messaging | Command Chaining | Logical Channel (0-3)
```

### SM CLA Mapping (from Original Plaintext CLA)

| Original CLA | SM Enabled | SM+Chaining | Meaning |
|--------------|-----------|------------|---------|
| `0x00` | `0x0C` | N/A | Standard class, no chaining |
| `0x10` | `0x1C` | N/A | Extended logical channel class |
| `0x20` | `0x2C` | N/A | Chain bit set in original |
| `0x04` | `0x0C` | N/A | SM in original (shouldn't happen, but map to SM+standard) |

### Typical Rule for ePassport
Most TD3 passports use:
- **Original APDU CLA:** `0x00` (class = standard, no logical channel, no chaining, no SM)
- **SM-Wrapped APDU CLA:** `0x0C` (class = standard, SM enabled, command chaining disabled, channel 0)

**Calculation:** `SM_CLA = original_CLA | 0x0C`

But verify exact rule from your specific ePassport implementation (e.g., test with real chip).

---

## 6. Final SM APDU Assembly

### Structure
```
Final APDU = CLA_SM | INS | P1 | P2 | Lc | DO87 | DO8E | [Le]
```

### Details
- **CLA_SM:** Modified CLA (from Section 5)
- **INS, P1, P2:** From original plainApdu (unchanged)
- **Lc:** Total length of (DO87 + DO8E) = 1 byte (for small files)
- **DO87 + DO8E:** Encrypted data + MAC (in that order)
- **Le (optional):** If original plainApdu has Le (expected response length), include it
  - For SELECT: usually no Le
  - For READ BINARY: usually includes Le (e.g., `0x00` = 256 bytes max)

### Example: SM-Wrapped SELECT FILE
**Original:** `00 A4 02 0C 02 01 1E` (select EF.COM)

**After wrapping (example values):**
```
0C A4 02 0C [Lc=len(DO87+DO8E)] [DO87] [DO8E]
```

If DO87 (encrypted `02 01 1E`) = `87 04 [4 encrypted bytes]` (8 bytes)
And DO8E = `8E 08 [8 MAC bytes]` (10 bytes)
Then Lc = 18, final APDU = 23 bytes total

---

## 7. Response Handling

### Expected Response (Success Case)
After sending SM-wrapped APDU:
- **Plain case (no SM):** Raw response bytes + 2-byte status word (SW = `0x9000`)
- **SM case:** Usually chip responds without SM wrapping (unwrapped)
  - Response = plain file data (if SELECT) or READ BINARY data
  - Status word = `0x9000` (success)

### Error Cases
- **0x6987:** "SM DATA OBJECTS MISSING" → APDU wrapping was incorrect
- **0x6988:** "SM DATA OBJECTS INCORRECT" → MAC or encryption failed
- **0x6D00:** "CLASS NOT SUPPORTED" → CLA byte incorrect for this chip
- **0x6A82:** "FILE NOT FOUND" → File doesn't exist (correct SM, but file issue)

---

## 8. Implementation Checklist

- [ ] SSC initialization to `0x0000000000000001`
- [ ] SSC increment before each wrapInSM() call
- [ ] IV derivation (SSC → 8-byte IV)
- [ ] 3DES-CBC encryption of data payload
- [ ] DO87 TLV encoding
- [ ] MAC computation over CLA | INS | P1 | P2 | DO87
- [ ] 3DES-CBC MAC encryption (first 8 bytes)
- [ ] DO8E TLV encoding
- [ ] CLA modification for SM (`0x00` → `0x0C`)
- [ ] Final APDU assembly
- [ ] isoDep.transceive() call
- [ ] Response logging and status word check

---

## 9. References

- **ICAO 9303-11:2015** Machine Readable Travel Documents, Part 11 (Security and Machine Readability)
  - Section 7: Secure Messaging
  - Section 7.2: Cryptographic Mechanisms
  - Section 7.2.1-7.2.3: DO87, DO8E, SSC, IV
- **RFC 3394:** AES Key Wrap Algorithm (for completeness; not directly used in BAC-SM)
- **FIPS 46-3:** Data Encryption Standard (DES reference)
- **PKCS#5:** Password-Based Cryptography Standard (padding reference)

---

## 10. Known Challenges & Workarounds

### Challenge 1: Empty Data Payload (READ BINARY)
READ BINARY APDUs have no data to encrypt. Some implementations:
- **Option A:** Omit DO87 entirely (MAC computed without DO87)
- **Option B:** Include empty DO87 (tag + length=0)
- **Recommended:** Try Option B first (most compatible)

### Challenge 2: Response Wrapping
- Most chips respond with plain (unwrapped) data + `0x9000`
- Some chips may wrap responses (rare; usually spec-compliant chips don't)
- If you get `0x6987` repeatedly, verify the chip supports SM properly

### Challenge 3: Key Derivation Verification
- Kenc and Kmac derived correctly? Test with JMRTD's BACKey comparison
- If BAC `0x9000` but files return `0x6987`, SM key derivation likely correct (keys used for BAC auth)
- Issue is SM APDU wrapping, not key derivation

---

**Last Updated:** September 6, 2026  
**Status:** Ready for implementation

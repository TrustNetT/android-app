# ICAO 9303 MRZ Field Positions - TrustNet Reference

**Created**: August 5, 2026  
**Source**: ICAO 9303 Specification + NFCPassportReader Reference Implementation  
**Verified**: Based on working TD3 and TD1 formats

---

## TD3 FORMAT (Passports)

### MRZ Structure
```
Line 1 (44 characters): P<ISSUING_COUNTRY_CODE>SURNAME_NAMES<<<<<<<<<<<<<
Line 2 (44 characters): DOCNUMBER<DOCCHK_NATIONALITY<DOBDOB_CHKEXPIRYEXP_CHKSPECIAL

Position numbering: 0-indexed (standard programming)
```

### Line 2 Field Positions (for TD3 Passports)
```
Positions 0-8:    Document Number (9 digits) = substring(0, 9)
Position 9:       Document Number Checksum (1 digit) = substring(9, 10)
Positions 10-12:  Nationality (3 letters) = substring(10, 13)
Positions 13-18:  Date of Birth YYMMDD (6 digits) = substring(13, 19)
Position 19:      DOB Checksum (1 digit) = substring(19, 20)
Positions 20-25:  Date of Expiry YYMMDD (6 digits) = substring(20, 26)
Position 26:      Expiry Checksum (1 digit) = substring(26, 27)
Positions 27-43:  Special information (varies by country)
```

### Kotlin Extraction (TD3)
```kotlin
fun extractTD3Fields(mrz Line2: String): Map<String, String> {
    return mapOf(
        "documentNumber" to mrz.substring(0, 9),           // 9 chars
        "documentChecksum" to mrz.substring(9, 10),       // 1 char
        "nationality" to mrz.substring(10, 13),           // 3 chars
        "dateOfBirth" to mrz.substring(13, 19),           // 6 chars (YYMMDD)
        "dobChecksum" to mrz.substring(19, 20),           // 1 char
        "dateOfExpiry" to mrz.substring(20, 26),          // 6 chars (YYMMDD)
        "expiryChecksum" to mrz.substring(26, 27),        // 1 char
        "special" to mrz.substring(27, 44)                // Variable
    )
}
```

---

## TD1 FORMAT (ID Cards - 3 Lines)

### MRZ Structure
```
Line 1 (30 characters): I<ISSUING_COUNTRYCARDNUMBER<
Line 2 (30 characters): DOB_DOBCHKNATIONALITYEXPIRYEXP_CHK<
Line 3 (30 characters): NAME_NAMES<<<<<<<<<<<<<<<<<<<<

Position numbering: 0-indexed (standard programming)
```

### Line 2 Field Positions (for TD1 ID Cards)
```
Positions 0-5:    Date of Birth YYMMDD (6 digits) = substring(0, 6)
Position 6:       DOB Checksum (1 digit) = substring(6, 7)
Positions 7-9:    Nationality (3 letters) = substring(7, 10)
Positions 10-15:  Date of Expiry YYMMDD (6 digits) = substring(10, 16)
Position 16:      Expiry Checksum (1 digit) = substring(16, 17)
Positions 17-29:  Special information (varies by country)
```

### Kotlin Extraction (TD1)
```kotlin
fun extractTD1Fields(mrzLine2: String): Map<String, String> {
    return mapOf(
        "dateOfBirth" to mrzLine2.substring(0, 6),        // 6 chars (YYMMDD)
        "dobChecksum" to mrzLine2.substring(6, 7),        // 1 char
        "nationality" to mrzLine2.substring(7, 10),       // 3 chars
        "dateOfExpiry" to mrzLine2.substring(10, 16),     // 6 chars (YYMMDD)
        "expiryChecksum" to mrzLine2.substring(16, 17),   // 1 char
        "special" to mrzLine2.substring(17, 30)           // Variable
    )
}
```

---

## BAC KEY EXTRACTION (For NFC Authentication)

The BAC (Basic Access Control) key requires three fields concatenated:
```
BAC_Key = DocumentNumber + DateOfBirth + DateOfExpiry
Example: "123456789" + "800101" + "250101" = "12345678980010125010"
```

### For TD3 Passports
```kotlin
val bacKey = mrzLine2.substring(0, 9) +      // Document Number
             mrzLine2.substring(13, 19) +    // DOB
             mrzLine2.substring(20, 26)      // Expiry
// Result: 21 characters (9 + 6 + 6)
```

### For TD1 ID Cards
```kotlin
val documentNumber = mrzLine1.substring(5, 14)   // From Line 1
val bacKey = documentNumber +                     // Document Number
             mrzLine2.substring(0, 6) +           // DOB (from Line 2)
             mrzLine2.substring(10, 16)           // Expiry (from Line 2)
// Result: 21 characters (9 + 6 + 6)
```

---

## VALIDATION

### MRZ Line Length
- TD3: Line 1 = 44 chars, Line 2 = 44 chars
- TD1: Line 1 = 30 chars, Line 2 = 30 chars, Line 3 = 30 chars

### Common Extraction Errors
| Error | Cause | Fix |
|-------|-------|-----|
| Getting "2A1140" for expiry | OCR extracted wrong region of document | Verify OCR is capturing actual MRZ at bottom of document |
| Off-by-one errors | Using wrong substring indices | Remember: `substring(start, end)` where end is EXCLUSIVE |
| Missing check digits | Forgetting positions have checksums | Check digit positions: 9, 19, 26 for TD3; 6, 16 for TD1 |
| Spaces in extracted data | OCR includes whitespace | Remove all spaces: `.replace(" ", "")` |

---

## References

**ICAO 9303 Standard**: Doc 9303, Part 1 (Machine Readable Travel Documents)  
**Working Reference**: https://github.com/AndyQ/NFCPassportReader (NFCPassportReader Swift implementation)  
**JMRTD Library**: https://github.com/JMRTD/jmrtd (Reference for standard compliance)


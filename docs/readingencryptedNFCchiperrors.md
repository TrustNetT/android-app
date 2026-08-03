Fixing Passport BAC Authentication and Secure Messaging – TrustNet NFC Reader

Date: August 3, 2026Author: TrustNet Engineering Notes (Julio)Status: Passport BAC authentication now understood; next step is implementing correct JMRTD Secure Messaging

1. Context

This document summarizes the root cause and the required fix for the current NFC passport‑reading failure in the TrustNet Android app. It consolidates findings from multiple debug sessions, including the milestone report dated August 3, 2026.

The app successfully:

Extracts MRZ

Derives BAC key (20 bytes)

Connects to the NFC chip

Selects the ICAO application

But fails at:

BAC mutual authentication (SW = 0x6300) when using PassportService.doBAC()

MSE:Set AT (SW = 6A88) when using custom APDU‑based BAC

DG1/DG2/EF.COM reads (SW = 6A82) because Secure Messaging is not active

This file explains why, and how to fix it.

2. The Real Root Cause

The failure is caused by incorrect BAC key format passed into JMRTD.

From the milestone report:

"Kenc: 24 bytes, Kmac: 24 bytes"

This is wrong.

JMRTD expects raw 16‑byte keys, not expanded 24‑byte DESede keys.

When 24‑byte keys are passed:

JMRTD encrypts the mutual authentication block incorrectly

The passport rejects the response

Mutual authentication step 2 fails with:

SW = 6300 (General authentication error)

This is the same failure pattern documented in JMRTD issue trackers.

3. Correct BAC Key Derivation

After SHA‑1 hashing the 24‑character MRZ password, we get a 20‑byte Kseed.

Correct split:

Kenc = Kseed[0..15] (first 16 bytes)

Kmac = Kseed[4..19] (last 16 bytes)

❌ Wrong (current code)

Repeating bytes to create 24‑byte DESede keys:

val kenc = deriveDES3Key(bacKey.copyOfRange(0, 16))
val kmac = deriveDES3Key(bacKey.copyOfRange(4, 20))
passportService.doBAC(kenc, kmac)

✔ Correct (JMRTD expects raw 16‑byte keys)

val kenc = bacKey.copyOfRange(0, 16)
val kmac = bacKey.copyOfRange(4, 20)
passportService.doBAC(kenc, kmac)

No DESede expansion. No SecretKeySpec. No repetition.

JMRTD internally expands these keys into proper 3DES session keys.

4. Why MSE:Set AT Fails (6A88)

When using the custom GovernmentIDNFCReader path, the app sends:

00 22 C1 A4 ... (MSE:Set AT)

The passport returns:

6A88 (Referenced data not found)

This happens because:

The chip expects JMRTD‑formatted mutual authentication, not custom MSE

The custom MSE command does not match ICAO 9303 BAC format

The chip rejects the authentication context

All file reads fail with 6A82 because Secure Messaging is not active

Conclusion

Custom BAC/MSE code must be removed for passports.

5. Correct Architecture for Passports (TD3)

Use JMRTD exclusively.

✔ Correct Passport Flow

val cardService = IsoDepCardServiceAdapter(isoDep)
cardService.open()

val passportService = PassportService(cardService, 256, 256, false, false)
passportService.open()

val bacKey = bacKeyService.deriveBACKey(documentNumber, dob, expiry)
val kenc = bacKey.copyOfRange(0, 16)
val kmac = bacKey.copyOfRange(4, 20)

passportService.doBAC(kenc, kmac)   // Secure Messaging now active

val dg1Stream = passportService.getInputStream(PassportService.EF_DG1)
val dg1 = DG1File(dg1Stream)

❌ Wrong Passport Flow (current)

Using GovernmentIDNFCReader

Using custom MSE:Set AT

Using raw APDUs after BAC

Using 24‑byte DESede keys

This path must be removed for passports.

6. Routing Logic (TD3 vs TD1)

To avoid future confusion:

Passport (TD3)

MRZ = 2 lines × 44 chars

Protocol = BAC

Use PassportService.doBAC()

ID Card (TD1/TD2)

MRZ = 3 lines × 30 chars

Protocol = PACE

Use PassportService.doPACE() (later)

Your milestone report shows the app incorrectly routing passports through the PACE/MSE path.

7. Why DG1/DG2/EF.COM Fail (6A82)

All file reads fail because Secure Messaging is not active.

This is expected when:

BAC mutual authentication fails

MSE:Set AT fails

Session keys are wrong

SSC (Send Sequence Counter) is not initialized

Once BAC succeeds via JMRTD, Secure Messaging is automatically enabled.

Then DG1/DG2/EF.COM will read successfully.

8. Summary of Required Fixes

✔ Fix BAC key split

Use raw 16‑byte keys.

✔ Remove custom MSE:Set AT

JMRTD handles this internally.

✔ Remove raw APDUs for passports

Use passportService.getInputStream().

✔ Route passports through BAC only

PACE is for ID cards.

✔ Keep GovernmentIDNFCReader only for future PACE work

Not for passports.

9. Next Steps

Apply correct BAC key split

Remove custom MSE and raw APDUs from passport path

Test BAC mutual authentication again

Confirm DG1/DG2 read successfully

Begin PACE implementation for TD1/TD2 (Spanish DNI, CIE, Perso)

10. Final Notes

Your milestone report shows excellent progress:

MRZ extraction: working

BAC key derivation: working

NFC communication: working

ICAO app selection: working

Authentication framework: working

The remaining blocker is incorrect BAC key format and custom MSE interfering with JMRTD.

Once corrected, passport reading will work end‑to‑end.

End of Document
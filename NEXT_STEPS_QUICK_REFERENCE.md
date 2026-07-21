# Quick Reference - Next Steps (Awaiting Test Document)

**Date**: July 21, 2026  
**Status**: ⏸️ Project ON HOLD  
**Waiting For**: Passport or valid CAN for Spanish ID  

---

## Checklist: What to Do When Passport Arrives

### Step 1: Prepare Device
- [ ] Ensure Huawei device is connected via USB
- [ ] Check: `adb devices` shows device ID
- [ ] NFC enabled: `adb shell settings put secure nfc_on 1`
- [ ] APK already installed (from previous session)

### Step 2: Run Current App
```bash
# Clear logs
adb logcat -c

# Launch app
adb shell am start -n com.trustnet.app/.SplashActivity

# Monitor logs
adb logcat -s "MainActivity:D,GovernmentIDNFCReader:D,PACEAuthenticator:D"
```

### Step 3: Test Scan (No CAN)
1. Tap "TAP TO SCAN NFC" button
2. Hold passport to phone (back of phone, upper area typically has NFC coil)
3. Wait for scan to complete
4. **OBSERVE**: Results screen should appear

### Step 4: Document Result
If results screen shows real data (NOT "[Protected...]"):
- ✅ Passport works without authentication!
- ✅ Skip CAN dialog implementation
- ✅ Proceed to Phase 2 (document parsing)
- Create session note: "Passport_Testing_SUCCESS.md"

If results screen shows "[Protected...]":
- ⚠️ Passport also requires authentication
- ✅ Implement CAN dialog (follow CAN_PROMPT_DESIGN.md)
- Test with MRZ data if available
- Create session note: "Passport_Testing_REQUIRES_AUTH.md"

### Step 5: Check Logs
Look for these patterns in `adb logcat` output:

**SUCCESS (files readable)**:
```
GovernmentIDNFCReader: Successfully read government ID data (XXX raw bytes, XXX biometric bytes)
```

**BLOCKED (authentication required)**:
```
GovernmentIDNFCReader: File read failed: SW1=69 SW2=86
```

**PACE ATTEMPTED** (if CAN provided):
```
PACEAuthenticator: PACE authentication successful - files are now accessible
OR
PACEAuthenticator: PACE authentication failed: ...
```

---

## Decision Tree

```
┌──────────────────────────────┐
│ Scan Passport with Current   │
│ App (No CAN)                 │
└──────────────┬───────────────┘
               │
       ┌───────┴────────┐
       │                │
       ▼                ▼
    [DATA]          [PROTECTED]
    Shows               Shows
    Values              6986
    │                   │
    ▼                   ▼
✅ SUCCESS          ⚠️ NEED AUTH
└─────┬──────────┬──────────┘
      │          │
      │          ├──→ Try with MRZ data from passport
      │          │    (If CAN embedded in passport)
      │          │
      │          └──→ Need to implement CAN_PROMPT_DESIGN.md
      │
      └──→ Move to Phase 2:
           Document Parsing
           & Signature Validation
```

---

## Files to Reference

| File | Purpose | Location |
| --- | --- | --- |
| PROJECT_STATUS_JULY21.md | Full status report | `~/GitProjects/android-app/` |
| CAN_PROMPT_DESIGN.md | Exact feature design (ready to code) | `~/GitProjects/android-app/` |
| PACE_INTEGRATION_COMPLETED_July21.md | This session's work | Session memory |
| GovernmentIDNFCReader.kt | Current NFC code | `app/src/main/java/com/trustnet/app/` |
| MainActivity.kt | UI and scan flow | `app/src/main/java/com/trustnet/app/` |

---

## Common Issues & Solutions

### Issue: "NFC not available" message on launch
**Solution**: 
```bash
adb shell settings put secure nfc_on 1
```

### Issue: "Successfully scanned but shows [Protected]" on all fields
**Expected**: This means authentication is required (needs CAN or PACE)  
**Next**: Either get CAN or wait for passport that doesn't require it

### Issue: App crashes on scan
**Check**: 
```bash
adb logcat | grep -i "exception\|error"
```
Save log output and share for debugging

### Issue: Results screen doesn't appear
**Check**:
```bash
adb logcat -s "MainActivity:D" | grep -i "showing\|result"
```
Verify `showScanResult()` method is being called

---

## Testing Commands

### Full Clean Test
```bash
# Clear everything
adb logcat -c
adb shell am force-stop com.trustnet.app

# Launch fresh
adb shell am start -n com.trustnet.app/.SplashActivity

# Hold passport to phone...

# Capture output
adb logcat -s "MainActivity:D,GovernmentIDNFCReader:D" > ~/Desktop/nfc_test.log

# Review after test
cat ~/Desktop/nfc_test.log | tail -30
```

### Quick Scan Test (with logs)
```bash
adb logcat -s "GovernmentIDNFCReader:D" --max-count=15
# Then scan passport
# Logs will show result
```

### PACE Test (if CAN available)
```bash
# Modify MainActivity.kt before building:
# In onCreate(): setCardAccessNumber("XXXXXX")

# Build & install
cd ~/GitProjects/android-app
./gradlew clean build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Test
adb logcat -s "PACEAuthenticator:D"
# Then scan
```

---

## What to Document After Testing

Create new session notes with:
1. **Document type tested** (passport make/model if known)
2. **Results** (data shown or protected)
3. **Logs** (key messages from adb logcat)
4. **Behavior** (what worked, what didn't)
5. **Next action** (what to do based on results)

**Template**:
```markdown
# Passport Testing Results (Date)

## Document Tested
- Type: Passport
- Country: [if known]
- Age: [if known]

## Results
- NFC detected: ✅ Yes
- Files readable: ✅ Yes / ⚠️ No (6986)
- Data extracted: [List fields shown]

## Screenshots
[Optional: attach phone screenshots]

## Logs
[Paste key log lines from adb logcat]

## Next Steps
[Based on results, what to do next]
```

---

## If Can't Get Passport

### Alternative 1: Get Valid CAN for Spanish ID
1. Find first 6 characters of Machine Readable Zone (MRZ)
2. Use: `MainActivity.setCardAccessNumber("XXXXXX")`
3. Test with current code
4. Proceed with CAN dialog implementation if needed

### Alternative 2: Use Different Test Document
- Check if any other government ID available
- Different countries may have different auth requirements
- Some older passports may not require PACE

### Alternative 3: Simulate with Mock Data
- Read PROJECT_STATUS_JULY21.md for file formats
- Create mock ICAO response data
- Allows testing UI without real NFC
- Less valuable but better than waiting indefinitely

---

## Success Criteria (Definition of Done)

**Minimum** (one of these):
- [ ] Passport scan works, real data displays
- [ ] Spanish ID with valid CAN works, real data displays

**Ideal** (both):
- [ ] Passport tested
- [ ] Spanish ID with CAN tested

**Outcome**:
- [ ] Results documented
- [ ] Next phase (parsing/validation) can proceed
- [ ] OR CAN dialog implemented + tested

---

## No Code Changes Needed Yet ⚠️

**Remember**: Do NOT modify code until after testing  

**Current state**:
- ✅ All code compiles and runs
- ✅ UI works perfectly  
- ✅ PACE framework ready
- ✅ APK already on device

**When testing**:
- Just scan and observe behavior
- Don't modify code unless debugging crash

**After testing**:
- If passport shows real data: Celebrate! ✅
- If passport needs PACE: Implement CAN_PROMPT_DESIGN.md

---

**Ready to go!** Just waiting for test document. 📱

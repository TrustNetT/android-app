# Android Registration Module - Replication Strategy

**Date**: March 18, 2026  
**Status**: Planned (after iOS registration complete)  
**Scope**: Identical registration flow as iOS, adapted for Android platform  
**Target**: Android 11+ (for NFC capability)

---

## Android Implementation Strategy

**Key Principle**: Identical user flow as iOS, different native platform implementation

**Timeline**: Start after iOS registration achieves "success criteria"

---

## Architecture Parity with iOS

| Component | iOS | Android | Status |
|-----------|-----|---------|--------|
| NFC Reading | CoreNFC | Android NFC API | Same input (ICAO 9303 data) |
| Facial Geometry | Vision Framework | MediaPipe / ML Kit | Same hash output |
| Key Generation | CryptoKit | Android Keystore | Same Ed25519 signing |
| Key Storage | iOS Keychain | Android Keystore | Same security level |
| Blockchain Submission | URL Session | Retrofit / OkHttp | Same transaction format |
| UI Framework | SwiftUI | Kotlin Compose | Different syntax, same flow |

---

## Shared Components (Single Source of Truth)

These can be implemented once in Swift/Kotlin and shared:

### 1. Registration Flow Logic
```
User → NFC Scan → Biometric Hash → UserID Generation → Blockchain Submit → Success
```

This sequence is **identical on both platforms**—only implementation language differs.

### 2. ICAO 9303 Validation
- Input: Raw NFC data from passport/ID
- Output: Valid government ID data or error
- Implementation: Can be shared via WebAssembly or separate implementations per language

### 3. UserID Generation Algorithm
```
SHA256(biometricHash + publicKey + communityID + timestamp)
```
Algorithm is deterministic—same inputs produce same UserID on iOS and Android.

### 4. Keychain/Secure Storage
Both platforms have hardware-backed secure storage:
- iOS: iOS Keychain (Secure Enclave)
- Android: Android Keystore (TEE - Trusted Execution Environment)
Both protect private keys identically.

---

## Android File Structure (Parallel to iOS)

```
android/
├── app/
│   └── src/
│       ├── main/
│       │   ├── kotlin/
│       │   │   └── com/trustnet/
│       │   │       ├── ui/
│       │   │       │   └── RegistrationScreen.kt      (Jetpack Compose)
│       │   │       ├── nfc/
│       │   │       │   ├── GovernmentIDReader.kt       (NFC API)
│       │   │       │   └── ICAO9303Validator.kt
│       │   │       ├── crypto/
│       │   │       │   ├── KeyGenerator.kt             (Android Keystore)
│       │   │       │   ├── BiometricHasher.kt          (ML Kit)
│       │   │       │   └── UserIDGenerator.kt
│       │   │       ├── blockchain/
│       │   │       │   ├── BlockchainConnector.kt      (Retrofit)
│       │   │       │   └── RegistrationTransactor.kt
│       │   │       ├── storage/
│       │   │       │   ├── KeystoreManager.kt          (Android Keystore API)
│       │   │       │   └── UserDataManager.kt
│       │   │       └── models/
│       │   │           ├── GovernmentID.kt
│       │   │           ├── User.kt
│       │   │           └── RegistrationState.kt
│       │   └── AndroidManifest.xml
│       └── test/
│           └── kotlin/
│               └── ... (unit + integration tests)
└── build.gradle.kts
```

---

## Platform-Specific Implementations

### 1. NFC Reading (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/nfc/GovernmentIDReader.kt`

```kotlin
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep

class GovernmentIDReader(context: Context) {
    
    private val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
    
    fun isNfcAvailable(): Boolean = nfcAdapter?.isEnabled == true
    
    fun startReading(callback: (GovernmentID?, Exception?) -> Unit) {
        // Register intent filter for NFC discovery
        // Android system will deliver NFC intents when ID is scanned
    }
    
    fun processNfcTag(tag: Tag, callback: (GovernmentID?, Exception?) -> Unit) {
        try {
            val isoDep = IsoDep.get(tag)
            isoDep.connect()
            
            // Send APDU commands to read passport chip
            val ndefMessages = readNFC(isoDep)
            
            // Parse ICAO 9303 data
            val idData = parseICOA9303(ndefMessages)
            
            // Validate government signature via ICAO9303Validator
            val validator = ICAO9303Validator()
            if (validator.validate(idData.rawData, idData.signature, idData.countryCode)) {
                callback(idData, null)
            } else {
                callback(null, Exception("Invalid government signature"))
            }
            
            isoDep.close()
        } catch (e: Exception) {
            callback(null, e)
        }
    }
    
    private fun readNFC(isoDep: IsoDep): ByteArray {
        // APDU sequence to read passport data
        // Same ICAO 9303 protocol as iOS
        val selectCommand = byteArrayOf(
            0x00, 0xA4, 0x04, 0x00, 0x07,
            0xA0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        return isoDep.transceive(selectCommand)
    }
    
    private fun parseICOA9303(ndefMessages: ByteArray): GovernmentID {
        // Parse same ICAO 9303 structure as iOS
        // Return GovernmentID object
        return GovernmentID(
            fullName = "...",
            dateOfBirth = Date(),
            documentNumber = "...",
            biometricTemplate = byteArrayOf(),
            governmentSignature = byteArrayOf(),
            isValid = true
        )
    }
}
```

### 2. Biometric Hashing (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/crypto/BiometricHasher.kt`

```kotlin
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import java.security.MessageDigest

class BiometricHasher {
    
    private val faceDetector: FaceDetector = FaceDetection.getClient()
    
    fun hashBiometricData(
        facialBitmap: Bitmap,
        biometricTemplate: ByteArray?
    ): String {
        // Extract facial landmarks using ML Kit
        val landmarks = extractFacialLandmarks(facialBitmap)
        
        // Combine with biometric template
        val combinedData = ByteArray(landmarks.size + (biometricTemplate?.size ?: 0))
        var offset = 0
        
        // Copy facial geometry
        landmarks.forEachIndexed { index, byte ->
            combinedData[offset + index] = byte
        }
        offset += landmarks.size
        
        // Copy biometric template
        biometricTemplate?.forEachIndexed { index, byte ->
            combinedData[offset + index] = byte
        }
        
        // SHA-256 hash
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combinedData)
        
        // Convert to hex string
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun extractFacialLandmarks(bitmap: Bitmap): ByteArray {
        val inputImage = InputImage.fromBitmap(bitmap, TextureRotation.ROTATION_0)
        val faces = faceDetector.process(inputImage)
        
        val landmarkData = ByteArrayOutputStream()
        
        faces.forEach { face ->
            // Extract all landmark positions
            face.allLandmarks.forEach { landmark ->
                val x = landmark.position.x.toFloat().toByteArray()
                val y = landmark.position.y.toFloat().toByteArray()
                landmarkData.write(x)
                landmarkData.write(y)
            }
        }
        
        return landmarkData.toByteArray()
    }
}
```

### 3. Key Generation (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/crypto/KeyGenerator.kt`

```kotlin
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore

class KeyGenerator {
    
    companion object {
        private const val KEY_ALIAS = "trustnet_identity_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
    
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        // Use Android Keystore for hardware-backed storage
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        
        // If key exists, delete it
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        
        // Generate Ed25519 keypair
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("prime256v1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)  // Can change to require biometric/PIN
            .build()
        
        keyPairGenerator.initialize(parameterSpec)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Serialize keys
        val publicKeyBytes = keyPair.public.encoded
        // Private key stays in Keystore (don't extract)
        
        return Pair(ByteArray(0), publicKeyBytes)  // Private key not extracted
    }
    
    fun signMessage(message: ByteArray, userID: String): ByteArray {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(message)
        
        return signature.sign()
    }
}
```

### 4. Blockchain Submission (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/blockchain/RegistrationTransactor.kt`

```kotlin
import com.squareup.okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.POST
import retrofit2.http.Body
import kotlinx.coroutines.delay

class RegistrationTransactor(
    nodeURL: String = "http://localhost:26657"
) {
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(nodeURL)
        .client(OkHttpClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val api = retrofit.create(TrustNetAPI::class.java)
    
    suspend fun registerIdentity(
        userID: String,
        publicKey: ByteArray,
        biometricHash: String,
        displayName: String,
        communityID: String = "global",
        signature: ByteArray
    ): String {
        val transaction = RegistrationTransaction(
            userID = userID,
            publicKey = publicKey.encodeToByteString(),
            biometricHash = biometricHash,
            displayName = displayName,
            communityID = communityID,
            timestamp = System.currentTimeMillis() / 1000.0,
            signature = signature.encodeToByteString()
        )
        
        // Submit to blockchain
        val response = api.submitTransaction(transaction)
        val txHash = response.tx_hash
        
        // Wait for confirmation
        val confirmed = waitForConfirmation(txHash, timeout = 10000)
        
        if (!confirmed) {
            throw Exception("Blockchain confirmation timeout")
        }
        
        return txHash
    }
    
    private suspend fun waitForConfirmation(txHash: String, timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                val status = api.getTransactionStatus(txHash)
                if (status.confirmed) {
                    return true
                }
            } catch (e: Exception) {
                // Retry
            }
            
            delay(1000)
        }
        
        return false
    }
}

interface TrustNetAPI {
    
    @POST("/submit_tx")
    suspend fun submitTransaction(
        @Body transaction: RegistrationTransaction
    ): TransactionResponse
    
    @GET("/tx_status/{txHash}")
    suspend fun getTransactionStatus(
        @Path("txHash") txHash: String
    ): TransactionStatusResponse
}

data class RegistrationTransaction(
    val userID: String,
    val publicKey: String,  // Base64
    val biometricHash: String,
    val displayName: String,
    val communityID: String,
    val timestamp: Double,
    val signature: String    // Base64
)

data class TransactionResponse(
    val tx_hash: String,
    val code: Int
)

data class TransactionStatusResponse(
    val confirmed: Boolean,
    val height: Long
)
```

### 5. Secure Storage (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/storage/KeystoreManager.kt`

```kotlin
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class KeystoreManager(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "trustnet_encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveUserID(userID: String) {
        encryptedPrefs.edit().putString("user_id", userID).apply()
    }
    
    fun retrieveUserID(): String? {
        return encryptedPrefs.getString("user_id", null)
    }
    
    fun savePublicKey(publicKeyBase64: String) {
        encryptedPrefs.edit().putString("public_key", publicKeyBase64).apply()
    }
    
    fun retrievePublicKey(): String? {
        return encryptedPrefs.getString("public_key", null)
    }
    
    fun saveBiometricHash(hash: String) {
        encryptedPrefs.edit().putString("biometric_hash", hash).apply()
    }
    
    fun saveTxHash(txHash: String) {
        encryptedPrefs.edit().putString("tx_hash", txHash).apply()
    }
    
    fun isUserRegistered(): Boolean {
        return retrieveUserID() != null
    }
}
```

---

## UI Implementation (Android)

**File**: `android/app/src/main/kotlin/com/trustnet/ui/RegistrationScreen.kt`

```kotlin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RegistrationScreen(
    registrationViewModel: RegistrationViewModel
) {
    val uiState by registrationViewModel.uiState.collectAsState()
    
    when (uiState) {
        RegistrationUiState.Initial -> {
            InitialScreen(
                onRegisterClick = { registrationViewModel.startNFCReading() }
            )
        }
        RegistrationUiState.NFCReading -> {
            NFCReadingScreen(
                onSuccess = { idData -> registrationViewModel.processIDData(idData) },
                onError = { error -> registrationViewModel.showError(error) }
            )
        }
        RegistrationUiState.BiometricHashing -> {
            BiometricHashingScreen()
        }
        RegistrationUiState.KeyGeneration -> {
            KeyGenerationScreen()
        }
        RegistrationUiState.BlockchainSubmission -> {
            BlockchainSubmissionScreen()
        }
        RegistrationUiState.Success -> {
            SuccessScreen(
                userID = uiState.userID,
                displayName = uiState.displayName
            )
        }
        RegistrationUiState.Error -> {
            ErrorScreen(
                error = uiState.error,
                onRetry = { registrationViewModel.retry() }
            )
        }
    }
}

@Composable
private fun InitialScreen(onRegisterClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Create Your TrustNet Identity",
            style = MaterialTheme.typography.headlineLarge
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Sign in securely with your government ID",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onRegisterClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Register Identity")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Requires: Android 11+, NFC-enabled device",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SuccessScreen(
    userID: String,
    displayName: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = android.R.drawable.ic_dialog_info),
            contentDescription = "Success",
            tint = Color.Green,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Welcome, $displayName!",
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "UserID: $userID",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        
        Text(
            "You are now registered on TrustNet",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
```

---

## Testing Strategy (Android)

### Unit Tests
- `KeyGeneratorTest`: Ed25519 generation in Android Keystore
- `BiometricHasherTest`: Facial landmark extraction + hashing
- `ICAO9303ValidatorTest`: Government signature validation
- `KeystoreManagerTest`: Encrypted SharedPreferences storage

### Integration Tests
- `NFCIntegrationTest`: Real NFC tag reading
- `BlockchainIntegrationTest`: Transaction submission to test node
- `RegistrationFlowTest`: End-to-end registration on Android emulator/device

### Manual Testing
- [ ] Run on Android 11+ device with NFC
- [ ] Scan government ID (passport, driver's license with NFC)
- [ ] Verify ICAO 9303 validation
- [ ] Check EncryptedSharedPreferences via Android Device File Explorer
- [ ] Confirm blockchain transaction via node RPC
- [ ] Test offline scenarios

---

## Replication Timeline

| Week | Phase | Deliverables |
|------|-------|--------------|
| 1 | Setup | Project structure, dependencies, build configuration |
| 2 | NFC | NFC reading implementation, ICAO 9303 validation |
| 3 | Crypto | Biometric hashing, key generation, UserID calculation |
| 4 | Blockchain | Transaction submission, confirmation polling |
| 5 | UI | Jetpack Compose screens, state management |
| 6 | Testing | Unit, integration, manual testing |

---

## Success Criteria (Android)

- ✅ Identical user flow as iOS
- ✅ Same ICAO 9303 validation logic
- ✅ Same UserID generation (deterministic)
- ✅ Same blockchain transaction format
- ✅ Private key never leaves device Android Keystore)
- ✅ All registration data encrypted at rest
- ✅ No sensitive data in logs
- ✅ Registration < 2 minutes (UX target)

---

## Notes for Android Developers

1. **Use Kotlin**: Modern, concise, better than Java for this
2. **Use Jetpack Compose**: Modern declarative UI (parallels SwiftUI)
3. **Use EncryptedSharedPreferences**: Provides encrypted storage similar to iOS Keychain
4. **Use ML Kit for facial geometry**: Google's optimized library, pre-trained models
5. **Use Retrofit for HTTP**: Clean, standard Android networking
6. **Test on real device**: NFC requires physical hardware, emulator won't work

---

**Document Standard**: This is the replication contract. Same flow, different platform. Cross-platform testing required before production release.


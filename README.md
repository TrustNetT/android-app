# TrustNet Android App

Native Android application for ICAO 9303 passport validation via NFC.

## Technology Stack
- **Language**: Kotlin
- **Build System**: Gradle
- **Min API Level**: 21
- **Target API Level**: 34
- **Key Features**: NFC Forum Type 4, ECDSA signature validation

## Project Structure
```
android-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/trustnet/
│   │   └── res/
│   └── src/test/
├── build.gradle
└── settings.gradle
```

## Getting Started
```bash
gradle build
gradle test
```

## Development
- Clone this repository
- Install Android SDK (API 34+)
- Build: `gradle assembleDebug`
- Test: `gradle test`
```

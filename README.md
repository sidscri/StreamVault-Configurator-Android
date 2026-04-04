# StreamVault Configurator — Android

Full StreamVault Configurator running as a native Android app.  
Designed for landscape use on Samsung Galaxy / large-screen phones.

## Build

### Option A — GitHub Actions (recommended)
1. Push this folder as a new GitHub repo (e.g. `sidscri/sv-config-android`)
2. Actions → **Build SVConfig Android APK** → Run workflow  
3. Download `SVConfig-APK` artifact → `SVConfig-4.1.0-release.apk`

### Option B — Local (Android Studio or command line)
1. Open this folder in Android Studio — or run:
   ```
   .\gradlew.bat assembleRelease
   ```
   **First run only:** if `gradle/wrapper/gradle-wrapper.jar` is missing, Android Studio
   will download it automatically. Or grab it manually:
   ```
   curl -L "https://github.com/gradle/gradle/raw/refs/tags/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
        -o gradle/wrapper/gradle-wrapper.jar
   ```
2. APK lands at `app/build/outputs/apk/release/SVConfig-4.1.0-release.apk`

## Install on Samsung Galaxy S24 Ultra
1. Copy APK to phone (USB, Google Drive, etc.)
2. Settings → Apps → Special app access → Install unknown apps → Files/Chrome → Allow
3. Tap the APK and install

## Usage
- Open the app — it launches in landscape mode showing the full configurator UI
- Use the **⚡ USB Inject** panel → **Network Inject** section to push settings to a Fire TV on the same Wi-Fi
- Enter the Fire TV's IP, tap **Test Connection** to confirm StreamVault is running, then **Push Settings**
- **Open / Save As** work with Android's file picker (Downloads, Google Drive, etc.)
- **Save to USB inject/** saves to `Downloads/StreamVault/inject/` on the phone

## Network Inject requirements
- Phone and Fire TV must be on the same Wi-Fi network
- StreamVault must be **open** on the Fire TV (the inject server starts with the app)
- Fire TV IP: Fire TV Settings → My Fire TV → About → Network
- Tip: set a DHCP reservation on your router for the Fire TV's MAC so the IP never changes

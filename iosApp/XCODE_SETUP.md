# Xcode Setup Guide — Stationly iOS

Complete step-by-step instructions for wiring the existing Swift files into an
Xcode project and building the app. All Swift source files are already written;
this guide covers the Xcode project scaffolding only.

---

## Prerequisites

- Xcode 15.2 or later
- iOS deployment target: **16.0**
- Apple Developer account with App ID `com.stationly.mobile` registered
- KMP Gradle build producing `composeApp.framework` (see step 6)

---

## Step 1 — Create the Xcode project

1. Open Xcode → **File > New > Project**.
2. Choose **iOS > App**, click **Next**.
3. Fill in:
   | Field | Value |
   |---|---|
   | Product Name | `iosApp` |
   | Bundle Identifier | `com.stationly.mobile` |
   | Interface | SwiftUI |
   | Language | Swift |
4. **Uncheck** "Include Tests" (add them later if needed).
5. Save the project inside `StationlyUI/iosApp/` — the Xcode `.xcodeproj`
   (or `.xcworkspace` once SPM packages are added) lives at:
   `StationlyUI/iosApp/iosApp.xcodeproj`

---

## Step 2 — Add the Widget Extension target

1. **File > New > Target**, choose **Widget Extension**, click **Next**.
2. Fill in:
   | Field | Value |
   |---|---|
   | Product Name | `StationlyWidget` |
   | Bundle Identifier | `com.stationly.mobile.StationlyWidget` |
   | Include Configuration Intent | **No** (StaticConfiguration only) |
3. When prompted "Activate scheme?", click **Activate**.
4. Delete the Xcode-generated placeholder Swift files inside
   `StationlyWidget/` — the real files are already on disk:
   - `AppGroupStorage.swift`
   - `DepartureEntry.swift`
   - `WidgetTheme.swift`
   - `WidgetViews.swift`
   - `StationlyWidget.swift`
   - `StationlyWidgetBundle.swift`

   Add them to the `StationlyWidget` target via **File > Add Files to
   "iosApp"** (make sure **Target Membership** is set to `StationlyWidget`
   only, not the main app target).

---

## Step 3 — Add the App Group capability to both targets

The App Group is the shared NSUserDefaults container through which the KMP
layer pushes departure data to the widget.

### Main app target

1. Select the `iosApp` target → **Signing & Capabilities** tab.
2. Click **+ Capability** → search for **App Groups** → add it.
3. Click **+** and enter: `group.com.stationly.mobile`

### Widget target

1. Select the `StationlyWidget` target → **Signing & Capabilities**.
2. Add **App Groups** capability.
3. Tick the same group: `group.com.stationly.mobile`

Both targets must share the **identical** group identifier for
`UserDefaults(suiteName:)` to work across process boundaries.

---

## Step 4 — Add GoogleService-Info.plist

1. Download `GoogleService-Info.plist` from the Firebase Console:
   **Project Settings > Your apps > iOS app (com.stationly.mobile) >
   GoogleService-Info.plist**
2. Replace the placeholder file at `iosApp/iosApp/GoogleService-Info.plist`
   with the real one.
3. In Xcode, make sure `GoogleService-Info.plist` is added to the **main app
   target only** (not the widget).

---

## Step 5 — Add Swift Package dependencies

In Xcode: **File > Add Package Dependencies** (or open the project's Package
Dependencies tab).

| Package URL | Version rule | Products to add to `iosApp` target |
|---|---|---|
| `https://github.com/firebase/firebase-ios-sdk` | Up to next major: `11.x` | `FirebaseAuth`, `FirebaseMessaging` |
| `https://github.com/google/GoogleSignIn-iOS` | Up to next major: `7.x` | `GoogleSignIn` |

The `StationlyWidget` target does **not** need Firebase or GoogleSignIn —
`WidgetKit` and `SwiftUI` are sufficient.

---

## Step 6 — Add the KMP composeApp framework

The KMP Gradle build produces an XCFramework at (relative to repo root):

```
composeApp/build/XCFrameworks/release/composeApp.xcframework
```

### Build the framework

```bash
# From StationlyUI/
./gradlew :composeApp:assembleReleaseXCFramework
```

### Link into Xcode

1. In Xcode, select the **iosApp** project (top of the navigator).
2. Select the `iosApp` target → **General** tab → **Frameworks, Libraries, and
   Embedded Content**.
3. Click **+** → **Add Other > Add Files** → navigate to
   `composeApp/build/XCFrameworks/release/composeApp.xcframework`.
4. Set embed to **Embed & Sign**.
5. In every Swift file that calls KMP APIs, uncomment:
   ```swift
   import ComposeApp
   ```
6. In `ContentView.swift`, replace the placeholder `UIViewController()` with:
   ```swift
   return MainViewController(startLoggedIn: startLoggedIn)
   ```
7. In `AuthBridge.swift`, uncomment the `wireToKMP()` closure body.

### Automate with a Run Script phase (recommended)

To keep the framework up-to-date on every build:

1. Select `iosApp` target → **Build Phases** → **+** → **New Run Script
   Phase**. Drag it above **Compile Sources**.
2. Paste:
   ```bash
   cd "$SRCROOT/../.."
   ./gradlew :composeApp:assembleReleaseXCFramework 2>&1
   ```
3. Add output files so Xcode can cache:
   ```
   $(SRCROOT)/../../composeApp/build/XCFrameworks/release/composeApp.xcframework
   ```

---

## Step 7 — Configure the reversed Google client ID URL scheme

1. Open `iosApp/Info.plist` in Xcode (source code view).
2. Find the two occurrences of `REPLACE_WITH_REVERSED_CLIENT_ID`.
3. Replace both with the `REVERSED_CLIENT_ID` value from
   `GoogleService-Info.plist`. The format is:
   `com.googleusercontent.apps.<YOUR_CLIENT_ID>`

Also update the `GIDClientID` key in `Info.plist` to match the (non-reversed)
`CLIENT_ID` from `GoogleService-Info.plist`.

---

## Step 8 — Set minimum iOS deployment target

1. Select the project (not a target) in the navigator.
2. Under **Info > Deployment Target**, set iOS to **16.0**.
3. Repeat for the `StationlyWidget` target.

iOS 16 is required for:
- `containerBackground(for:)` widget API
- `#Preview` macro
- Compose Multiplatform minimum support

---

## Step 9 — Add Push Notifications capability

1. Select the `iosApp` target → **Signing & Capabilities**.
2. Click **+ Capability** → **Push Notifications**.
3. Ensure your provisioning profile (or Automatic Signing) includes the push
   entitlement.
4. In the Firebase Console: **Project Settings > Cloud Messaging > iOS app**
   → upload your APNs Auth Key (`.p8`) or APNs certificate.

---

## Step 10 — Wire the existing Swift files into the project

All source files are already on disk inside:

```
iosApp/iosApp/        ← main app files
iosApp/StationlyWidget/ ← widget extension files
```

If Xcode did not pick them up automatically (depends on whether you set the
project directory to the existing folder):

1. In the Project Navigator, right-click `iosApp` group → **Add Files**.
2. Select all `.swift` and `.plist` files under `iosApp/iosApp/`.
3. Target membership: **iosApp** only.
4. Repeat for `StationlyWidget/` files with target membership:
   **StationlyWidget** only.

Key files and their target memberships:

| File | iosApp | StationlyWidget |
|---|---|---|
| `iOSApp.swift` | YES | no |
| `AppDelegate.swift` | YES | no |
| `ContentView.swift` | YES | no |
| `AuthBridge.swift` | YES | no |
| `FCMBridge.swift` | YES | no |
| `Info.plist` | YES | no |
| `GoogleService-Info.plist` | YES | no |
| `AppGroupStorage.swift` | no | YES |
| `DepartureEntry.swift` | no | YES |
| `WidgetTheme.swift` | no | YES |
| `WidgetViews.swift` | no | YES |
| `StationlyWidget.swift` | no | YES |
| `StationlyWidgetBundle.swift` | no | YES |

---

## Build & Run checklist

- [ ] Simulator or device running iOS 16+
- [ ] `GoogleService-Info.plist` replaced with real file
- [ ] Reversed client ID updated in `Info.plist`
- [ ] App Group capability present on both targets
- [ ] Push Notifications capability added
- [ ] composeApp framework built and linked (or placeholder `UIViewController`
      left in place for initial smoke test)
- [ ] Scheme set to `iosApp` for the main app, `StationlyWidget` for widget
      previews

---

## Troubleshooting

**"No such module 'ComposeApp'"**
Run `./gradlew :composeApp:assembleReleaseXCFramework` and verify the
`.xcframework` is linked under Frameworks, Libraries, and Embedded Content.

**Widget shows empty / "No station set"**
The App Group container is not shared. Double-check both targets have the
**same** group ID (`group.com.stationly.mobile`) under Signing & Capabilities.

**Google Sign-In returns "The operation couldn't be completed"**
The reversed client ID URL scheme in `Info.plist` doesn't match
`GoogleService-Info.plist`. Check both the `CFBundleURLSchemes` entry and the
`GIDClientID` key.

**FCM tokens not arriving**
Push Notifications capability is missing, or the APNs key/certificate has not
been uploaded to Firebase Console.

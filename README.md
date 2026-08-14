<img align="left" width="80" height="80"
src=".github/repo_icon.png" alt="App icon">

# ZevBoard

**ZevBoard** is a free and open-source Android keyboard designed specifically as a companion for **ZevLink**. 

It is a fork of the excellent [FlorisBoard](https://github.com/florisboard/florisboard) keyboard, aiming to provide a modern, user-friendly, and highly customizable typing experience while enabling seamless cross-device clipboard synchronization.

## What It Does

ZevBoard provides all the powerful features of FlorisBoard (themes, gestures, offline functionality) with one crucial addition: **Background Clipboard Capture for ZevLink.**

Starting with Android 10, apps can no longer read the clipboard in the background. However, Android grants an exception to the active Input Method Editor (IME) – your keyboard.

**How ZevBoard powers ZevLink:**
1. You copy text anywhere on your phone.
2. ZevBoard, running securely as your active keyboard, instantly detects the clipboard change.
3. It securely extracts the text and broadcasts it exclusively to the ZevLink Android app via an explicit local intent.
4. ZevLink then sends the text over your local Wi-Fi or Hotspot to your Mac.

This allows ZevLink to securely sync your clipboard to your Mac instantly, without relying on clunky accessibility services, cloud storage, or external relay servers.

## Privacy

Like ZevLink, ZevBoard respects your privacy:
- **No Cloud Sync:** Clipboard data is only sent to your local ZevLink Android app, which securely transmits it to your Mac over your local network.
- **No History Logging By Default:** Clipboard history saving is disabled by default to align with ZevLink's commitment to only sync the *latest* item without logging your history.
- **No Analytics:** ZevBoard does not track you or send data to third parties.

## Building ZevBoard

To build the debug APK:
```sh
./gradlew :app:assembleDebug
```

To install on a connected device:
```sh
./gradlew :app:installDebug
```

## Upstream Project (FlorisBoard)

ZevBoard is built on top of [FlorisBoard](https://github.com/florisboard/florisboard) and we are incredibly grateful to the FlorisBoard contributors for their outstanding work on the original keyboard.

*If you don't need ZevLink's clipboard sync capabilities, we highly recommend checking out the original FlorisBoard!*

## License
```
Copyright 2020-2026 The FlorisBoard Contributors
Copyright 2026 The ZevLink Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

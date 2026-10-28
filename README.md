# WariatCam

WariatCam is a free Android camera/viewer project for using one Android phone as a remotely viewed camera.

## Current stage
This repository starts with a native Android app and a GitHub Actions workflow that builds a debug APK in the cloud. The first milestone is verifying the Android build and camera preview on-device.

## Planned milestones
1. Buildable Android app and camera preview.
2. Live audio/video between the Redmi 9 camera phone and Redmi Note 12S viewer.
3. Local Wi-Fi and remote-internet connection.
4. Viewer controls: digital zoom and pan, tap-to-focus where supported.
5. Remote camera/microphone pause and resume, plus battery/background reliability testing.

## Build
Push to `main` or run the **Build Android APK** workflow from the Actions tab. The debug APK is uploaded as a workflow artifact.

## Important
This is an early development build. Streaming, remote controls, and background behavior are not implemented yet. Android may restrict camera/microphone use in the background; these features must be implemented using Android-supported foreground services and tested on the target devices.

# 🐼 Panda Assistant Web

Standalone Panda Assistant with its own Gemini backend and Android live-screen companion.

## Web features
- Floating, draggable and resizable Panda chat panel
- Hindi/Hinglish Gemini chat
- Image/file upload to chat
- Hindi speech input
- Gemini API key stays on Render backend
- No dependency on the Annotate Agent project

## Android live features
- Android MediaProjection screen capture
- Floating Panda bubble above other apps
- Live screen frames sent to Gemini Live
- Hindi/Hinglish voice questions
- Gemini Live Hindi voice/audio replies
- Text chat from the floating Panda panel
- Screen capture and overlay permissions are requested by Android

## Render environment variables
Set these on the Panda Assistant Render Web Service:

- `GEMINI_API_KEY` — your Gemini API key
- `GEMINI_MODEL` — optional, defaults to `gemini-3.6-flash`
- `GEMINI_LIVE_MODEL` — optional, defaults to `gemini-3.1-flash-live-preview`
- `PANDA_TIMEOUT` — optional, defaults to `60`

`ANNOTATE_BACKEND_URL` is no longer required.

## Android APK
The Android project is inside `android/`. Every change to `android/**` on `main` automatically starts the **Panda Android APK** GitHub Actions workflow and uploads a debug APK artifact.

The Android app is configured to use the standalone Panda Render backend, so the old Annotate Agent Android backend is not required.

## Security
Never put `GEMINI_API_KEY` inside the Android APK or frontend JavaScript. The Android app receives a short-lived Gemini Live token from the Panda Render backend.

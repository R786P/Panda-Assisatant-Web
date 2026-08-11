# 🐼 Panda Assistant Web

Standalone Render-ready Panda chat panel.

## Features
- Beautiful floating Panda chat panel
- Drag the panel from the Panda header
- Resize from the bottom-right corner
- Hindi text chat
- Copy / Paste / Upload / Hindi Voice controls
- Proxies chat requests to the existing Annotate Agent `/live-chat` endpoint
- Gemini/API key stays on the backend

## Render
Create a Render Web Service from this repository.

Set:

`ANNOTATE_BACKEND_URL=https://YOUR-ANNOTATE-AGENT.onrender.com`

The service starts with `gunicorn app:app`.

The Android app can later load this standalone URL for the Panda panel, so UI changes can be deployed without rebuilding the APK.

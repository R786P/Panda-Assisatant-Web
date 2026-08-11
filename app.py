import os
import base64
import datetime
import requests
from flask import Flask, jsonify, render_template, request

app = Flask(__name__)
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.6-flash").strip()
GEMINI_LIVE_MODEL = os.environ.get("GEMINI_LIVE_MODEL", "gemini-3.1-flash-live-preview").strip()
GEMINI_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
TIMEOUT = int(os.environ.get("PANDA_TIMEOUT", "60"))


def gemini_error(resp):
    try:
        data = resp.json()
        message = data.get("error", {}).get("message")
        if message:
            return str(message)[:500]
    except Exception:
        pass
    return resp.text[:500]


@app.get("/")
def index():
    return render_template("index.html", configured=bool(GEMINI_API_KEY))


@app.get("/health")
def health():
    return jsonify({"ok": True, "gemini_configured": bool(GEMINI_API_KEY), "live_screen_backend": bool(GEMINI_API_KEY)})


@app.post("/api/chat")
def chat():
    if not GEMINI_API_KEY:
        return jsonify({"error": "GEMINI_API_KEY is not configured on Render."}), 500
    data = request.get_json(silent=True) or {}
    question = str(data.get("question", "")).strip()
    image = str(data.get("image", "")).strip()
    mime_type = str(data.get("mime_type", "image/jpeg")).strip() or "image/jpeg"
    if not question:
        return jsonify({"error": "Question is empty."}), 400
    parts = []
    if image:
        try:
            base64.b64decode(image, validate=True)
            parts.append({"inline_data": {"mime_type": mime_type, "data": image}})
        except Exception:
            pass
    parts.append({"text": (
        "Tum Panda Assistant ho. User Hindi ya Hinglish me baat kare to natural Hindi/Hinglish me jawab do. "
        "User ke attached image ko dhyan se dekho aur uske sawal ka direct, useful jawab do. "
        "Bina zarurat English me switch mat karo. Passwords, OTPs, API keys aur private secrets ko repeat mat karo.\n\n"
        f"USER QUESTION:\n{question}"
    )})
    try:
        resp = requests.post(GEMINI_URL, params={"key": GEMINI_API_KEY}, json={"contents": [{"parts": parts}]}, timeout=TIMEOUT)
        if resp.status_code != 200:
            return jsonify({"error": f"Gemini API error ({resp.status_code}): {gemini_error(resp)}"}), 502
        data = resp.json()
        candidates = data.get("candidates") or []
        if not candidates:
            return jsonify({"error": "Gemini ne koi reply nahi diya."}), 502
        reply = "\n".join(str(part.get("text", "")).strip() for part in candidates[0].get("content", {}).get("parts", []) if isinstance(part, dict) and str(part.get("text", "")).strip()).strip()
        if not reply:
            return jsonify({"error": "Gemini ka text reply empty hai."}), 502
        return jsonify({"reply": reply})
    except requests.RequestException as exc:
        return jsonify({"error": f"Gemini connection failed: {exc}"}), 502


@app.post("/live-chat")
def live_chat():
    if not GEMINI_API_KEY:
        return jsonify({"error": "GEMINI_API_KEY is not configured on Render."}), 500
    body = request.get_json(silent=True) or {}
    question = str(body.get("question", body.get("message", ""))).strip()
    image_b64 = str(body.get("image", "")).strip()
    image_mime = str(body.get("mime_type", "image/jpeg")).strip() or "image/jpeg"
    if not question:
        return jsonify({"error": "Question zaroori hai."}), 400
    parts = []
    if image_b64:
        try:
            base64.b64decode(image_b64, validate=True)
            parts.append({"inline_data": {"mime_type": image_mime, "data": image_b64}})
        except Exception:
            pass
    parts.append({"text": (
        "Tum Panda Assistant ho. User ke phone screen ka latest screenshot diya gaya hai. "
        "Screen par app, button, error, text ya UI dikh rahi ho to usko dhyan se samjho. "
        "User Hindi ya Hinglish me baat kare to natural Hindi/Hinglish me seedha aur concise jawab do. "
        "Bina zarurat English me switch mat karo. Passwords, OTPs, API keys aur private secrets ko repeat mat karo.\n\n"
        f"USER QUESTION:\n{question}"
    )})
    try:
        resp = requests.post(GEMINI_URL, params={"key": GEMINI_API_KEY}, json={"contents": [{"parts": parts}]}, timeout=TIMEOUT)
        if resp.status_code != 200:
            return jsonify({"error": f"Gemini API error ({resp.status_code}): {gemini_error(resp)}"}), 502
        data = resp.json()
        candidates = data.get("candidates") or []
        if not candidates:
            return jsonify({"error": "Gemini ne koi reply nahi diya."}), 502
        reply = "\n".join(str(part.get("text", "")).strip() for part in candidates[0].get("content", {}).get("parts", []) if isinstance(part, dict) and str(part.get("text", "")).strip()).strip()
        if not reply:
            return jsonify({"error": "Gemini ka text reply empty hai."}), 502
        return jsonify({"reply": reply})
    except requests.RequestException as exc:
        return jsonify({"error": f"Gemini connection failed: {exc}"}), 502


@app.post("/live-token")
def live_token():
    if not GEMINI_API_KEY:
        return jsonify({"error": "GEMINI_API_KEY is not configured on Render."}), 500
    try:
        now = datetime.datetime.now(datetime.timezone.utc)
        payload = {
            "uses": 1,
            "expireTime": (now + datetime.timedelta(minutes=30)).isoformat().replace("+00:00", "Z"),
            "newSessionExpireTime": (now + datetime.timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
            "liveConnectConstraints": {
                "model": f"models/{GEMINI_LIVE_MODEL}",
                "config": {"responseModalities": ["AUDIO"], "outputAudioTranscription": {}},
            },
        }
        resp = requests.post("https://generativelanguage.googleapis.com/v1alpha/auth_tokens", headers={"x-goog-api-key": GEMINI_API_KEY, "Content-Type": "application/json"}, json=payload, timeout=20)
        if resp.status_code != 200:
            return jsonify({"error": f"Gemini Live token error ({resp.status_code}): {gemini_error(resp)}"}), 502
        data = resp.json()
        token = data.get("name")
        if not token:
            return jsonify({"error": "Gemini Live token response invalid hai."}), 502
        return jsonify({"token": token, "model": GEMINI_LIVE_MODEL})
    except requests.RequestException as exc:
        return jsonify({"error": f"Live token network error: {exc}"}), 502
    except Exception as exc:
        return jsonify({"error": f"Live token failed: {type(exc).__name__}: {exc}"}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "10000")))

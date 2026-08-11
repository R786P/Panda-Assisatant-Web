import os
import requests
from flask import Flask, jsonify, render_template, request

app = Flask(__name__)
BACKEND_URL = os.environ.get("ANNOTATE_BACKEND_URL", "").rstrip("/")
TIMEOUT = int(os.environ.get("PANDA_TIMEOUT", "45"))


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/health")
def health():
    return jsonify({"ok": True, "backend_configured": bool(BACKEND_URL)})


@app.post("/api/chat")
def chat():
    if not BACKEND_URL:
        return jsonify({"error": "ANNOTATE_BACKEND_URL is not configured."}), 500

    data = request.get_json(silent=True) or {}
    question = str(data.get("question", "")).strip()
    image = str(data.get("image", ""))
    mime_type = str(data.get("mime_type", "image/jpeg"))

    if not question:
        return jsonify({"error": "Question is empty."}), 400

    payload = {"question": question, "image": image, "mime_type": mime_type}

    try:
        response = requests.post(
            f"{BACKEND_URL}/live-chat",
            json=payload,
            timeout=TIMEOUT,
        )
        try:
            body = response.json()
        except ValueError:
            body = {"error": response.text[:1000]}
        if response.status_code >= 400:
            return jsonify(body), response.status_code
        return jsonify({"reply": str(body.get("reply", "")).strip()})
    except requests.RequestException as exc:
        return jsonify({"error": f"Backend connection failed: {exc}"}), 502


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "10000")))

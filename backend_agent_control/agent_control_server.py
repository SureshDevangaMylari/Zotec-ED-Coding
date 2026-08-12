"""
Minimal backend for agent control: flips START / STOP.
Run: pip install flask && python agent_control_server.py
Then GET/POST http://10.1.241.22:8000/api/agent/status/ (Basic auth: i / admin123)
"""
import base64
from flask import Flask, request, jsonify

app = Flask(__name__)

# Current status: "START" or "STOP"
AGENT_STATUS = "STOP"

USERNAME = "i"
PASSWORD = "admin123"


def check_auth(auth_header):
    if not auth_header or not auth_header.startswith("Basic "):
        return False
    try:
        decoded = base64.b64decode(auth_header[6:]).decode("utf-8")
        user, pwd = decoded.split(":", 1)
        return user == USERNAME and pwd == PASSWORD
    except Exception:
        return False


@app.route("/api/agent/status/", methods=["GET", "POST"])
def agent_status():
    auth = request.headers.get("Authorization")
    if not check_auth(auth):
        return jsonify({"error": "Unauthorized"}), 401

    global AGENT_STATUS

    if request.method == "GET":
        return jsonify({"status": AGENT_STATUS})

    if request.method == "POST":
        data = request.get_json() or {}
        new_status = (data.get("status") or "").strip().upper()
        if new_status in ("START", "STOP"):
            AGENT_STATUS = new_status
        return jsonify({"status": AGENT_STATUS})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=False)

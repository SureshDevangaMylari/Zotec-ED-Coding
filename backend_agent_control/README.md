# Backend Agent Control API

The Java Playwright agent polls this backend for **START** / **STOP**. When the backend returns **START**, the agent runs; when it returns **STOP**, the agent stops.

## Expected API

- **URL:** `GET http://10.1.241.22:8000/api/agent/status/`
- **Auth:** HTTP Basic  
  - Username: `i`  
  - Password: `admin123`
- **Response (JSON):**  
  - `{"status": "START"}` → agent runs  
  - `{"status": "STOP"}` → agent stops  

## Optional: Minimal Flask server (standalone)

If you don't have an existing backend, run the provided Flask app to flip START/STOP:

```bash
cd backend_agent_control
pip install flask
python agent_control_server.py
```

Then use:

- **GET** `http://10.1.241.22:8000/api/agent/status/` — returns current status (Basic auth: `i` / `admin123`)
- **POST** `http://10.1.241.22:8000/api/agent/status/` with JSON `{"status": "START"}` or `{"status": "STOP"}` — set status (same auth)

The Java agent polls **GET** every 2 seconds and reacts in real time.

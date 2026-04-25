"""
M-TRACKER — Users API Patch
============================
Add these routes to your existing Python backend (Flask/FastAPI).
This saves users permanently to a local JSON file on the server.

FLASK version — paste into your existing app.py / server.py
"""

import json, os
from flask import request, jsonify

USERS_FILE = os.path.join(os.path.dirname(__file__), 'users.json')

ADMIN_USER = {
    "id": "u_admin",
    "username": "aabujami",
    "email": "hmmodtech@gmail.com",
    "email2": "aabujami@pt.acfspain.org",
    "name": "Ahmed Abu Jami",
    "password": "801165226",
    "role": "admin",
    "created": "2024-01-01T00:00:00Z",
    "isBuiltIn": True
}

def load_users():
    """Load users from JSON file, always ensuring admin exists."""
    users = []
    if os.path.exists(USERS_FILE):
        try:
            with open(USERS_FILE, 'r', encoding='utf-8') as f:
                users = json.load(f)
        except Exception:
            users = []

    # Always ensure the built-in admin is present and up-to-date
    has_admin = False
    for i, u in enumerate(users):
        if u.get('id') == 'u_admin':
            users[i] = {**u, **ADMIN_USER}  # merge, admin fields win
            has_admin = True
            break
    if not has_admin:
        users.insert(0, dict(ADMIN_USER))

    return users

def save_users(users):
    """Save users list to JSON file."""
    with open(USERS_FILE, 'w', encoding='utf-8') as f:
        json.dump(users, f, ensure_ascii=False, indent=2)

# ── Add these routes to your Flask app ──────────────────────────────

# @app.route('/api/users', methods=['GET'])
def get_users():
    """Return all users (passwords included — admin-only endpoint)."""
    return jsonify(load_users())

# @app.route('/api/users', methods=['POST'])
def save_users_route():
    """Overwrite the full users list (sent by the frontend after any change)."""
    try:
        data = request.get_json(force=True)
        if not isinstance(data, list):
            return jsonify({'error': 'Expected a list'}), 400
        save_users(data)
        return jsonify({'ok': True, 'count': len(data)})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ── HOW TO WIRE INTO YOUR app.py ───────────────────────────────────
#
#   from users_api_patch import get_users, save_users_route
#
#   app.add_url_rule('/api/users', 'get_users',  get_users,       methods=['GET'])
#   app.add_url_rule('/api/users', 'save_users', save_users_route, methods=['POST'])
#
# OR just paste the route functions directly into your app.py and
# add the @app.route decorators.
#
# ── FASTAPI version ─────────────────────────────────────────────────
#
#   from fastapi import FastAPI
#   from fastapi.responses import JSONResponse
#
#   @app.get('/api/users')
#   def get_users_fa():
#       return load_users()
#
#   @app.post('/api/users')
#   async def save_users_fa(request: Request):
#       data = await request.json()
#       save_users(data)
#       return {'ok': True}
#
# ────────────────────────────────────────────────────────────────────

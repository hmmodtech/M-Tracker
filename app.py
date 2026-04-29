import logging, os, json, hashlib, secrets, atexit
from flask import Flask, jsonify, request, send_from_directory, session
from flask_cors import CORS
from apscheduler.schedulers.background import BackgroundScheduler
import database as db
import scraper

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(levelname)s %(name)s: %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='static')
app.secret_key = os.environ.get('SECRET_KEY', secrets.token_hex(32))
CORS(app, supports_credentials=True)
db.init_db()
logger.info("Database ready.")

def _hash(password):
    """Return SHA-256 hex of password. Already-hashed (64 hex chars) pass through."""
    if len(password) == 64 and all(c in '0123456789abcdef' for c in password.lower()):
        return password
    return hashlib.sha256(password.encode('utf-8')).hexdigest()

def _safe_user(u):
    """Strip password before sending to client."""
    return {k: v for k, v in u.items() if k != 'password'}

# ── AUTH ROUTES ────────────────────────────────────────────────────

@app.route('/api/login', methods=['POST'])
def api_login():
    data = request.get_json(force=True, silent=True) or {}
    username = (data.get('username') or '').strip().lower()
    password = (data.get('password') or '').strip()
    if not username or not password:
        return jsonify({'error': 'Username and password required'}), 400
    users = _load_users()
    found = None
    for u in users:
        match = (
            (u.get('username') or '').lower() == username or
            (u.get('email') or '').lower() == username or
            (u.get('email2') or '').lower() == username
        )
        # Support both plain-text passwords (existing) and hashed
        pw = u.get('password', '')
        pw_match = (pw == password) or (_hash(password) == _hash(pw))
        if match and pw_match:
            found = u
            break
    if not found:
        logger.warning(f'Failed login: {username}')
        return jsonify({'error': 'Invalid username or password'}), 401
    session['user_id'] = found['id']
    session.permanent = True
    logger.info(f'Login: {found["username"]} ({found["role"]})')
    return jsonify({'ok': True, 'user': _safe_user(found)})

@app.route('/api/me', methods=['GET'])
def api_me():
    user_id = session.get('user_id')
    if not user_id:
        return jsonify({'error': 'Not authenticated'}), 401
    users = _load_users()
    for u in users:
        if u.get('id') == user_id:
            return jsonify(_safe_user(u))
    return jsonify({'error': 'User not found'}), 404

@app.route('/api/logout', methods=['POST'])
def api_logout():
    session.clear()
    return jsonify({'ok': True})

# ── USER MANAGEMENT ────────────────────────────────────────────────
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
    "isBuiltIn": True,
    "isSuperAdmin": True
}

ADMIN_USER2 = {
    "id": "u_admin2",
    "username": "aaljojo",
    "email": "aaljojo@pt.acfspain.org",
    "email2": "",
    "name": "Ashraf Al Jojo",
    "password": "3030",
    "role": "admin",
    "created": "2024-01-01T00:00:00Z",
    "isBuiltIn": True,
    "isSuperAdmin": False
}

def _load_users():
    users = []
    if os.path.exists(USERS_FILE):
        try:
            with open(USERS_FILE, 'r', encoding='utf-8') as f:
                users = json.load(f)
        except Exception:
            users = []
    # Always lock superadmin fields
    has_admin = any(u.get('id') == 'u_admin' for u in users)
    if has_admin:
        users = [{**u, **ADMIN_USER} if u.get('id') == 'u_admin' else u for u in users]
    else:
        users.insert(0, dict(ADMIN_USER))
    # Ensure second built-in admin exists (but allow edits by superadmin)
    has_admin2 = any(u.get('id') == 'u_admin2' for u in users)
    if not has_admin2:
        idx = next((i for i,u in enumerate(users) if u.get('id')=='u_admin'), 0)
        users.insert(idx+1, dict(ADMIN_USER2))
    else:
        for u in users:
            if u.get('id') == 'u_admin2':
                u['isBuiltIn'] = True
                u['isSuperAdmin'] = False
    return users

def _save_users(users):
    with open(USERS_FILE, 'w', encoding='utf-8') as f:
        json.dump(users, f, ensure_ascii=False, indent=2)

@app.route('/api/users', methods=['GET'])
def api_get_users():
    return jsonify(_load_users())

@app.route('/api/users', methods=['POST'])
def api_save_users():
    try:
        data = request.get_json(force=True, silent=True)
        if not isinstance(data, list):
            return jsonify({'error': 'Expected a list'}), 400
        # Always lock superadmin
        has_admin = any(u.get('id') == 'u_admin' for u in data)
        if not has_admin:
            data.insert(0, dict(ADMIN_USER))
        else:
            data = [{**u, **ADMIN_USER} if u.get('id') == 'u_admin' else u for u in data]
        # Ensure second admin exists (allow edits)
        has_admin2 = any(u.get('id') == 'u_admin2' for u in data)
        if not has_admin2:
            idx = next((i for i,u in enumerate(data) if u.get('id')=='u_admin'), 0)
            data.insert(idx+1, dict(ADMIN_USER2))
        _save_users(data)
        return jsonify({'ok': True, 'count': len(data)})
    except Exception as e:
        logger.error(f'save_users error: {e}')
        return jsonify({'error': str(e)}), 500

# ── CHANNELS ──────────────────────────────────────────────────────
@app.route('/api/channels', methods=['GET'])
def api_get_channels():
    return jsonify(db.get_channels())

@app.route('/api/channels', methods=['POST'])
def api_add_channel():
    data = request.get_json(force=True, silent=True) or {}
    username = data.get('username', '').strip().lstrip('@')
    if not username:
        return jsonify({'error': 'username required'}), 400
    display     = data.get('display', username).strip() or username
    desc        = data.get('desc', '')
    source_type = data.get('source_type', 'telegram')
    source_url  = data.get('source_url', '')
    cid = db.add_channel(username, display, desc, source_type, source_url)
    try:
        scraper.scrape_channel(username)
    except Exception as e:
        logger.warning(f'Initial scrape failed for {username}: {e}')
    return jsonify({'id': cid, 'username': username, 'display': display}), 201

@app.route('/api/channels/<int:cid>', methods=['PUT'])
def api_update_channel(cid):
    data = request.get_json(force=True, silent=True) or {}
    db.update_channel(cid, data.get('display',''), data.get('desc',''), data.get('source_url',''))
    return jsonify({'ok': 1})

@app.route('/api/channels/<int:cid>', methods=['DELETE'])
def api_delete_channel(cid):
    db.delete_channel(cid)
    return jsonify({'ok': 1})

# ── MESSAGES ─────────────────────────────────────────────────────
@app.route('/api/messages')
def api_get_messages():
    channel  = request.args.get('channel')
    query    = request.args.get('q')
    limit    = int(request.args.get('limit', 300))
    critical = request.args.get('critical') == '1'
    msgs = db.get_messages(channel=channel, query=query, limit=limit, critical_only=critical)
    return jsonify({'messages': msgs, 'total': db.count_messages()})

# ── BOOKMARKS ────────────────────────────────────────────────────
@app.route('/api/bookmarks', methods=['GET'])
def api_get_bookmarks():
    return jsonify(db.get_bookmarks())

@app.route('/api/bookmarks', methods=['POST'])
def api_add_bookmark():
    data = request.get_json(force=True, silent=True) or {}
    mid  = data.get('message_id')
    if not mid:
        return jsonify({'error': 'message_id required'}), 400
    db.add_bookmark(mid)
    return jsonify({'ok': 1}), 201

@app.route('/api/bookmarks/<int:mid>', methods=['DELETE'])
def api_remove_bookmark(mid):
    db.remove_bookmark(mid)
    return jsonify({'ok': 1})

# ── KEYWORDS ─────────────────────────────────────────────────────
@app.route('/api/keywords', methods=['GET'])
def api_get_keywords():
    return jsonify(db.get_keywords())

@app.route('/api/keywords', methods=['POST'])
def api_add_keyword():
    data = request.get_json(force=True, silent=True) or {}
    word = data.get('word', '').strip()
    if not word:
        return jsonify({'error': 'word required'}), 400
    is_critical = 1 if data.get('is_critical') else 0
    kid = db.add_keyword(word, is_critical)
    return jsonify({'id': kid, 'word': word}), 201

@app.route('/api/keywords/<int:kid>', methods=['DELETE'])
def api_remove_keyword(kid):
    db.remove_keyword(kid)
    return jsonify({'ok': 1})

# ── STATS / SCRAPE / GEOCODE / EXPORT ────────────────────────────
@app.route('/api/stats')
def api_stats():
    return jsonify(db.get_stats())

@app.route('/api/scrape', methods=['POST'])
def api_scrape():
    results = scraper.scrape_all()
    return jsonify(results)

@app.route('/api/geocode')
def api_geocode():
    import geocoder as gc
    q = request.args.get('q', '').strip()
    limit = min(int(request.args.get('limit', 10)), 20)
    if not q:
        return jsonify({'results': []})
    return jsonify({'results': gc.search(q, limit=limit)})

@app.route('/api/geocode/reverse')
def api_geocode_reverse():
    import geocoder as gc
    try:
        lat = float(request.args.get('lat'))
        lon = float(request.args.get('lon'))
    except (TypeError, ValueError):
        return jsonify({'error': 'lat and lon required'}), 400
    result = gc.reverse(lat, lon)
    return jsonify(result or {})

@app.route('/api/export')
def api_export():
    mode = request.args.get('mode', 'all')
    if mode == 'bookmarks':
        return jsonify(db.get_bookmarks())
    return jsonify(db.get_messages(limit=99999))

# ── STATIC SERVING ───────────────────────────────────────────────
@app.route('/')
def serve_index():
    return send_from_directory('static', 'index.html')

@app.route('/<path:filename>')
def serve_static(filename):
    return send_from_directory('static', filename)

# ── SCHEDULER ────────────────────────────────────────────────────
def run_scrape():
    try:
        scraper.scrape_all()
    except Exception as e:
        logger.error(f'Scheduled scrape error: {e}')

sched = BackgroundScheduler(daemon=True)
sched.add_job(run_scrape, 'interval', seconds=30, id='auto_scrape')
sched.start()
atexit.register(lambda: sched.shutdown(wait=False))
logger.info("Scheduler started.")

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False, threaded=True)

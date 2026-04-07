import logging, os, atexit
from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS
from apscheduler.schedulers.background import BackgroundScheduler
import database as db
import scraper

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(levelname)s %(name)s: %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='static')
CORS(app)
db.init_db()
logger.info("Database ready.")

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

@app.route('/api/messages')
def api_get_messages():
    channel  = request.args.get('channel')
    query    = request.args.get('q')
    limit    = int(request.args.get('limit', 300))
    critical = request.args.get('critical') == '1'
    msgs = db.get_messages(channel=channel, query=query, limit=limit, critical_only=critical)
    return jsonify({'messages': msgs, 'total': db.count_messages()})

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

@app.route('/api/stats')
def api_stats():
    return jsonify(db.get_stats())

@app.route('/api/scrape', methods=['POST'])
def api_scrape():
    results = scraper.scrape_all()
    return jsonify(results)

@app.route('/api/translate', methods=['POST'])
def api_translate():
    import requests as req
    data = request.get_json(force=True, silent=True) or {}
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'error': 'text required'}), 400
    try:
        resp = req.get(
            'https://api.mymemory.translated.net/get',
            params={'q': text[:500], 'langpair': 'ar|en'},
            timeout=10,
        )
        resp.raise_for_status()
        result = resp.json()
        translation = result.get('responseData', {}).get('translatedText', '')
        # fallback: pick best match from matches list
        if not translation and result.get('matches'):
            translation = result['matches'][0].get('translation', '')
        return jsonify({'translation': translation})
    except Exception as e:
        logger.error(f'Translation error: {e}')
        return jsonify({'error': str(e)}), 500

@app.route('/api/export')
def api_export():
    mode = request.args.get('mode', 'all')
    if mode == 'bookmarks':
        return jsonify(db.get_bookmarks())
    return jsonify(db.get_messages(limit=99999))

@app.route('/')
def serve_index():
    return send_from_directory('static', 'index.html')

@app.route('/<path:filename>')
def serve_static(filename):
    return send_from_directory('static', filename)

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

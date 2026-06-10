import os
from flask import Flask, jsonify, send_from_directory
from flask_cors import CORS
import json

# --- Explicitly define the path to the static folder ---
# This is a robust method to ensure Flask finds your files,
# regardless of the server environment. It gets the absolute path
# to the directory this script is in, and then joins it with 'static'.
static_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'static')

# --- Initialize Flask with Explicit and Correct Paths ---
# We are now telling Flask two things, leaving no room for error:
# 1. Your static files are located in the exact folder path defined in 'static_dir'.
# 2. They should be served from the URL that starts with "/static".
app = Flask(__name__, static_folder=static_dir, static_url_path='/static')
CORS(app)

# --- Database Loading ---
def load_json(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        return json.load(f)

channels_data = load_json('channels.json')
keywords_data = load_json('keywords.json')

# --- API Endpoint ---
@app.route('/api/data', methods=['GET'])
def get_data():
    return jsonify({
        'channels': channels_data,
        'keywords': keywords_data
    })

# --- Main Route to Serve the HTML Application ---
@app.route('/')
def index():
    # This tells Flask to find 'index.html' inside the 'static_folder' we defined above.
    return send_from_directory(app.static_folder, 'index.html')

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)

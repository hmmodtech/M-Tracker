from flask import Flask, request, jsonify, send_from_directory
from flask_cors import CORS
import json
import os

# STEP 1: This is the standard, correct way to initialize Flask.
# It will automatically handle the /static/ route for you.
app = Flask(__name__)
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

# --- Main Route for the Application ---
@app.route('/')
def index():
    # This tells Flask to send the index.html file from the 'static' folder
    # whenever someone visits the main URL.
    return send_from_directory(app.static_folder, 'index.html')

# STEP 2: The entire conflicting route below has been REMOVED.
#
# @app.route('/<path:filename>') <--- THIS IS THE PROBLEM CODE
# def static_files(filename):
#     return send_from_directory(app.static_folder, filename)
#

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)

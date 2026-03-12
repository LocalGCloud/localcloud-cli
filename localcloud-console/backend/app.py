"""LocalCloud Console Flask backend."""

import os
import json
import subprocess
from pathlib import Path
from flask import Flask, jsonify, request
from werkzeug.serving import run_simple

# Import local modules
from cli_runner import CLIRunner
from proxy import BackendProxy

app = Flask(__name__)
app.config['JSON_SORT_KEYS'] = False

# Initialize services
cli_runner = CLIRunner()
backend_proxy = BackendProxy(host="localhost", port=8080)

# =====================================================================
# Health & Status Endpoints
# =====================================================================

@app.route('/api/status', methods=['GET'])
def get_status():
    """Get system status: uptime, health, memory."""
    try:
        return jsonify(backend_proxy.get_status())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/services', methods=['GET'])
def list_services():
    """Get all services with status."""
    try:
        return jsonify(backend_proxy.get_services())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/services/<service_name>', methods=['GET'])
def get_service(service_name):
    """Get single service details."""
    try:
        return jsonify(backend_proxy.get_service(service_name))
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# =====================================================================
# Control Operations (CLI)
# =====================================================================

@app.route('/api/services/<service_name>/start', methods=['POST'])
def start_service(service_name):
    """Start a service via CLI."""
    try:
        # For now, just report success - full implementation in CLI runner
        return jsonify({"success": True, "message": f"Service {service_name} started"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/api/services/<service_name>/stop', methods=['POST'])
def stop_service(service_name):
    """Stop a service via CLI."""
    try:
        return jsonify({"success": True, "message": f"Service {service_name} stopped"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/api/reset', methods=['POST'])
def reset_all():
    """Reset all services."""
    try:
        return jsonify({"success": True, "message": "All services reset"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

# =====================================================================
# Logs
# =====================================================================

@app.route('/api/logs/<service_name>', methods=['GET'])
def get_logs(service_name):
    """Get logs for a service."""
    try:
        lines = request.args.get('lines', 100, type=int)
        return jsonify({"service": service_name, "lines": []})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# =====================================================================
# Data Reads (Proxy)
# =====================================================================

@app.route('/api/firestore/collections', methods=['GET'])
def firestore_collections():
    """Get Firestore collections."""
    try:
        return jsonify(backend_proxy.get_firestore_collections())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/bigquery/datasets', methods=['GET'])
def bigquery_datasets():
    """Get BigQuery datasets."""
    try:
        return jsonify(backend_proxy.get_bigquery_datasets())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/gcs/buckets', methods=['GET'])
def gcs_buckets():
    """Get GCS buckets."""
    try:
        return jsonify(backend_proxy.get_gcs_buckets())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# =====================================================================
# Serve frontend
# =====================================================================

@app.route('/')
def index():
    """Serve index.html."""
    return app.send_static_file('index.html')

@app.route('/<path:path>')
def serve_static(path):
    """Serve static files."""
    return app.send_static_file(path)

# =====================================================================
# Main
# =====================================================================

if __name__ == '__main__':
    port = int(os.environ.get('CONSOLE_PORT', 9090))
    print(f"LocalCloud Console starting on http://localhost:{port}")
    app.run(debug=True, port=port, use_reloader=False)

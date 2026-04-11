"""LocalCloud Console Flask backend — thin proxy to Admin API."""

import os
import logging

from flask import Flask, jsonify, request, send_from_directory
try:
    from flask_cors import CORS
except ImportError:
    CORS = None  # Optional: not needed when frontend is served from same origin

from proxy import BackendProxy

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(name)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='../dist', static_url_path='/')
app.config['JSON_SORT_KEYS'] = False
if CORS:
    CORS(app)

GATEWAY_URL = os.environ.get('LOCALCLOUD_GATEWAY', 'http://localhost:8080')
GCS_URL = os.environ.get('GCS_URL', 'https://localhost:4443')
proxy = BackendProxy(gateway_url=GATEWAY_URL, gcs_url=GCS_URL)


# --- Health & Status ---

@app.route('/api/health')
def health():
    data = proxy.get_health()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/services')
def api_services():
    data = proxy.get_services()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/requests')
def api_requests():
    data = proxy.get_requests()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/env')
def env():
    project = request.args.get('project')
    data = proxy.get_env(project=project)
    return jsonify(data), 502 if "error" in data else 200


# --- Projects ---

@app.route('/api/projects')
def list_projects():
    data = proxy.list_projects()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/projects', methods=['POST'])
def create_project():
    body = request.get_data(as_text=True)
    data = proxy.create_project(body)
    return jsonify(data), 502 if "error" in data else 201

@app.route('/api/projects/<project_id>', methods=['DELETE'])
def delete_project(project_id):
    data = proxy.delete_project(project_id)
    return jsonify(data), 502 if "error" in data else 200


# --- Control ---

@app.route('/api/reset', methods=['POST'])
def reset():
    project = request.args.get('project')
    data = proxy.reset(project=project)
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/seed', methods=['POST'])
def seed():
    yaml_data = request.get_data(as_text=True)
    data = proxy.seed(yaml_data)
    return jsonify(data), 502 if "error" in data else 200


# --- Data Browse ---

@app.route('/api/browse/gcs')
def browse_gcs():
    data = proxy.browse_gcs()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/gcs/<bucket>')
def browse_gcs_objects(bucket):
    data = proxy.browse_gcs_objects(bucket)
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/pubsub')
def browse_pubsub():
    data = proxy.browse_pubsub_topics()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/pubsub/subscriptions')
def browse_pubsub_subs():
    data = proxy.browse_pubsub_subscriptions()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/secretmanager')
def browse_secrets():
    data = proxy.browse_secrets()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/cloudtasks')
def browse_cloudtasks():
    data = proxy.browse_cloudtasks_queues()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/logging')
def browse_logging():
    data = proxy.browse_logging()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/monitoring')
def browse_monitoring():
    data = proxy.browse_monitoring()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/bigquery')
def browse_bigquery():
    data = proxy.browse_bigquery()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/<service>')
@app.route('/api/browse/<service>/<path:subpath>')
def browse_generic(service, subpath=''):
    data = proxy.browse_generic(service, subpath)
    return jsonify(data), 502 if "error" in data else 200


# --- Data Mutation ---

@app.route('/api/reset/<service>', methods=['POST'])
def reset_service(service):
    data = request.get_json(silent=True)
    result = proxy.reset_service(service, data)
    return jsonify(result), 502 if "error" in result else 200

@app.route('/api/export')
def export_state():
    yaml_data = proxy.export_state()
    if yaml_data:
        return yaml_data, 200, {'Content-Type': 'application/yaml',
                                'Content-Disposition': 'attachment; filename=localcloud-state.yaml'}
    return jsonify({"error": "Export failed"}), 502


@app.route('/api/mutate/<service>/<path:subpath>', methods=['POST'])
def mutate(service, subpath=''):
    data = request.get_json(silent=True)
    result = proxy.mutate(service, subpath, data)
    return jsonify(result), 502 if "error" in result else 200

@app.route('/api/mutate/<service>', methods=['POST'])
def mutate_base(service):
    data = request.get_json(silent=True)
    result = proxy.mutate(service, data=data)
    return jsonify(result), 502 if "error" in result else 200


# --- Serve frontend ---

@app.route('/')
def index():
    return send_from_directory(app.static_folder, 'index.html')

@app.route('/<path:path>')
def serve_static(path):
    if path.startswith('api/'):
        return jsonify({"error": f"Not found: /{path}"}), 404
    try:
        return send_from_directory(app.static_folder, path)
    except Exception:
        return send_from_directory(app.static_folder, 'index.html')


if __name__ == '__main__':
    port = int(os.environ.get('CONSOLE_PORT', 9090))
    logger.info(f"LocalCloud Console on http://localhost:{port}")
    host = os.environ.get('CONSOLE_HOST', '0.0.0.0')
    app.run(host=host, port=port, use_reloader=False)

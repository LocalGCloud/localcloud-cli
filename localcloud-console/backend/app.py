"""LocalCloud Console Flask backend."""

import os
import logging
from pathlib import Path
from typing import Dict, Any, Tuple

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS
from werkzeug.exceptions import BadRequest

# Import local modules
from cli_runner import CLIRunner
from proxy import BackendProxy

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='../frontend/dist', static_url_path='/')
app.config['JSON_SORT_KEYS'] = False
CORS(app)

# Valid service names for whitelist validation
VALID_SERVICES = {
    'firestore', 'bigquery', 'gcs', 'pubsub', 'bigtable',
    'cloudtasks', 'spanner', 'cloudrun', 'compute', 'gke',
    'logging', 'monitoring', 'secretmanager'
}

# Initialize services
cli_runner = CLIRunner()
backend_proxy = BackendProxy(host="localhost", port=8080)


def sanitize_error(error: Exception) -> str:
    """Sanitize error messages to avoid exposing internal details."""
    error_str = str(error)
    # Log full error internally
    logger.error(f"Internal error: {error_str}", exc_info=True)
    # Return safe error message to client
    return "An internal error occurred. Please check the server logs for details."


def validate_service_name(service_name: str) -> Tuple[bool, str]:
    """Validate service name against whitelist.

    Returns:
        Tuple of (is_valid, error_message)
    """
    if not service_name or not isinstance(service_name, str):
        return False, "Invalid service name format"
    if len(service_name) > 50:
        return False, "Service name too long"
    if service_name.lower() not in VALID_SERVICES:
        return False, f"Unknown service: {service_name}"
    return True, ""


def create_error_response(message: str, code: int = 400) -> Tuple[Dict[str, Any], int]:
    """Create standardized error response.

    Args:
        message: Error message to return to client
        code: HTTP status code

    Returns:
        Tuple of (json_dict, status_code)
    """
    return jsonify({"success": False, "error": message}), code

# =====================================================================
# Health & Status Endpoints
# =====================================================================

@app.route('/api/status', methods=['GET'])
def get_status() -> Tuple[Any, int]:
    """Get system status: uptime, health, memory."""
    try:
        return jsonify(backend_proxy.get_status()), 200
    except Exception as e:
        logger.error(f"Error getting status: {e}", exc_info=True)
        return create_error_response("Failed to retrieve system status", 500)


@app.route('/api/services', methods=['GET'])
def list_services() -> Tuple[Any, int]:
    """Get all services with status."""
    try:
        return jsonify(backend_proxy.get_services()), 200
    except Exception as e:
        logger.error(f"Error listing services: {e}", exc_info=True)
        return create_error_response("Failed to retrieve service list", 500)


@app.route('/api/services/<service_name>', methods=['GET'])
def get_service(service_name: str) -> Tuple[Any, int]:
    """Get single service details."""
    is_valid, error_msg = validate_service_name(service_name)
    if not is_valid:
        return create_error_response(error_msg, 400)

    try:
        return jsonify(backend_proxy.get_service(service_name)), 200
    except Exception as e:
        logger.error(f"Error getting service {service_name}: {e}", exc_info=True)
        return create_error_response(f"Failed to retrieve service details for {service_name}", 500)

# =====================================================================
# Control Operations (CLI)
# =====================================================================

@app.route('/api/services/<service_name>/start', methods=['POST'])
def start_service(service_name: str) -> Tuple[Any, int]:
    """Start a service via CLI."""
    is_valid, error_msg = validate_service_name(service_name)
    if not is_valid:
        return create_error_response(error_msg, 400)

    try:
        logger.info(f"Starting service: {service_name}")
        result = cli_runner.start(services=[service_name])
        if result.get("success"):
            return jsonify({"success": True, "message": f"Service {service_name} started"}), 200
        else:
            error = result.get("error", "Unknown error")
            logger.warning(f"Failed to start service {service_name}: {error}")
            return create_error_response(f"Failed to start service {service_name}", 500)
    except Exception as e:
        logger.error(f"Error starting service {service_name}: {e}", exc_info=True)
        return create_error_response(f"Error starting service {service_name}", 500)


@app.route('/api/services/<service_name>/stop', methods=['POST'])
def stop_service(service_name: str) -> Tuple[Any, int]:
    """Stop a service via CLI."""
    is_valid, error_msg = validate_service_name(service_name)
    if not is_valid:
        return create_error_response(error_msg, 400)

    try:
        logger.info(f"Stopping service: {service_name}")
        # Note: CLI stop currently stops all services, this is a limitation
        result = cli_runner.stop()
        if result.get("success"):
            return jsonify({"success": True, "message": f"Service {service_name} stopped"}), 200
        else:
            error = result.get("error", "Unknown error")
            logger.warning(f"Failed to stop service {service_name}: {error}")
            return create_error_response(f"Failed to stop service {service_name}", 500)
    except Exception as e:
        logger.error(f"Error stopping service {service_name}: {e}", exc_info=True)
        return create_error_response(f"Error stopping service {service_name}", 500)


@app.route('/api/reset', methods=['POST'])
def reset_all() -> Tuple[Any, int]:
    """Reset all services."""
    try:
        logger.info("Resetting all services")
        result = cli_runner.reset()
        if result.get("success"):
            return jsonify({"success": True, "message": "All services reset"}), 200
        else:
            error = result.get("error", "Unknown error")
            logger.warning(f"Failed to reset services: {error}")
            return create_error_response("Failed to reset services", 500)
    except Exception as e:
        logger.error(f"Error resetting services: {e}", exc_info=True)
        return create_error_response("Error resetting services", 500)

# =====================================================================
# Logs
# =====================================================================

@app.route('/api/logs/<service_name>', methods=['GET'])
def get_logs(service_name: str) -> Tuple[Any, int]:
    """Get logs for a service.

    Args:
        service_name: Name of the service to fetch logs for

    Query parameters:
        lines: Number of log lines to retrieve (default: 100, max: 10000)
    """
    is_valid, error_msg = validate_service_name(service_name)
    if not is_valid:
        return create_error_response(error_msg, 400)

    try:
        lines = request.args.get('lines', 100, type=int)
        # Bounds checking: ensure lines is between 1 and 10000
        if lines < 1:
            lines = 1
        elif lines > 10000:
            lines = 10000

        logger.info(f"Fetching {lines} lines of logs for service {service_name}")
        result = cli_runner.logs(lines=lines)
        if result.get("success"):
            return jsonify({
                "service": service_name,
                "lines": result.get("stdout", "").split('\n'),
                "success": True
            }), 200
        else:
            error = result.get("error", "Unknown error")
            logger.warning(f"Failed to get logs for {service_name}: {error}")
            return create_error_response(f"Failed to retrieve logs for {service_name}", 500)
    except ValueError:
        return create_error_response("Invalid 'lines' parameter: must be an integer", 400)
    except Exception as e:
        logger.error(f"Error getting logs for {service_name}: {e}", exc_info=True)
        return create_error_response(f"Error retrieving logs for {service_name}", 500)

# =====================================================================
# Data Reads (Proxy)
# =====================================================================

@app.route('/api/firestore/collections', methods=['GET'])
def firestore_collections() -> Tuple[Any, int]:
    """Get Firestore collections."""
    try:
        return jsonify(backend_proxy.get_firestore_collections()), 200
    except Exception as e:
        logger.error(f"Error getting Firestore collections: {e}", exc_info=True)
        return create_error_response("Failed to retrieve Firestore collections", 500)


@app.route('/api/bigquery/datasets', methods=['GET'])
def bigquery_datasets() -> Tuple[Any, int]:
    """Get BigQuery datasets."""
    try:
        return jsonify(backend_proxy.get_bigquery_datasets()), 200
    except Exception as e:
        logger.error(f"Error getting BigQuery datasets: {e}", exc_info=True)
        return create_error_response("Failed to retrieve BigQuery datasets", 500)


@app.route('/api/gcs/buckets', methods=['GET'])
def gcs_buckets() -> Tuple[Any, int]:
    """Get GCS buckets."""
    try:
        return jsonify(backend_proxy.get_gcs_buckets()), 200
    except Exception as e:
        logger.error(f"Error getting GCS buckets: {e}", exc_info=True)
        return create_error_response("Failed to retrieve GCS buckets", 500)

# =====================================================================
# Serve frontend
# =====================================================================

@app.route('/')
def index() -> Any:
    """Serve index.html."""
    return send_from_directory(app.static_folder, 'index.html')


@app.route('/<path:path>')
def serve_static(path: str) -> Any:
    """Serve static files."""
    try:
        return send_from_directory(app.static_folder, path)
    except Exception as e:
        logger.debug(f"Static file not found: {path}")
        # Fall back to index.html for client-side routing
        return send_from_directory(app.static_folder, 'index.html')

# =====================================================================
# Main
# =====================================================================

if __name__ == '__main__':
    port = int(os.environ.get('CONSOLE_PORT', 9090))
    debug = os.environ.get('FLASK_DEBUG', 'False').lower() == 'true'
    logger.info(f"LocalCloud Console starting on http://localhost:{port}")
    app.run(debug=debug, port=port, use_reloader=False)

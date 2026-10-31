"""Lightweight shared defaults for CLI startup and configuration."""

DEFAULT_CONFIG_NAME = "localcloud.yaml"
DEFAULT_IMAGE = "agentcloud/localcloud:lite"
# Published tags come in two builds: `latest` and `<version>` are full, and
# `lite` and `<version>-lite` leave out BigQuery's full edition and k3d.
FULL_TAG = "latest"
LITE_TAG = "lite"
LITE_TAG_SUFFIX = "-lite"
DEFAULT_MEMORY = "4g"
DEFAULT_TLS_PORT = 5381
DEFAULTS_CONFIG_LABEL = "<defaults>"
DEFAULT_DATA_VOLUME = "localcloud-data"
DEFAULT_PROJECT = "local-gcp-project"
DEFAULT_USER = "local-developer"

"""Tests for gcloud CLI support - env vars, service registry, and gcloud-setup."""

import subprocess
from unittest.mock import patch, MagicMock

import pytest
from click.testing import CliRunner

from localcloud.cli import cli
from localcloud.service_registry import ServiceDefinition, ServiceRegistry


# ---------------------------------------------------------------------------
# ServiceDefinition gcloud methods
# ---------------------------------------------------------------------------

class TestServiceDefinitionGcloud:
    """Tests for gcloud-related fields and methods on ServiceDefinition."""

    def _make_svc(self, gcloud_api_name=None, gcloud_port=0, port=8080):
        data = {
            "displayName": "Test Service",
            "port": port,
            "protocol": "grpc",
            "envVar": "TEST_EMULATOR_HOST",
            "envValuePrefix": "",
            "type": "facade",
            "defaultEnabled": True,
        }
        if gcloud_api_name:
            data["gcloudApiName"] = gcloud_api_name
        if gcloud_port:
            data["gcloudPort"] = gcloud_port
        return ServiceDefinition("test", data, gateway_port=8080)

    def test_gcloud_env_var_with_name(self):
        svc = self._make_svc(gcloud_api_name="secretmanager")
        assert svc.gcloud_env_var() == "CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER"

    def test_gcloud_env_var_without_name(self):
        svc = self._make_svc()
        assert svc.gcloud_env_var() is None

    def test_gcloud_endpoint_uses_service_port(self):
        svc = self._make_svc(gcloud_api_name="secretmanager", port=8080)
        assert svc.gcloud_endpoint("localhost") == "http://localhost:8080/"

    def test_gcloud_endpoint_uses_gcloud_port_when_set(self):
        svc = self._make_svc(gcloud_api_name="spanner", port=9010, gcloud_port=9020)
        assert svc.gcloud_endpoint("localhost") == "http://localhost:9020/"

    def test_gcloud_endpoint_custom_host(self):
        svc = self._make_svc(gcloud_api_name="storage", port=4443)
        assert svc.gcloud_endpoint("myhost") == "http://myhost:4443/"


# ---------------------------------------------------------------------------
# ServiceRegistry.get_env_vars() CLOUDSDK output
# ---------------------------------------------------------------------------

class TestRegistryGcloudEnvVars:
    """Tests for CLOUDSDK_* variables in get_env_vars()."""

    def test_includes_cloudsdk_vars(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars()
        # Should include at least storage and secretmanager CLOUDSDK overrides
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE" in env
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER" in env

    def test_includes_auth_and_project(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars(project_id="my-test-project")
        assert env["CLOUDSDK_CORE_PROJECT"] == "my-test-project"
        assert env["CLOUDSDK_AUTH_ACCESS_TOKEN"] == "localcloud-dev-token"

    def test_bigtable_has_no_gcloud_override(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars()
        # Bigtable emulator is gRPC-only, no gcloud override
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_BIGTABLEADMIN" not in env

    def test_memorystore_has_no_gcloud_override(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars()
        # Redis has no gcloud CLI equivalent
        for key in env:
            if "MEMORYSTORE" in key.upper() and "CLOUDSDK" in key:
                pytest.fail(f"Unexpected CLOUDSDK var for memorystore: {key}")

    def test_still_includes_sdk_vars(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars()
        assert "STORAGE_EMULATOR_HOST" in env
        assert "PUBSUB_EMULATOR_HOST" in env

    def test_spanner_uses_rest_port(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars()
        assert env["CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER"] == "http://localhost:9020/"

    def test_filtering_by_enabled_services(self):
        registry = ServiceRegistry()
        env = registry.get_env_vars(enabled_services=["gcs", "secretmanager"])
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE" in env
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER" in env
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB" not in env
        assert "PUBSUB_EMULATOR_HOST" not in env


# ---------------------------------------------------------------------------
# CLI: gcloud-setup command registration
# ---------------------------------------------------------------------------

class TestGcloudSetupCommand:
    """Tests for the gcloud-setup CLI command."""

    def test_command_registered(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["--help"])
        assert "gcloud-setup" in result.output

    def test_help_text(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["gcloud-setup", "--help"])
        assert result.exit_code == 0
        assert "gcloud CLI" in result.output
        assert "--activate" in result.output
        assert "--remove" in result.output

    @patch("localcloud.commands.gcloud_setup.shutil.which", return_value=None)
    def test_missing_gcloud_binary(self, mock_which):
        runner = CliRunner()
        result = runner.invoke(cli, ["gcloud-setup"])
        assert result.exit_code != 0
        assert "not installed" in result.output


# ---------------------------------------------------------------------------
# CLI: env command includes CLOUDSDK vars
# ---------------------------------------------------------------------------

class TestEnvCommandGcloud:
    """Tests for CLOUDSDK_* variables in localcloud env output."""

    @patch("localcloud.commands.env.DockerManager")
    def test_env_shell_format_includes_cloudsdk(self, mock_dm_cls):
        mock_dm = MagicMock()
        mock_dm.is_running.return_value = True
        mock_dm.get_env_vars.side_effect = Exception("server down")
        mock_dm_cls.return_value = mock_dm

        runner = CliRunner()
        result = runner.invoke(cli, ["env", "--format", "shell"])
        assert result.exit_code == 0
        assert "CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE" in result.output
        assert "CLOUDSDK_AUTH_ACCESS_TOKEN" in result.output
        assert "CLOUDSDK_CORE_PROJECT" in result.output

    @patch("localcloud.commands.env.DockerManager")
    def test_env_shell_values_are_quoted(self, mock_dm_cls):
        mock_dm = MagicMock()
        mock_dm.is_running.return_value = True
        mock_dm.get_env_vars.side_effect = Exception("server down")
        mock_dm_cls.return_value = mock_dm

        runner = CliRunner()
        result = runner.invoke(cli, ["env", "--format", "shell"])
        assert result.exit_code == 0
        # Values should be double-quoted
        for line in result.output.strip().splitlines():
            if line.startswith("export "):
                assert '="' in line, f"Missing quotes in: {line}"

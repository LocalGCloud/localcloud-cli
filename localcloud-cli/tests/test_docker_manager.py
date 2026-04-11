"""Tests for localcloud.docker_manager module."""

from localcloud.docker_manager import (
    DEFAULT_PORT_MAPPINGS,
    SERVICE_ENV_VARS,
    DockerManager,
)


# ---------------------------------------------------------------------------
# SERVICE_ENV_VARS constant
# ---------------------------------------------------------------------------

class TestServiceEnvVars:
    """Tests for the SERVICE_ENV_VARS constant."""

    def test_all_14_services_have_env_vars(self):
        """All 14 services must be defined in SERVICE_ENV_VARS."""
        assert len(SERVICE_ENV_VARS) == 14

    def test_expected_services_present(self):
        """Verify all expected service names exist as keys."""
        expected = {
            "storage", "pubsub", "firestore", "bigtable", "spanner",
            "bigquery", "secretmanager", "cloudtasks", "logging",
            "monitoring", "gke", "compute", "cloudrun", "memorystore",
        }
        assert set(SERVICE_ENV_VARS.keys()) == expected

    def test_env_var_tuples_have_port_and_name(self):
        """Each value is a (port, env_var_name) tuple."""
        for svc, val in SERVICE_ENV_VARS.items():
            assert isinstance(val, tuple), f"{svc}: expected tuple, got {type(val)}"
            assert len(val) == 2, f"{svc}: expected 2-element tuple"
            port, env_var = val
            assert isinstance(port, int), f"{svc}: port must be int"
            assert isinstance(env_var, str), f"{svc}: env_var must be str"

    def test_all_env_var_names_end_with_host(self):
        """All env var names should end with _HOST."""
        for svc, (_, env_var) in SERVICE_ENV_VARS.items():
            assert env_var.endswith("_HOST"), f"{svc}: {env_var} should end with _HOST"


# ---------------------------------------------------------------------------
# DEFAULT_PORT_MAPPINGS constant
# ---------------------------------------------------------------------------

class TestDefaultPortMappings:
    """Tests for the DEFAULT_PORT_MAPPINGS constant."""

    def test_all_port_mappings_are_valid_integers(self):
        """All keys and values in DEFAULT_PORT_MAPPINGS must be integers."""
        for host_port, container_port in DEFAULT_PORT_MAPPINGS.items():
            assert isinstance(host_port, int), f"host_port {host_port} must be int"
            assert isinstance(container_port, int), f"container_port {container_port} must be int"

    def test_default_gateway_port_is_8080(self):
        """Port 8080 must be mapped (the gateway port)."""
        assert 8080 in DEFAULT_PORT_MAPPINGS
        assert DEFAULT_PORT_MAPPINGS[8080] == 8080

    def test_port_mappings_count(self):
        """Verify the expected number of port mappings."""
        assert len(DEFAULT_PORT_MAPPINGS) == 10

    def test_key_ports_present(self):
        """Key service ports should be present."""
        expected_ports = {8080, 4443, 8085, 8086, 8087, 9010, 9020, 9050, 9060, 6443}
        assert set(DEFAULT_PORT_MAPPINGS.keys()) == expected_ports


# ---------------------------------------------------------------------------
# DockerManager initialisation
# ---------------------------------------------------------------------------

class TestDockerManagerInit:
    """Tests for DockerManager constructor."""

    def test_default_container_name(self):
        """Default container name is 'localcloud-main'."""
        dm = DockerManager.__new__(DockerManager)
        DockerManager.__init__(dm)
        assert dm.container_name == "localcloud-main"

    def test_default_gateway_port(self):
        """Default gateway port is 8080."""
        dm = DockerManager.__new__(DockerManager)
        DockerManager.__init__(dm)
        assert dm.gateway_port == 8080

    def test_default_image(self):
        """Default image is 'localcloud/localcloud:latest'."""
        dm = DockerManager.__new__(DockerManager)
        DockerManager.__init__(dm)
        assert dm.image == "localcloud/localcloud:latest"

    def test_custom_container_name(self):
        """Custom container_name is stored correctly."""
        dm = DockerManager(container_name="my-container")
        assert dm.container_name == "my-container"

    def test_custom_gateway_port(self):
        """Custom gateway_port is stored correctly."""
        dm = DockerManager(gateway_port=9999)
        assert dm.gateway_port == 9999

    def test_custom_image(self):
        """Custom image is stored correctly."""
        dm = DockerManager(image="my-image:v2")
        assert dm.image == "my-image:v2"

    def test_client_is_initially_none(self):
        """The internal _client is None until first access."""
        dm = DockerManager()
        assert dm._client is None

    def test_constructor_with_all_custom_args(self):
        """All constructor args can be customised together."""
        dm = DockerManager(
            image="custom:tag",
            container_name="my-lc",
            gateway_port=7070,
        )
        assert dm.image == "custom:tag"
        assert dm.container_name == "my-lc"
        assert dm.gateway_port == 7070


# ---------------------------------------------------------------------------
# DockerManager.client property (mocked docker)
# ---------------------------------------------------------------------------

class TestDockerManagerClient:
    """Tests for the lazy client property using mocked docker module."""

    def test_client_calls_docker_from_env(self, mocker):
        """Accessing .client calls docker.from_env() exactly once."""
        mock_docker_client = mocker.MagicMock()
        mocker.patch("localcloud.docker_manager.docker.from_env", return_value=mock_docker_client)

        dm = DockerManager()
        client = dm.client

        assert client is mock_docker_client
        from localcloud.docker_manager import docker
        docker.from_env.assert_called_once()

    def test_client_is_cached(self, mocker):
        """Subsequent .client accesses return the same object without calling from_env again."""
        mock_docker_client = mocker.MagicMock()
        mocker.patch("localcloud.docker_manager.docker.from_env", return_value=mock_docker_client)

        dm = DockerManager()
        client1 = dm.client
        client2 = dm.client

        assert client1 is client2
        from localcloud.docker_manager import docker
        docker.from_env.assert_called_once()

    def test_image_exists_returns_true(self, mocker):
        """image_exists returns True when the image is found."""
        mock_client = mocker.MagicMock()
        mocker.patch("localcloud.docker_manager.docker.from_env", return_value=mock_client)

        dm = DockerManager()
        assert dm.image_exists() is True
        mock_client.images.get.assert_called_once_with("localcloud/localcloud:latest")

    def test_image_exists_returns_false(self, mocker):
        """image_exists returns False when docker raises ImageNotFound."""
        import docker.errors as docker_errors

        mock_client = mocker.MagicMock()
        mock_client.images.get.side_effect = docker_errors.ImageNotFound("not found")
        mocker.patch("localcloud.docker_manager.docker.from_env", return_value=mock_client)

        dm = DockerManager()
        assert dm.image_exists() is False

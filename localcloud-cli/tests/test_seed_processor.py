"""Tests for localcloud.seed_processor module."""

import pytest

from localcloud.seed_processor import SUPPORTED_SECTIONS, SeedProcessor


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def processor():
    """Return a SeedProcessor pointed at a dummy URL."""
    return SeedProcessor("http://localhost:8080")


def _valid_seed() -> dict:
    """Return a minimal valid seed dict."""
    return {
        "version": "1",
        "project": "test-project",
        "services": {},
    }


# ---------------------------------------------------------------------------
# validate_seed – valid inputs
# ---------------------------------------------------------------------------

class TestValidateSeedValid:
    """Seed data that should pass validation without errors."""

    def test_valid_seed_passes_validation(self, processor):
        """Valid seed file with version, project, and services keys passes."""
        errors = processor.validate_seed(_valid_seed())
        assert errors == []

    def test_empty_services_passes(self, processor):
        """An empty services dict is acceptable."""
        data = _valid_seed()
        data["services"] = {}
        errors = processor.validate_seed(data)
        assert errors == []

    def test_valid_seed_with_multiple_services(self, processor):
        """Seed with gcs, pubsub, and secretmanager service sections passes."""
        data = _valid_seed()
        data["services"] = {
            "gcs": {"buckets": ["my-bucket"]},
            "pubsub": {"topics": ["my-topic"]},
            "secretmanager": {"secrets": ["my-secret"]},
        }
        errors = processor.validate_seed(data)
        assert errors == []

    def test_validate_seed_returns_true_for_valid_structure(self, processor):
        """validate_seed returns an empty list (truthy check: no errors)."""
        errors = processor.validate_seed(_valid_seed())
        assert len(errors) == 0, "Expected zero validation errors"

    def test_version_can_be_numeric(self, processor):
        """version key can be an int or float."""
        data = _valid_seed()
        data["version"] = 1
        assert processor.validate_seed(data) == []

        data["version"] = 1.0
        assert processor.validate_seed(data) == []

    def test_services_with_list_value(self, processor):
        """A service section value can be a list."""
        data = _valid_seed()
        data["services"] = {"pubsub": [{"topic": "t1"}]}
        assert processor.validate_seed(data) == []


# ---------------------------------------------------------------------------
# validate_seed – invalid inputs
# ---------------------------------------------------------------------------

class TestValidateSeedInvalid:
    """Seed data that should fail validation."""

    def test_missing_version_key(self, processor):
        """Missing 'version' key produces a validation error."""
        data = _valid_seed()
        del data["version"]
        errors = processor.validate_seed(data)
        assert any("version" in e.lower() for e in errors)

    def test_missing_project_key(self, processor):
        """Missing 'project' key produces a validation error."""
        data = _valid_seed()
        del data["project"]
        errors = processor.validate_seed(data)
        assert any("project" in e.lower() for e in errors)

    def test_missing_services_key(self, processor):
        """Missing 'services' key produces a validation error."""
        data = _valid_seed()
        del data["services"]
        errors = processor.validate_seed(data)
        assert any("services" in e.lower() for e in errors)

    def test_non_dict_top_level(self, processor):
        """A non-dict top level is rejected."""
        errors = processor.validate_seed(["not", "a", "dict"])
        assert len(errors) > 0
        assert any("mapping" in e.lower() or "dict" in e.lower() for e in errors)

    def test_unknown_service_section(self, processor):
        """An unrecognised service section name produces an error."""
        data = _valid_seed()
        data["services"] = {"unknown_svc": {}}
        errors = processor.validate_seed(data)
        assert any("unknown_svc" in e for e in errors)

    def test_services_must_be_dict(self, processor):
        """'services' must be a dict, not a list."""
        data = _valid_seed()
        data["services"] = ["not", "a", "dict"]
        errors = processor.validate_seed(data)
        assert any("mapping" in e.lower() or "dict" in e.lower() for e in errors)

    def test_service_section_value_must_be_dict_or_list(self, processor):
        """Each service section value must be a dict or list, not a scalar."""
        data = _valid_seed()
        data["services"] = {"gcs": "invalid-string"}
        errors = processor.validate_seed(data)
        assert any("gcs" in e for e in errors)

    def test_version_must_not_be_complex_type(self, processor):
        """version cannot be a list or dict."""
        data = _valid_seed()
        data["version"] = [1, 2]
        errors = processor.validate_seed(data)
        assert any("version" in e.lower() for e in errors)

    def test_project_must_be_string(self, processor):
        """project must be a string."""
        data = _valid_seed()
        data["project"] = 12345
        errors = processor.validate_seed(data)
        assert any("project" in e.lower() for e in errors)


# ---------------------------------------------------------------------------
# load_seed_file – file errors
# ---------------------------------------------------------------------------

class TestLoadSeedFile:
    """Tests for load_seed_file file I/O and parsing."""

    def test_nonexistent_file_raises_error(self, processor):
        """load_seed_file with a non-existent path raises FileNotFoundError."""
        with pytest.raises(FileNotFoundError, match="Seed file not found"):
            processor.load_seed_file("/tmp/does_not_exist_12345.yaml")

    def test_invalid_yaml_raises_error(self, processor, tmp_path):
        """load_seed_file with broken YAML raises ValueError."""
        bad_file = tmp_path / "bad.yaml"
        bad_file.write_text(":\n  :\n    - :\n  bad: [unclosed", encoding="utf-8")
        with pytest.raises(Exception):
            processor.load_seed_file(str(bad_file))

    def test_empty_yaml_raises_error(self, processor, tmp_path):
        """load_seed_file with an empty file raises ValueError."""
        empty_file = tmp_path / "empty.yaml"
        empty_file.write_text("", encoding="utf-8")
        with pytest.raises(ValueError, match="empty"):
            processor.load_seed_file(str(empty_file))

    def test_valid_file_validation_failure(self, processor, tmp_path):
        """load_seed_file with YAML missing required keys raises ValueError."""
        bad_seed = tmp_path / "missing_keys.yaml"
        bad_seed.write_text("foo: bar\n", encoding="utf-8")
        with pytest.raises(ValueError, match="validation failed"):
            processor.load_seed_file(str(bad_seed))


# ---------------------------------------------------------------------------
# SUPPORTED_SECTIONS constant
# ---------------------------------------------------------------------------

class TestSupportedSections:
    """Tests for the SUPPORTED_SECTIONS constant."""

    def test_supported_sections_is_frozenset(self):
        assert isinstance(SUPPORTED_SECTIONS, frozenset)

    def test_expected_sections_present(self):
        expected = {"gcs", "pubsub", "firestore", "bigquery", "secretmanager",
                    "cloudtasks", "spanner", "bigtable", "memorystore"}
        assert expected == SUPPORTED_SECTIONS

    def test_supported_sections_immutable(self):
        with pytest.raises(AttributeError):
            SUPPORTED_SECTIONS.add("new_section")

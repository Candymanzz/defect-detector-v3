import json

from app import file_logging
from app.services.analysis_settings import AnalysisSettings
from app.services.analysis_settings_presets import expand_merged
from app.services.inspection_service import InspectionService


def test_settings_log_is_written_as_json_lines(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(file_logging, "_LOGS_ROOT", tmp_path / "logs")
    monkeypatch.setattr(file_logging, "_SESSION_DIR", None)
    monkeypatch.setattr(file_logging, "_INITIALIZED", False)
    monkeypatch.setattr(file_logging, "is_file_logging_enabled", lambda: True)

    settings = AnalysisSettings.from_overrides(
        expand_merged(0.3, 1.0, noise_tolerance=100)
    )
    InspectionService._log_analysis_settings_event(
        "saved_detailed",
        "bench-lan1",
        settings,
        simple_knobs={"threshold": 0.3, "sensitivity": 1.0},
        detailed_knobs={"noise_tolerance": 100},
        source="detailed_api",
    )

    session_dir = file_logging.get_session_log_dir()
    assert session_dir is not None
    records = (session_dir / "analysis_settings.log").read_text(encoding="utf-8").splitlines()
    assert len(records) == 1
    record = json.loads(records[0])
    assert record["event"] == "saved_detailed"
    assert record["analysis_profile"] == "bench-lan1"
    assert record["source"] == "detailed_api"
    assert record["global_sensitivity_pct"] == 100.0
    assert record["group_sliders"]["noise_tolerance"] == 100.0
    assert record["effective_groups"]["noise_tolerance"] == 150.0
    assert record["expanded_settings"]["min_diff_signal"] == 2.5
    assert "stage=analysis_settings" in (session_dir / "analysis.log").read_text(encoding="utf-8")

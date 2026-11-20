"""配置读取顺序与日志脱敏的单元测试。"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

from chatbi import config
from chatbi.config import ENV_KEYS, parse_env_file, redact, register_secret


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for key in ENV_KEYS:
        monkeypatch.delenv(key, raising=False)
    config._SECRETS.clear()


def _write_env(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def test_parse_env_file_only_keeps_whitelisted_keys(tmp_path: Path) -> None:
    env = tmp_path / "a.env"
    _write_env(env, "CHATBI_DB_USER=chatbi_ro\nSECRET_TOKEN=leak\n# comment\n\nBAD=1\n")
    values = parse_env_file(env)
    assert values == {"CHATBI_DB_USER": "chatbi_ro"}
    assert "SECRET_TOKEN" not in values


def test_parse_env_file_strips_quotes(tmp_path: Path) -> None:
    env = tmp_path / "a.env"
    _write_env(env, 'CHATBI_DB_PASSWORD="p@ss"\nCHATBI_DB_NAME=\'ainative_v2\'\n')
    values = parse_env_file(env)
    assert values["CHATBI_DB_PASSWORD"] == "p@ss"
    assert values["CHATBI_DB_NAME"] == "ainative_v2"


def test_parse_env_file_missing_file_returns_empty(tmp_path: Path) -> None:
    assert parse_env_file(tmp_path / "nope.env") == {}


def test_redact_replaces_registered_secret() -> None:
    register_secret("super-secret-token")
    assert redact("Authorization: Bearer super-secret-token") == "Authorization: Bearer ***"


def test_redact_leaves_other_text_untouched() -> None:
    register_secret("abc12345")
    assert redact("SELECT * FROM wi_ticket") == "SELECT * FROM wi_ticket"


def test_redact_ignores_short_values() -> None:
    register_secret("ab")
    assert redact("ab cd") == "ab cd"


def test_endpoint_precedence_chatbi_over_anthropic(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CHATBI_ANTHROPIC_BASE_URL", "http://chatbi-first")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "http://anthropic-second")
    monkeypatch.setenv("CHATBI_ANTHROPIC_AUTH_TOKEN", "tok")
    monkeypatch.setenv("CHATBI_ANTHROPIC_MODEL", "m")
    cfg = config.load_config()
    assert cfg.llm.base_url == "http://chatbi-first"


def test_endpoint_precedence_anthropic_over_files(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "http://anthropic-first")
    monkeypatch.setenv("ANTHROPIC_AUTH_TOKEN", "tok")
    monkeypatch.setenv("ANTHROPIC_MODEL", "m")
    cfg = config.load_config()
    assert cfg.llm.base_url == "http://anthropic-first"


def test_endpoint_precedence_falls_back_to_runtime_env(monkeypatch: pytest.MonkeyPatch,
                                                       tmp_path: Path) -> None:
    runtime = tmp_path / "runtime.env"
    _write_env(runtime, "ANTHROPIC_BASE_URL=http://from-runtime\n"
                        "ANTHROPIC_AUTH_TOKEN=tok\nANTHROPIC_MODEL=m\n")
    monkeypatch.setattr(config, "RUNTIME_ENV_PATH", runtime)
    cfg = config.load_config()
    assert cfg.llm.base_url == "http://from-runtime"
    assert cfg.llm.model == "m"


def test_dotenv_takes_precedence_over_runtime_env(monkeypatch: pytest.MonkeyPatch,
                                                   tmp_path: Path) -> None:
    dotenv = tmp_path / ".env"
    _write_env(dotenv, "ANTHROPIC_BASE_URL=http://from-dotenv\n"
                       "ANTHROPIC_AUTH_TOKEN=tok\nANTHROPIC_MODEL=m\n")
    runtime = tmp_path / "runtime.env"
    _write_env(runtime, "ANTHROPIC_BASE_URL=http://from-runtime\n"
                        "ANTHROPIC_AUTH_TOKEN=tok\nANTHROPIC_MODEL=m\n")
    monkeypatch.setattr(config, "DOTENV_PATH", dotenv)
    monkeypatch.setattr(config, "RUNTIME_ENV_PATH", runtime)
    cfg = config.load_config()
    assert cfg.llm.base_url == "http://from-dotenv"


def test_missing_base_url_raises(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(config, "RUNTIME_ENV_PATH", tmp_path / "none.env")
    monkeypatch.setattr(config, "DOTENV_PATH", tmp_path / "none.env")
    with pytest.raises(config.ConfigError):
        config.load_config()


def test_db_defaults_use_readonly_account(monkeypatch: pytest.MonkeyPatch,
                                          tmp_path: Path) -> None:
    monkeypatch.setattr(config, "RUNTIME_ENV_PATH", tmp_path / "none.env")
    monkeypatch.setattr(config, "DOTENV_PATH", tmp_path / "none.env")
    monkeypatch.setenv("CHATBI_ANTHROPIC_BASE_URL", "http://x")
    monkeypatch.setenv("CHATBI_ANTHROPIC_AUTH_TOKEN", "t")
    monkeypatch.setenv("CHATBI_ANTHROPIC_MODEL", "m")
    cfg = config.load_config()
    assert cfg.db.user == "chatbi_ro"
    assert cfg.workspace_id == 2
    assert cfg.max_retries == 3
    assert cfg.table_max_rows == 20


def test_env_file_is_not_read_by_default_for_secrets() -> None:
    """确认白名单里没有把执行器凭据整体放行。"""
    assert "AINATIVE_EXECUTOR_TOKEN" not in ENV_KEYS
    assert "ANTHROPIC_AUTH_TOKEN" in ENV_KEYS
    assert "CHATBI_ANTHROPIC_AUTH_TOKEN" in ENV_KEYS

"""配置加载与日志脱敏。

端点读取顺序(自高到低):
    1. 进程环境 CHATBI_ANTHROPIC_*
    2. 进程环境 ANTHROPIC_*
    3. chatbi/.env
    4. 执行器 runtime.env(兜底)
密钥一律不硬编码、不入库、不进日志。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, replace
from pathlib import Path

from .errors import ConfigError

PACKAGE_DIR = Path(__file__).resolve().parent
REPO_ROOT = PACKAGE_DIR.parent
DOTENV_PATH = PACKAGE_DIR / ".env"
RUNTIME_ENV_PATH = REPO_ROOT / "runtime.env"

# 只允许从 .env / runtime.env 读取这些键,其余一律忽略——避免把执行器
# 凭据整体载入内存后误入日志。
ENV_KEYS = frozenset({
    "CHATBI_DB_HOST", "CHATBI_DB_PORT", "CHATBI_DB_USER", "CHATBI_DB_PASSWORD",
    "CHATBI_DB_NAME", "CHATBI_DB_CONNECT_TIMEOUT",
    "CHATBI_ANTHROPIC_BASE_URL", "CHATBI_ANTHROPIC_AUTH_TOKEN", "CHATBI_ANTHROPIC_MODEL",
    "CHATBI_ANTHROPIC_MAX_TOKENS", "CHATBI_ANTHROPIC_FAST_MAX_TOKENS",
    "CHATBI_ANTHROPIC_TIMEOUT",
    "CHATBI_WORKSPACE_ID", "CHATBI_OUTPUT_DIR", "CHATBI_TABLE_MAX_ROWS", "CHATBI_MAX_RETRIES",
    "ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL",
})

SECRET_KEYS = frozenset({
    "CHATBI_DB_PASSWORD", "CHATBI_ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN",
})

# 运行期登记的明文机密,redact() 会把它们替换掉。
_SECRETS: set[str] = set()


def register_secret(value: str) -> None:
    if value and len(value) >= 4:
        _SECRETS.add(value)


def redact(text: str) -> str:
    """把已登记的机密替换成 ***。"""
    if not text:
        return text
    out = text
    for secret in sorted(_SECRETS, key=len, reverse=True):
        if secret in out:
            out = out.replace(secret, "***")
    return out


def parse_env_file(path: Path) -> dict[str, str]:
    """解析 KEY=VALUE 形式的文件,只保留白名单键。"""
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if key not in ENV_KEYS:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


@dataclass(frozen=True)
class DBConfig:
    host: str = "127.0.0.1"
    port: int = 33060
    user: str = "chatbi_ro"
    password: str = ""
    database: str = "ainative_v2"
    connect_timeout: float = 10.0

    @property
    def label(self) -> str:
        return f"{self.user}@{self.host}:{self.port}/{self.database}"


@dataclass(frozen=True)
class LLMConfig:
    base_url: str
    auth_token: str
    model: str
    max_tokens: int = 32768
    fast_max_tokens: int = 8192
    timeout: float = 180.0

    @property
    def messages_url(self) -> str:
        return self.base_url.rstrip("/") + "/v1/messages"


@dataclass(frozen=True)
class AppConfig:
    db: DBConfig
    llm: LLMConfig
    workspace_id: int = 2
    output_dir: Path = REPO_ROOT / "deliverables" / "chatbi"
    table_max_rows: int = 20
    max_retries: int = 3
    database: str = "ainative_v2"

    def with_overrides(self, **kwargs) -> "AppConfig":
        return replace(self, **kwargs)


def _first(*values: str) -> str:
    for value in values:
        if value:
            return value.strip()
    return ""


def _int(value: str, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _float(value: str, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def load_config() -> AppConfig:
    """按读取顺序组装配置。"""
    process_env = {k: v for k, v in os.environ.items() if k in ENV_KEYS}
    dotenv = parse_env_file(DOTENV_PATH)
    runtime_env = parse_env_file(RUNTIME_ENV_PATH)

    def pick(*names: str) -> str:
        for name in names:
            if process_env.get(name):
                return process_env[name]
        for name in names:
            if dotenv.get(name):
                return dotenv[name]
        for name in names:
            if runtime_env.get(name):
                return runtime_env[name]
        return ""

    db = DBConfig(
        host=pick("CHATBI_DB_HOST") or "127.0.0.1",
        port=_int(pick("CHATBI_DB_PORT"), 33060),
        user=pick("CHATBI_DB_USER") or "chatbi_ro",
        password=pick("CHATBI_DB_PASSWORD"),
        database=pick("CHATBI_DB_NAME") or "ainative_v2",
        connect_timeout=_float(pick("CHATBI_DB_CONNECT_TIMEOUT"), 10.0),
    )
    register_secret(db.password)

    base_url = pick("CHATBI_ANTHROPIC_BASE_URL", "ANTHROPIC_BASE_URL")
    auth_token = pick("CHATBI_ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN")
    model = pick("CHATBI_ANTHROPIC_MODEL", "ANTHROPIC_MODEL")
    if not base_url:
        raise ConfigError(
            "未找到 LLM 端点。请设置 ANTHROPIC_BASE_URL,或在 chatbi/.env 中配置,"
            "或确保仓库根 runtime.env 可读。"
        )
    if not auth_token:
        raise ConfigError("未找到 LLM 令牌(ANTHROPIC_AUTH_TOKEN)。")
    if not model:
        raise ConfigError("未找到 LLM 模型名(ANTHROPIC_MODEL)。")
    register_secret(auth_token)

    llm = LLMConfig(
        base_url=base_url,
        auth_token=auth_token,
        model=model,
        max_tokens=_int(pick("CHATBI_ANTHROPIC_MAX_TOKENS"), 32768),
        fast_max_tokens=_int(pick("CHATBI_ANTHROPIC_FAST_MAX_TOKENS"), 8192),
        timeout=_float(pick("CHATBI_ANTHROPIC_TIMEOUT"), 180.0),
    )

    output_dir = Path(pick("CHATBI_OUTPUT_DIR") or (REPO_ROOT / "deliverables" / "chatbi"))
    if not output_dir.is_absolute():
        output_dir = (REPO_ROOT / output_dir).resolve()

    return AppConfig(
        db=db,
        llm=llm,
        workspace_id=_int(pick("CHATBI_WORKSPACE_ID"), 2),
        output_dir=output_dir,
        table_max_rows=_int(pick("CHATBI_TABLE_MAX_ROWS"), 20),
        max_retries=_int(pick("CHATBI_MAX_RETRIES"), 3),
        database=db.database,
    )

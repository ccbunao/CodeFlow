"""ChatBI 异常类型。"""
from __future__ import annotations


class ChatBIError(Exception):
    """所有 ChatBI 异常的基类。"""


class ConfigError(ChatBIError):
    """配置缺失或非法。"""


class LLMError(ChatBIError):
    """LLM 端点调用失败。"""


class SQLRejected(ChatBIError):
    """SQL 未通过校验器(防线 A)。reason 会回灌给 LLM 重试。"""

    def __init__(self, reason: str, detail: str = "") -> None:
        super().__init__(reason)
        self.reason = reason
        self.detail = detail


class MySQLError(ChatBIError):
    """数据库连接或执行失败。"""

    def __init__(self, message: str, code: int = 0) -> None:
        super().__init__(message)
        self.message = message
        self.code = code

    @property
    def is_permission_denied(self) -> bool:
        return self.code in (1044, 1045, 1142, 1143) or "denied" in self.message.lower()

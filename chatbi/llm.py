"""Anthropic 兼容端点客户端(urllib,纯标准库)。

密钥不落日志;异常信息统一走 config.redact() 脱敏。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from .config import LLMConfig, redact
from .errors import LLMError


class LLMClient:
    def __init__(self, cfg: LLMConfig) -> None:
        self.cfg = cfg

    def complete(self, system: str, user: str) -> str:
        """先按快速预算调用;被 max_tokens 截断再升级到完整预算重试一次。

        该端点的模型会输出扩展思考,thinking token 同样计入 max_tokens 预算。
        预算给满会让模型把思考写满(慢),简单问题用小预算反而快;
        复杂问题则在小预算下被截断,升级一次即可。
        """
        text, truncated = self._call(system, user, self.cfg.fast_max_tokens)
        if truncated and self.cfg.fast_max_tokens < self.cfg.max_tokens:
            text, truncated = self._call(system, user, self.cfg.max_tokens)
        if truncated:
            raise LLMError(
                f"LLM 响应被 max_tokens={self.cfg.max_tokens} 截断,没有产出正文。"
                f"请调大 CHATBI_ANTHROPIC_MAX_TOKENS。"
            )
        return text

    def _call(self, system: str, user: str, max_tokens: int) -> tuple[str, bool]:
        """返回 (正文, 是否因 max_tokens 被截断)。"""
        payload = {
            "model": self.cfg.model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": user}],
        }
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.cfg.messages_url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "x-api-key": self.cfg.auth_token,
                "Authorization": f"Bearer {self.cfg.auth_token}",
                "anthropic-version": "2023-06-01",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.cfg.timeout) as response:
                raw = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:600]
            raise LLMError(
                f"LLM 端点返回 HTTP {exc.code}: {redact(detail)}") from exc
        except urllib.error.URLError as exc:
            raise LLMError(
                f"无法连接 LLM 端点 {self.cfg.base_url} —— {redact(str(exc.reason))}") from exc
        except TimeoutError as exc:
            raise LLMError(f"LLM 端点超时(>{self.cfg.timeout}s)") from exc

        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise LLMError(f"LLM 响应不是合法 JSON: {redact(raw[:300])}") from exc

        text = self._extract_text(data)
        stop_reason = data.get("stop_reason", "?")
        if stop_reason == "max_tokens":
            # 截断可能落在半截 SQL 上,不能当成功用。
            return text, True
        if text.strip():
            return text, False
        raise LLMError(f"LLM 返回了空内容(stop_reason={stop_reason})")

    @staticmethod
    def _extract_text(data: dict) -> str:
        content = data.get("content")
        if isinstance(content, list):
            parts = [
                block.get("text", "")
                for block in content
                if isinstance(block, dict) and block.get("type") == "text"
            ]
            return "\n".join(part for part in parts if part)
        if isinstance(content, str):
            return content
        if isinstance(data.get("completion"), str):
            return data["completion"]
        return ""

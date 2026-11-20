"""CLI 入口:python3 -m chatbi "<问题>"。"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .agent import ChatBIAgent
from .config import AppConfig, load_config, redact
from .errors import ChatBIError
from .llm import LLMClient
from .mysql_client import MySQLClient


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python3 -m chatbi",
        description="ChatBI · 自然语言查库出图出数(只读)",
    )
    parser.add_argument("question", help="用自然语言提问,例如「最近 7 天每天新增需求多少条」")
    parser.add_argument("--workspace", type=int, default=None, help="数据空间 ID(默认取配置值)")
    parser.add_argument("--out", default=None, help="产物输出目录(默认取配置值)")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出结果")
    return parser


def apply_overrides(cfg: AppConfig, args: argparse.Namespace) -> AppConfig:
    overrides: dict = {}
    if args.workspace is not None:
        overrides["workspace_id"] = args.workspace
    if args.out:
        overrides["output_dir"] = Path(args.out).expanduser().resolve()
    return cfg.with_overrides(**overrides) if overrides else cfg


def main(argv: list[str] | None = None) -> int:
    # 管道输出时默认是块缓冲,CLI 应当按行吐,便于观察进度。
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, ValueError):
        pass
    args = build_parser().parse_args(argv)
    try:
        cfg = apply_overrides(load_config(), args)
    except ChatBIError as exc:
        print(f"配置错误:{redact(str(exc))}", file=sys.stderr)
        return 2

    db = MySQLClient(
        host=cfg.db.host, port=cfg.db.port, user=cfg.db.user,
        password=cfg.db.password, database=cfg.db.database,
        connect_timeout=cfg.db.connect_timeout,
    )
    try:
        db.connect()
    except ChatBIError as exc:
        print(f"数据库连接失败:{redact(str(exc))}", file=sys.stderr)
        return 3

    try:
        agent = ChatBIAgent(cfg, db, LLMClient(cfg.llm))
        outcome = agent.ask(args.question)
    except ChatBIError as exc:
        print(f"查询失败:{redact(str(exc))}", file=sys.stderr)
        return 4
    finally:
        db.close()

    if args.json:
        print(json.dumps({
            "question": outcome.question,
            "sql": outcome.sql,
            "rowCount": outcome.row_count,
            "columns": outcome.columns,
            "chartKind": outcome.chart_kind,
            "chartPath": str(outcome.chart_path),
            "conclusion": outcome.conclusion,
            "attempts": outcome.attempts,
            "notes": outcome.notes,
            "selfCheckHistory": outcome.self_check_history,
        }, ensure_ascii=False, indent=2))
        return 0

    print(f"问题:{outcome.question}")
    print(f"SQL:{outcome.sql}")
    print(f"行数:{outcome.row_count}")
    print(f"图表形态:{outcome.chart_kind}")
    print(f"图表文件:{outcome.chart_path}")
    print(f"尝试次数:{outcome.attempts}")
    if outcome.self_check_history:
        print("自检拦截记录:")
        for item in outcome.self_check_history:
            print(f"  - {item}")
    print("结论:")
    print(outcome.conclusion)
    if outcome.notes:
        print("数据说明:")
        for note in outcome.notes:
            print(f"  - {note}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

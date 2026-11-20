"""图表选型与 HTML 生成的单元测试。"""
from __future__ import annotations

from pathlib import Path

import pytest

from chatbi.chart import ChartSpec, choose_chart, render_chart
from chatbi.mysql_client import DATE_FIELD_TYPES, FIELD_TYPE_DATETIME, FIELD_TYPE_LONGLONG


def test_time_series_prefers_line_even_with_few_rows() -> None:
    """「最近 7 天每天…」只有 7 行,必须出折线图而不是表格。"""
    columns = ["stat_date", "cnt"]
    types = [FIELD_TYPE_DATETIME, FIELD_TYPE_LONGLONG]
    rows = [("2026-11-14", "0"), ("2026-11-15", "0"), ("2026-11-16", "0")]
    assert choose_chart(columns, types, rows, table_max_rows=20) == "line"


def test_time_column_detected_by_name() -> None:
    columns = ["day", "cnt"]
    rows = [("2026-11-14", "1")]
    assert choose_chart(columns, [0, 0], rows, table_max_rows=20) == "line"


def test_categorical_with_numeric_returns_bar_even_for_one_row() -> None:
    """分类维度 + 数值列 → 柱状图,哪怕只有一行(「分类出柱状」)。"""
    columns = ["agent_name", "total_runs"]
    rows = [("前后端全栈开发", "4")]
    assert choose_chart(columns, [0, 0], rows, table_max_rows=20) == "bar"


def test_large_categorical_returns_bar() -> None:
    columns = ["agent_name", "total_runs"]
    rows = [(f"agent{i}", str(i)) for i in range(30)]
    assert choose_chart(columns, [0, 0], rows, table_max_rows=20) == "bar"


def test_small_categorical_with_multiple_rows_returns_bar() -> None:
    """排名类问题返回全部候选(3 行)时,要出柱状图而不是表格。"""
    columns = ["agent_name", "total_runs", "success_rate"]
    rows = [("前后端全栈开发", "4", "75"), ("公众号标题优化师", "1", "0"), ("另一个", "2", "50")]
    assert choose_chart(columns, [0, 0, 0], rows, table_max_rows=20) == "bar"


def test_identifier_columns_are_not_plotted_as_series(tmp_path: Path) -> None:
    """agent_id 是标识符不是指标,不该画成一根柱子。"""
    spec = ChartSpec(
        kind="bar",
        title="数字员工",
        columns=["agent_id", "agent_name", "execution_count"],
        rows=[("1", "前后端全栈开发", "4")],
    )
    path = render_chart(spec, tmp_path / "bar.html")
    text = path.read_text(encoding="utf-8")
    assert '"name": "execution_count"' in text
    assert '"name": "agent_id"' not in text
    assert '"name": "agent_name"' in text or "agent_name" in text


def test_single_row_summary_returns_table() -> None:
    """单行汇总(如平均耗时)出表格,不做成一根柱子。"""
    columns = ["avg_hours"]
    rows = [("12.5",)]
    assert choose_chart(columns, [0], rows, table_max_rows=20) == "table"


def test_no_numeric_column_returns_table() -> None:
    columns = ["title"]
    rows = [(f"title{i}",) for i in range(30)]
    assert choose_chart(columns, [0], rows, table_max_rows=20) == "table"


def test_date_field_type_detected() -> None:
    assert FIELD_TYPE_DATETIME in DATE_FIELD_TYPES
    columns = ["d", "v"]
    rows = [("2026-11-14", "1")]
    assert choose_chart(columns, [DATE_FIELD_TYPES and 12, 8], rows, table_max_rows=20) == "line"


def test_render_line_chart_inlines_echarts(tmp_path: Path) -> None:
    spec = ChartSpec(
        kind="line",
        title="每天新增需求",
        columns=["stat_date", "cnt"],
        rows=[("2026-11-17", "5"), ("2026-11-18", "0")],
        subtitle="SELECT ...",
        notes=["样本量小"],
    )
    path = render_chart(spec, tmp_path / "chart.html", [12, 8])
    text = path.read_text(encoding="utf-8")
    assert path.exists()
    assert "Apache Software Foundation" in text, "ECharts 必须内联进单文件"
    assert "<script src=" not in text, "不允许外链脚本"
    assert "stat_date" in text
    assert "2026-11-17" in text
    assert "cnt" in text


def test_render_table_escapes_html(tmp_path: Path) -> None:
    spec = ChartSpec(
        kind="table",
        title="标题 <script>alert(1)</script>",
        columns=["col<1>"],
        rows=[("<img src=x onerror=alert(1)>",)],
    )
    path = render_chart(spec, tmp_path / "table.html")
    text = path.read_text(encoding="utf-8")
    assert "<script>alert(1)</script>" not in text
    assert "&lt;script&gt;" in text
    assert "<img src=x" not in text
    assert "&lt;img" in text


def test_render_chart_escapes_title_in_chart_html(tmp_path: Path) -> None:
    spec = ChartSpec(
        kind="line",
        title="<script>alert(1)</script>",
        columns=["d", "v"],
        rows=[("2026-11-17", "5")],
    )
    path = render_chart(spec, tmp_path / "c.html", [12, 8])
    text = path.read_text(encoding="utf-8")
    assert "<script>alert(1)</script>" not in text


def test_render_creates_parent_directory(tmp_path: Path) -> None:
    spec = ChartSpec(kind="table", title="t", columns=["a"], rows=[("1",)])
    path = render_chart(spec, tmp_path / "deep" / "nested" / "t.html")
    assert path.exists()

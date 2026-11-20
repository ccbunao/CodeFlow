"""图表渲染:单文件 HTML,内联 vendor ECharts,离线双击可看。

选型规则(确定性,不靠 LLM 猜):
    1. 有时间维度且有数值列   → 折线图(时序)
    2. 有分类维度且有数值列   → 柱状图(分类)
    3. 其余 / 行数很少        → 表格(汇总结果)
时序与分类都优先于「行数少」:否则「最近 7 天每天新增需求」这种 7 行的时序数据
只会出表格,达不到「出图出数」的验收;而「平均交付耗时」这种没有维度的汇总结果
做成一根柱子没有意义,仍出表格。
"""
from __future__ import annotations

import html
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from .mysql_client import DATE_FIELD_TYPES

VENDOR_DIR = Path(__file__).resolve().parent / "vendor"
ECHARTS_JS = VENDOR_DIR / "echarts.min.js"

_TIME_HINTS = ("date", "day", "time", "dt", "month", "week", "日期", "时间", "月份", "天")


@dataclass
class ChartSpec:
    kind: str                      # table | line | bar
    title: str
    columns: list[str]
    rows: list[tuple]
    subtitle: str = ""
    notes: list[str] = field(default_factory=list)
    column_types: list[int] = field(default_factory=list)


def _is_time_column(name: str, col_type: int) -> bool:
    lowered = name.lower()
    return col_type in DATE_FIELD_TYPES or any(hint in lowered for hint in _TIME_HINTS)


def _to_number(value) -> float | None:
    if value is None:
        return None
    text = str(value).strip().replace(",", "")
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _is_identifier_column(name: str) -> bool:
    """id / xxx_id 是标识符,不是指标,不该画成一根柱子。"""
    lowered = name.strip().lower()
    return lowered == "id" or lowered.endswith("_id")


def choose_chart(columns: list[str], column_types: list[int], rows: list[tuple],
                 table_max_rows: int = 20) -> str:
    """按规则挑图表形态。"""
    time_idx = next(
        (i for i, name in enumerate(columns) if i < len(column_types) and _is_time_column(name, column_types[i])),
        None,
    )
    numeric_idx = [
        i for i in range(len(columns))
        if i != time_idx and any(_to_number(row[i]) is not None for row in rows if i < len(row))
    ]
    category_idx = [
        i for i in range(len(columns))
        if i != time_idx and i not in numeric_idx
    ]
    if time_idx is not None and numeric_idx:
        return "line"
    if category_idx and numeric_idx:
        return "bar"
    if len(rows) <= table_max_rows:
        return "table"
    return "table"


def _js_literal(payload: dict) -> str:
    """把数据安全地嵌进 <script>。"""
    text = json.dumps(payload, ensure_ascii=False)
    return text.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")


def _load_echarts() -> str:
    if not ECHARTS_JS.exists():
        raise FileNotFoundError(f"缺少内联 ECharts 资源: {ECHARTS_JS}")
    return ECHARTS_JS.read_text(encoding="utf-8")


def _slug(text: str) -> str:
    cleaned = re.sub(r"[^\w-]+", "-", text.strip())
    return cleaned.strip("-")[:40] or "chart"


def _table_html(spec: ChartSpec) -> str:
    head = "".join(f"<th>{html.escape(str(c))}</th>" for c in spec.columns)
    body = []
    for row in spec.rows:
        cells = "".join(f"<td>{html.escape('' if v is None else str(v))}</td>" for v in row)
        body.append(f"<tr>{cells}</tr>")
    notes = "".join(f"<li>{html.escape(n)}</li>" for n in spec.notes)
    notes_block = f"<ul class='notes'>{notes}</ul>" if notes else ""
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>{html.escape(spec.title)}</title>
<style>
body{{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:32px;background:#f7f8fa;color:#1f2328}}
.wrap{{max-width:1100px;margin:0 auto;background:#fff;border:1px solid #d8dee4;border-radius:10px;padding:28px}}
h1{{font-size:20px;margin:0 0 6px}}
.sub{{color:#59636e;font-size:13px;margin-bottom:20px}}
table{{border-collapse:collapse;width:100%;font-size:13px}}
th,td{{border:1px solid #d8dee4;padding:7px 10px;text-align:left}}
th{{background:#f6f8fa;font-weight:600}}
tr:nth-child(even) td{{background:#fcfcfd}}
.notes{{margin-top:18px;font-size:12px;color:#59636e;line-height:1.8}}
</style></head>
<body><div class="wrap">
<h1>{html.escape(spec.title)}</h1>
<div class="sub">{html.escape(spec.subtitle)}</div>
<table><thead><tr>{head}</tr></thead><tbody>{''.join(body)}</tbody></table>
{notes_block}
</div></body></html>"""


def _chart_html(spec: ChartSpec, echarts_js: str) -> str:
    columns = spec.columns
    if spec.kind == "line":
        time_idx = next(
            (i for i, name in enumerate(columns)
             if i < len(spec.column_types) and _is_time_column(name, spec.column_types[i])),
            0,
        )
    else:
        time_idx = None

    numeric_cols = [
        i for i in range(len(columns))
        if i != time_idx and any(_to_number(row[i]) is not None for row in spec.rows if i < len(row))
    ]
    metric_cols = [i for i in numeric_cols if not _is_identifier_column(str(columns[i]))]
    if not metric_cols:
        metric_cols = numeric_cols

    if spec.kind == "line":
        x_index = time_idx if time_idx is not None else 0
    else:
        category_cols = [
            i for i in range(len(columns))
            if i not in metric_cols and not _is_identifier_column(str(columns[i]))
        ]
        if not category_cols:
            category_cols = [i for i in range(len(columns)) if i not in metric_cols]
        x_index = category_cols[0] if category_cols else 0

    x_data = [str(row[x_index]) if x_index < len(row) else "" for row in spec.rows]
    series = []
    for col in metric_cols:
        values = []
        for row in spec.rows:
            value = _to_number(row[col]) if col < len(row) else None
            values.append(value if value is not None else 0)
        series.append({
            "name": str(columns[col]),
            "type": "line" if spec.kind == "line" else "bar",
            "data": values,
            "smooth": True,
            "barMaxWidth": 48,
            "label": {"show": spec.kind == "bar" and len(spec.rows) <= 12},
        })

    option = {
        "title": {"text": spec.title, "left": "center", "textStyle": {"fontSize": 16}},
        "tooltip": {"trigger": "axis"},
        "legend": {"top": 46, "type": "scroll"},
        "grid": {"left": 64, "right": 36, "top": 84, "bottom": 64, "containLabel": True},
        "xAxis": {
            "type": "category",
            "name": str(columns[x_index]) if x_index < len(columns) else "",
            "data": x_data,
            "axisLabel": {"rotate": 30 if len(x_data) > 8 else 0, "fontSize": 11},
        },
        "yAxis": {
            "type": "value",
            "name": str(columns[metric_cols[0]]) if len(metric_cols) == 1 else "",
        },
        "series": series,
    }
    payload = {"option": option, "subtitle": spec.subtitle, "notes": spec.notes}
    notes = "".join(f"<li>{html.escape(n)}</li>" for n in spec.notes)
    notes_block = f"<ul class='notes'>{notes}</ul>" if notes else ""
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>{html.escape(spec.title)}</title>
<style>
body{{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:24px;background:#f7f8fa;color:#1f2328}}
.wrap{{max-width:1200px;margin:0 auto;background:#fff;border:1px solid #d8dee4;border-radius:10px;padding:24px}}
#chart{{width:100%;height:620px}}
.notes{{margin:8px 0 0;font-size:12px;color:#59636e;line-height:1.8}}
</style></head>
<body><div class="wrap">
<div id="chart"></div>
{notes_block}
</div>
<script>{echarts_js}</script>
<script>
(function(){{
  var DATA = {_js_literal(payload)};
  var chart = echarts.init(document.getElementById('chart'));
  chart.setOption(DATA.option);
  window.addEventListener('resize', function(){{ chart.resize(); }});
}})();
</script>
</body></html>"""


def render_chart(spec: ChartSpec, output_path: Path, column_types: list[int] | None = None) -> Path:
    """渲染并落盘,返回文件路径。"""
    if column_types is not None:
        spec.column_types = list(column_types)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if spec.kind == "table":
        html_text = _table_html(spec)
    else:
        html_text = _chart_html(spec, _load_echarts())
    output_path.write_text(html_text, encoding="utf-8")
    return output_path

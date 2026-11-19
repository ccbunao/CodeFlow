#!/usr/bin/env python3
"""公众号文章 Markdown → 内联样式 HTML。

用法: python3 scripts/wechat-md2html.py <input.md> <output.html> [图片目录名]

约定(与 docs/images/wechat/公众号*.html 现有产物一致):
- `# ` 标题 → <h2>;`## ` → <h3>;`### ` → <h4>
- `> ...` → <blockquote>(连续行合并,内部空行分段)
- ![alt](images/wechat/xx.png) → <p class="img"><img src="xx.png"></p>
  (src 只取文件名,与 HTML 同目录,方便公众号编辑器上传)
- ``` 围栏代码块 → <pre><code>
- 行内 `code` / **bold** 转换;`---` → <hr>
"""
from __future__ import annotations

import html
import re
import sys
from pathlib import Path

STYLE = """body { max-width: 677px; margin: 0 auto; padding: 24px 16px 64px;
       font: 15px/1.8 -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; color: #3f3f3f; }
h2 { font-size: 20px; margin: 32px 0 14px; color: #1a1a1a; }
h3 { font-size: 17px; margin: 26px 0 10px; color: #1a1a1a; }
h4 { font-size: 15px; margin: 20px 0 8px; color: #333; }
p { margin: 12px 0; }
p.img { margin: 20px 0 6px; }
p.img img { width: 100%; border-radius: 6px; display: block; }
p.caption { margin: 0 0 22px; font-size: 13px; color: #8a8a8a; text-align: center; }
strong { color: #1a1a1a; }
code { background: #f5f6f8; border-radius: 3px; padding: 1px 5px; font-size: 13px;
       font-family: Menlo, Consolas, monospace; color: #c0341d; }
pre { background: #f7f8fa; border: 1px solid #e9ebef; border-radius: 6px; padding: 12px 14px;
      overflow-x: auto; font-size: 13px; line-height: 1.6; }
pre code { background: none; padding: 0; color: #3f3f3f; }
blockquote { margin: 14px 0; padding: 8px 14px; border-left: 3px solid #2383e2;
             background: #f6f9fd; color: #5a6474; }
blockquote p { margin: 4px 0; }
ul, ol { padding-left: 22px; margin: 12px 0; }
li { margin: 5px 0; }
table { width: 100%; border-collapse: collapse; margin: 14px 0; font-size: 13px; }
th, td { border: 1px solid #e6e8eb; padding: 7px 9px; text-align: left; vertical-align: top; }
th { background: #f2f6fc; }
hr { border: none; border-top: 1px solid #eceef1; margin: 30px 0; }
a { color: #2383e2; }"""


def inline(text: str) -> str:
    """行内:`code`、**bold**、[text](url) → HTML(先转义再还原标记)。"""
    text = html.escape(text, quote=False)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    return text


def convert(md: str) -> str:
    lines = md.splitlines()
    out: list[str] = []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            i += 1
            continue
        # 围栏代码块
        if stripped.startswith("```"):
            buf = []
            i += 1
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            out.append(f"<pre><code>{html.escape(chr(10).join(buf))}</code></pre>")
            continue
        # 图片:src 取文件名,alt 丢弃;紧随其后的单行短句作 caption
        m = re.match(r"!\[([^\]]*)\]\(([^)]+)\)", stripped)
        if m:
            src = Path(m.group(2)).name
            out.append(f'<p class="img"><img src="{src}" alt="{html.escape(m.group(1))}"></p>')
            i += 1
            if i < n and lines[i].strip() and not lines[i].strip().startswith(("#", "```", "![")):
                cap = lines[i].strip()
                if len(cap) <= 80:
                    out.append(f'<p class="caption">{inline(cap)}</p>')
                    i += 1
            continue
        # 分隔线
        if stripped == "---":
            out.append("<hr>")
            i += 1
            continue
        # 引用块(连续 > 行)
        if stripped.startswith(">"):
            buf = []
            while i < n and lines[i].strip().startswith(">"):
                content = lines[i].strip()[1:].strip()
                buf.append(content or "")
                i += 1
            paras = [f"<p>{inline(t)}</p>" if t else "<p></p>" for t in buf]
            out.append(f"<blockquote>{''.join(paras)}</blockquote>")
            continue
        # 标题
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level = {"#": "h2", "##": "h3", "###": "h4"}.get(m.group(1), "h4")
            out.append(f"<{level}>{inline(m.group(2))}</{level}>")
            i += 1
            continue
        # 列表
        if re.match(r"^[-*]\s+", stripped) or re.match(r"^\d+\.\s+", stripped):
            ordered = bool(re.match(r"^\d+\.", stripped))
            tag = "ol" if ordered else "ul"
            items = []
            while i < n and (re.match(r"^[-*]\s+", lines[i].strip()) or re.match(r"^\d+\.\s+", lines[i].strip())):
                items.append(f"<li>{inline(re.sub(r'^([-*]|\d+\.)\s+', '', lines[i].strip()))}</li>")
                i += 1
            out.append(f"<{tag}>{''.join(items)}</{tag}>")
            continue
        # 普通段落(单行)
        out.append(f"<p>{inline(stripped)}</p>")
        i += 1
    return "\n".join(out)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    md = src.read_text(encoding="utf-8")
    title = next((l.lstrip("# ").strip() for l in md.splitlines() if l.startswith("# ")), "CodeFlow")
    body = convert(md)
    html_doc = (f'<!DOCTYPE html>\n<html lang="zh-CN"><head><meta charset="utf-8">\n'
                f'<meta name="viewport" content="width=device-width, initial-scale=1">\n'
                f'<title>{html.escape(title)}</title>\n<style>\n{STYLE}\n</style>\n</head><body>\n'
                f"{body}\n</body></html>\n")
    dst.write_text(html_doc, encoding="utf-8")
    print(f"✓ {dst} ({len(html_doc)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""A page of every icon in design/icons.txt, grouped by its section comments, for `avs show` (build/icon-sheet/index.html)."""
import html, os, re
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
groups, cur = [], None
for line in open(os.path.join(root, "design", "icons.txt")):
    s = line.strip()
    if s.startswith("# ") and "=" not in s and not s.startswith("# local_name") and not s.startswith("#  "):
        cur = [s[2:].strip(), []]; groups.append(cur); continue
    body = s.split("#")[0].strip()
    if not body or "=" not in body: continue
    name, _, rest = body.partition("="); parts = [p.strip() for p in rest.split(",")]
    if cur is None: cur = ["icons", []]; groups.append(cur)
    cur[1].append((name.strip(), parts[0], parts[1:]))
def svg(mat, fill=False):
    key = f"{mat}{'_fill' if fill else ''}.svg"
    return open(os.path.join(root, "tools", "icon-cache", key)).read()
total = sum(len(g[1]) for g in groups)
out = ['<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Plait icons</title><style>',
 ':root{--bg:#15171a;--s:#1e2125;--t:#eceef1;--t2:#b0b7c1;--tile:#28496b;--on:#d3e4ff}',
 '@media (prefers-color-scheme: light){:root{--bg:#f3f4f6;--s:#fff;--t:#1b1d21;--t2:#464e58;--tile:#d3e4ff;--on:#0b2d52}}',
 '*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:15px/1.4 system-ui,sans-serif}main{max-width:820px;margin:0 auto;padding:18px 14px 50px}',
 'h1{font-size:26px;margin:6px 0 4px}p{color:var(--t2);margin:0 0 10px}h2{font-size:15px;margin:26px 0 8px;color:var(--t2);font-weight:600;text-transform:capitalize}',
 '.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:8px}.c{background:var(--s);border-radius:14px;padding:12px 4px 8px;text-align:center}',
 '.t{width:44px;height:44px;margin:0 auto;border-radius:12px;background:var(--tile);color:var(--on);display:grid;place-items:center}.t svg{width:24px;height:24px;fill:currentColor}',
 '.n{margin-top:6px;font-size:11.5px;word-break:break-word;color:var(--t)}.m{font-size:10.5px;color:var(--t2);word-break:break-word}.f{margin-top:6px;opacity:.9}code{background:var(--s);padding:1px 6px;border-radius:6px;font-size:13px}',
 '</style></head><body><main><h1>Plait icons</h1>',
 f'<p>{total} icons, Material Symbols Rounded (weight 400) on the tonal tile. Use <code>PlaitIcon.Name</code>; add one with a line in <code>design/icons.txt</code>. Filled variants are shown after the name.</p>']
for title, items in groups:
    out.append(f'<h2>{html.escape(title)}</h2><div class="g">')
    for name, mat, flags in items:
        fill = "fill" in flags
        out.append(f'<div class="c"><div class="t">{svg(mat)}</div><div class="n">{html.escape(name)}</div>' + (f'<div class="m">{html.escape(mat)}</div>' if mat != name else '<div class="m">&nbsp;</div>') +
                   (f'<div class="t f">{svg(mat, True)}</div>' if fill else '') + '</div>')
    out.append('</div>')
out.append('</main></body></html>')
dst = os.path.join(root, "build", "icon-sheet"); os.makedirs(dst, exist_ok=True)
open(os.path.join(dst, "index.html"), "w").write("\n".join(out))
print(total, "icons,", len(groups), "groups ->", dst)

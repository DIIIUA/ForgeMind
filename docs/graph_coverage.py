"""Coverage audit: compare the Graphify AST graph of ForgeMind against README.md.

Lists user-facing surface (UI composables, enums, services, clients, tools) and flags
items whose identifier (or a humanized form) does not appear in the README.
"""
import json
import re
import pathlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[1]
GRAPH = ROOT / "graphify-out" / "graph.json"  # run: graphify extract app/src/main --code-only --out .

g = json.loads(GRAPH.read_text(encoding="utf-8"))
readme = (ROOT / "README.md").read_text(encoding="utf-8").lower()
arch = (ROOT / "docs" / "GRAPHIFY_ARCHITECTURE.md").read_text(encoding="utf-8").lower()
docs = readme + "\n" + arch

UI_SUFFIX = re.compile(r"(Pane|Dialog|Screen|Card|Sheet|Bar|Strip|Panel|Viewer|Picker|Menu|Drawer|Row|Section|Editor)\(\)$")
CLASS_HINT = re.compile(r"(Service|Client|Manager|Server|Scanner|Controller|Engine|Bridge|Parser|Receiver|Compactor|Router|Shell|Recorder|Policy)$")


def humanize(label: str) -> list[str]:
    base = label.rstrip("()")
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", base).replace("_", " ").lower()
    return [base.lower(), words]


def mentioned(label: str) -> bool:
    return any(h and h in docs for h in humanize(label))


buckets = defaultdict(list)
enum_parent = {}
for n in g["nodes"]:
    lab = n.get("label", "")
    src = n.get("source_file", "")
    if not src.startswith("java/com/forgemind/app/"):
        continue
    pkg = src.split("/")[4] if src.count("/") > 4 else "root"
    if UI_SUFFIX.search(lab) and "Preview" not in lab:
        buckets["ui"].append((lab, src))
    elif CLASS_HINT.search(lab) and lab[:1].isupper():
        buckets["classes"].append((lab, src))

# enums straight from DomainModels / other sources
enum_re = re.compile(r"enum class (\w+)[^{]*\{(.*?)(?:;|\n\})", re.S)
for kt in (ROOT / "app/src/main/java/com/forgemind/app").rglob("*.kt"):
    txt = kt.read_text(encoding="utf-8")
    for m in enum_re.finditer(txt):
        entries = re.findall(r"^\s*([A-Z][A-Z0-9_]+)\s*(?:\(|,|$)", m.group(2), re.M)
        for e in entries:
            buckets["enum:" + m.group(1)].append((e, str(kt.relative_to(ROOT))))

for key in sorted(buckets):
    items = sorted(set(buckets[key]))
    missing = [lab for lab, _ in items if not mentioned(lab)]
    print(f"\n### {key}: {len(items)} total, {len(missing)} not in docs")
    for lab in missing:
        print("  -", lab)


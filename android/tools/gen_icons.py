"""Download Material Symbols Outlined SVGs and emit a Kotlin file of ImageVectors."""
import re
import sys
import urllib.request

OUT = sys.argv[1]
BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{name}/{style}/24px.svg"

OUTLINE = """account_tree add alt_route arrow_back arrow_upward check check_box check_box_outline_blank
chevron_right close cloud_off computer contrast delete description dns download edit error expand_more folder
forum image more_vert palette photo_camera power_settings_new radio_button_checked radio_button_unchecked
refresh settings style upload wallpaper warning""".split()
FILLED = ["computer", "forum", "account_tree", "settings"]


def camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def fetch(name: str, style: str) -> list[str]:
    url = BASE.format(name=name, style=style)
    with urllib.request.urlopen(url, timeout=30) as r:
        svg = r.read().decode()
    if 'viewBox="0 -960 960 960"' not in svg:
        raise SystemExit(f"unexpected viewBox for {name}/{style}")
    return re.findall(r'<path d="([^"]+)"', svg)


lines = [
    "package clauderc.app.ui.theme",
    "",
    "// Generated from Material Symbols Outlined (Apache 2.0) by gen_icons.py; do not edit by hand.",
    "// Outline icons use weight 300 like the mockup; *Filled use FILL 1 weight 400 for the selected nav item.",
    "internal object SymbolPaths {",
]
for name in OUTLINE:
    paths = fetch(name, "wght300")
    lines.append(f'    const val {camel(name)} = "{"|".join(paths)}"')
for name in FILLED:
    paths = fetch(name, "fill1")
    lines.append(f'    const val {camel(name)}Filled = "{"|".join(paths)}"')
lines.append("}")
lines.append("")
with open(OUT, "w") as f:
    f.write("\n".join(lines))
print("wrote", OUT, len(OUTLINE) + len(FILLED), "icons")

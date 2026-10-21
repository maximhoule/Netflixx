"""Writes the app's settings for the test: a placeholder Stremio session plus public addons."""
import json, sys, urllib.request
from xml.sax.saxutils import escape

addons = []
for url in ["https://v3-cinemeta.strem.io/manifest.json", "https://opensubtitles-v3.strem.io/manifest.json",
            "https://raw.githubusercontent.com/maximhoule/Netflixx/main/.github/scripts/testaddon/manifest.json"]:
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            addons.append({"transportUrl": url, "manifest": json.load(r), "flags": {}})
    except Exception as e:
        print(f"could not fetch {url}: {e}", file=sys.stderr)

profile = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="authKey">ci-test-session</string>
    <string name="email">ci@example.com</string>
    <string name="addons">{escape(json.dumps(addons), {'"': '&quot;'})}</string>
    <int name="trailerMode" value="0" />
</map>
"""
glob = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="autoUpdate" value="false" />
</map>
"""
open(sys.argv[1], "w").write(profile)
open(sys.argv[2], "w").write(glob)
print(f"seeded {len(addons)} addons")

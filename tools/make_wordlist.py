"""Builds app/src/main/assets/words_en.txt from AOSP LatinIME's en_GB word list (Apache 2.0)."""
import base64, gzip, re, sys, urllib.request

URL = ("https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/"
       "dictionaries/en_GB_wordlist.combined.gz?format=TEXT")
LIMIT = 50000

raw = gzip.decompress(base64.b64decode(urllib.request.urlopen(URL).read())).decode("utf-8")
words = {}
for line in raw.splitlines():
    if not line.startswith(" word="):
        continue
    fields = dict(kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv)
    w, f = fields.get("word", ""), int(fields.get("f", "0"))
    if fields.get("flags") in ("offensive", "nonword") or fields.get("not_a_word") == "true":
        continue
    if not re.fullmatch(r"[A-Za-z']+", w):
        continue
    words[w] = max(f, words.get(w, 0))
top = sorted(words.items(), key=lambda kv: -kv[1])[:LIMIT]
out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/words_en.txt"
with open(out, "w", encoding="utf-8", newline="\n") as fh:
    for w, f in top:
        fh.write(f"{w}\t{f}\n")
print(len(top))

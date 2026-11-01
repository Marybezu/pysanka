#!/usr/bin/env python3
"""
Pysanka: builds the word lists for suggestions and swipe (app/src/main/assets/ime/dict/<lang>.json).

Sources (both checked out by hand, see README in this folder):
- FrequencyWords (Hermit Dave), OpenSubtitles 2018 word counts, CC BY-SA 4.0:
  content/2018/<lang>/<lang>_full.txt
- Ukrainian only: the Ukrainian hunspell dictionary of the dict_uk project (uk_UA.dic/.aff;
  GPL 3.0+ / LGPL 2.1+ / MPL 1.1): a Ukrainian word is kept only if the dictionary knows it, so
  Russian words and misspellings common in subtitles are left out.

Output: {"word": frequency 1..255} for the most frequent words, frequency on a log scale.
Usage: build_dicts.py <FrequencyWords dir> <uk_UA dir> <out dir>
"""
import json
import math
import re
import sys
from pathlib import Path

MAX_WORDS = {"uk": 60000, "en": 50000}
LETTERS = {
    "uk": re.compile(r"^[абвгґдеєжзиіїйклмнопрстуфхцчшщьюя']+(-[абвгґдеєжзиіїйклмнопрстуфхцчшщьюя']+)*$"),
    "en": re.compile(r"^[a-z]+('[a-z]+)?$"),
}


def hunspell_forms(dic: Path, aff: Path) -> set[str]:
    """All word forms of a hunspell dictionary that uses only suffix rules (like uk_UA)."""
    rules: dict[str, list[tuple[str, str, re.Pattern]]] = {}
    for line in aff.read_text(encoding="utf-8").splitlines():
        p = line.split()
        if len(p) >= 5 and p[0] == "SFX":
            flag, strip, add, cond = p[1], p[2], p[3], p[4]
            strip = "" if strip == "0" else strip
            add = "" if add == "0" else add.split("/")[0]
            rules.setdefault(flag, []).append((strip, add, re.compile(cond + "$")))
    forms = set()
    for line in dic.read_text(encoding="utf-8").splitlines()[1:]:
        line = line.split("\t")[0].strip()
        if not line or line.startswith("+"):
            continue
        stem, _, flags = line.partition("/")
        stem = stem.lower()
        forms.add(stem)
        for f in flags:
            for strip, add, cond in rules.get(f, []):
                if cond.search(stem) and stem.endswith(strip):
                    forms.add(stem[: len(stem) - len(strip)] + add)
    return forms


def apostrophe_words(raw: dict[str, int], known: set[str]) -> dict[str, int]:
    """
    The subtitles were split at apostrophes («п'ять» counted as «ять»), so a word with an
    apostrophe gets the count of its part after the apostrophe, shared among the dictionary
    words with that part. When that part is a word of its own («сім'я»: «я»), its count says
    nothing; such a word gets a modest count instead.
    """
    by_tail: dict[str, list[str]] = {}
    for w in known:
        if "'" in w:
            by_tail.setdefault(w.split("'")[-1], []).append(w)
    modest = sorted(raw.values(), reverse=True)[min(20000, len(raw) - 1)]
    out = {}
    for tail, words in by_tail.items():
        n = raw.get(tail, 0)
        if n == 0:
            continue
        share = modest if tail in known else max(modest, n // len(words))
        for w in words:
            out[w] = share
    return out


def build(lang: str, freq_file: Path, known: set[str] | None) -> dict[str, int]:
    raw: dict[str, int] = {}
    for line in freq_file.read_text(encoding="utf-8").splitlines():
        word, _, n = line.rpartition(" ")
        word = word.strip().lower().replace("’", "'").replace("ʼ", "'")
        if word and n.isdigit() and LETTERS[lang].match(word):
            raw[word] = raw.get(word, 0) + int(n)
    if known is not None:
        found = {w: n for w, n in raw.items() if w in known}
        for w, n in apostrophe_words(raw, known).items():
            found.setdefault(w, n)
        raw = found
    counts = sorted(raw.items(), key=lambda kv: -kv[1])[: MAX_WORDS[lang]]
    top = math.log(counts[0][1])
    low = math.log(max(counts[-1][1], 1))
    return {w: max(1, round(1 + 254 * (math.log(n) - low) / max(top - low, 1e-9))) for w, n in counts}


def main() -> None:
    fw, uk_dir, out = (Path(a) for a in sys.argv[1:4])
    known_uk = hunspell_forms(uk_dir / "uk_UA.dic", uk_dir / "uk_UA.aff")
    out.mkdir(parents=True, exist_ok=True)
    for lang, known in (("uk", known_uk), ("en", None)):
        data = build(lang, fw / "content" / "2018" / lang / f"{lang}_full.txt", known)
        (out / f"{lang}.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(lang, len(data), list(data)[:15])


if __name__ == "__main__":
    main()

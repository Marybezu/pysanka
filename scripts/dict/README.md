# Word lists for suggestions and swipe

`app/src/main/assets/ime/dict/uk.json` and `en.json` — the most frequent words with a frequency
1..255, made by `build_dicts.py` from:

- **FrequencyWords** by Hermit Dave (https://github.com/hermitdave/FrequencyWords), word counts
  from OpenSubtitles 2018 (http://opus.nlpl.eu/OpenSubtitles.php, http://www.opensubtitles.org/),
  content under CC BY-SA 4.0. The lists made from it are under CC BY-SA 4.0 too.
- Ukrainian only: the Ukrainian hunspell dictionary of the dict_uk project
  (https://github.com/brown-uk/dict_uk, uk_UA.dic/.aff as in LibreOffice; GPL 3.0+ / LGPL 2.1+ /
  MPL 1.1), used only to keep Ukrainian words (no Russian words or misspellings from subtitles).

```sh
git clone --depth 1 https://github.com/hermitdave/FrequencyWords fw
git clone --depth 1 --filter=blob:none --sparse https://github.com/LibreOffice/dictionaries lo && (cd lo && git sparse-checkout set uk_UA)
python3 -I scripts/dict/build_dicts.py fw lo/uk_UA app/src/main/assets/ime/dict
```

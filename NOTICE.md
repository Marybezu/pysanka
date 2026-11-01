# Third-party material

- Pysanka is based on **FlorisBoard** by The FlorisBoard Contributors
  (https://github.com/florisboard/florisboard), Apache License 2.0. The full history of FlorisBoard
  is kept in this repository; files from FlorisBoard keep their copyright headers. Pysanka is an
  independent project and is not affiliated with or endorsed by FlorisBoard.
- Libraries used by FlorisBoard (AndroidX, Kotlin, Jetpack Compose and others) are used as
  dependencies under their own licences; the in-app "Open-source licences" screen lists them.
- `app/src/main/assets/ocr/*.onnx` and `*_dict.txt` — PP-OCRv5 mobile text detection and East Slavic /
  Latin text recognition models by PaddlePaddle (PaddleOCR), Apache License 2.0, converted to ONNX
  unchanged in weights (see `SOURCES.json` and `scripts/ocr_models.py`).
- LiteRT-LM (Google, Apache 2.0) runs the AI editor's model. The Gemma model is not included; the
  owner downloads it under its own terms, and only the exact published file is accepted.
- ONNX Runtime (Microsoft, MIT), Bouncy Castle (MIT-style),
  SQLCipher for Android (BSD-style) are used as dependencies under their own licences.

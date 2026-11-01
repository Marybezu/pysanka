<img src=".github/repo_icon.png" width="96" alt="">

# Писанка · Pysanka

Приватна клавіатура для Android без доступу до інтернету. Набір, swipe, буфер обміну, а за бажанням — локальний ШІ, що редагує текст прямо на телефоні. Українська й англійська в інтерфейсі, розкладки для багатьох мов. Безкоштовно, без реклами й акаунтів.

> **Статус: у розробці, тестові версії** — у [Releases](../../releases). Помилки й побажання: [Issues](../../issues).

## Що буде

- 🔒 **Приватність.** Дозволу на інтернет немає, і збірка зупиняється, якщо він з'явиться. Нічого не потрапляє в хмарну копію чи на інший телефон.
- 📋 **Зашифрований буфер** ✅ Історія тексту й зображень зашифрована (SQLCipher + AES-256, ключ в апаратному сховищі). «Не зберігати паролі» — на ваш вибір. Копія буфера під паролем та імпорт із FlorisBoard. Кольорові теги й автотеги (посилання, номери, коди). Очищення посилань від стеження. Фото й скріншоти в історію одним дотиком, з розпізнаним текстом. Далі: пошук, шаблони.
- 🔍 **Текст із фото й камери** ✅ розпізнається на телефоні.
- 🤖 **Локальний ШІ (Gemma 4), за бажанням** ✅ стиль, граматика й пунктуація, підсумок довгих текстів (з перевіркою чисел і назв), переклад, русизми, список, таблиця, нотатки, лист з опису, варіанти відповіді, приховати особисте; «Поділитися → Писанка ШІ» з будь-якого застосунку. Далі: інструменти для соцмереж.
- 🎙️ **Диктування** на телефоні (Whisper), за бажанням.

Зараз це клавіатура на основі FlorisBoard з простішими налаштуваннями, посиленою приватністю й зашифрованим буфером із тегами. Функції з'являтимуться поступово.

## Прозорість

- **Основа:** [FlorisBoard](https://github.com/florisboard/florisboard) (Apache 2.0), історію змін збережено. Pysanka — незалежний проєкт.
- **Збірка:** APK збирається з цього коду в GitHub Actions окремо від підпису й підписується лише після схвалення авторки. Контрольні суми залежностей і обгортки Gradle перевіряються.
- **Підпис:** відбиток сертифіката SHA-256 `ea082ca6d8620c201948d08a9572c0b74a75101eb71b2752ceddcb4807d038ce`.
- **Як створено:** код написано за допомогою ШІ (Claude, Anthropic) під керівництвом і з тестуванням авторки.

Kotlin · Jetpack Compose · Rust. Сторонні матеріали — [NOTICE.md](NOTICE.md). Ліцензія Apache 2.0.

---

# Pysanka · English

A private keyboard for Android with no internet access. Typing, swipe, clipboard and, if you wish, an on-device AI that edits text right on the phone. Ukrainian and English interface, layouts for many languages. Free, no ads, no accounts.

> **Status: in development, test versions** in [Releases](../../releases). Bugs and wishes: [Issues](../../issues).

## Privacy

- No internet permission; the build fails if one appears. Nothing goes to a cloud backup or to another phone.
- Encrypted clipboard history (SQLCipher + AES-256, hardware-backed key); "Don't keep passwords" is your choice; password-protected clipboard copy and import from FlorisBoard. Coloured and automatic tags. Copied links are cleaned of tracking. Photos and screenshots into the history with one tap, with recognised text. Planned: search, snippets.
- Optional on-device AI (Gemma 4): grammar, style, summaries of long texts (numbers and names checked), translation, Russianisms, list, table, notes, letters, reply options, hiding personal data; "Share → Pysanka AI" from any app. Planned: social-media tools; on-device dictation (Whisper).
- On-device text recognition from photos and the camera.

Today this is a keyboard built on FlorisBoard with simpler settings, stronger privacy and an encrypted clipboard with tags. Features will arrive step by step.

## Transparency

- **Based on** [FlorisBoard](https://github.com/florisboard/florisboard) (Apache 2.0), with its history kept. Pysanka is an independent project.
- **Build:** the APK is built from this code in GitHub Actions, separately from signing, and is signed only after the author's approval. Dependency and Gradle wrapper checksums are verified.
- **Signing:** certificate SHA-256 `ea082ca6d8620c201948d08a9572c0b74a75101eb71b2752ceddcb4807d038ce`.
- **How it was made:** the code was written with AI (Claude, Anthropic) under the author's direction and testing.

Licence: Apache 2.0. Third-party material: [NOTICE.md](NOTICE.md).

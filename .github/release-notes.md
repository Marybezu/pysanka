**Pysanka 0.2.0** — приватна клавіатура для Android без доступу до інтернету. Тестова версія.

- 🔒 **Зашифрований буфер.** Історія буфера (текст і зображення) зашифрована; ключ в апаратному сховищі телефона. Поки телефон заблоковано після перезапуску, буфер закритий.
- 🔑 **«Не зберігати паролі»** (типово ввімкнено): скопійоване з менеджера паролів і з полів пароля не потрапляє в історію. Можна вимкнути, тоді доступне автовидалення чутливого через N секунд. Інкогніто — завжди без історії.
- 🖼️ **Зображення до 20 МБ, без відео** в історії.
- 💾 **Копія буфера** (Налаштування → Буфер → Копія буфера): експорт тексту й зображень у файл під паролем (Argon2id + AES-256) та імпорт, зокрема з резервної копії FlorisBoard.

⚠️ Історію буфера треба ввімкнути: Налаштування → Буфер → «Історія буфера». Обмеження історії типово 20 записів — для великого імпорту збільште його.

📲 Завантажте файл .apk нижче й відкрийте його на телефоні. 🔄 Для оновлень — Obtainium: «Додати застосунок» → `github.com/Marybezu/pysanka`.

---

**Pysanka 0.2.0** — a private keyboard for Android with no internet access. Test version.

- Encrypted clipboard history (text and images), key in the phone's hardware keystore.
- "Don't keep passwords" (on by default); incognito never kept.
- Images up to 20 MB, no videos in the history.
- Clipboard copy: password-protected export/import, including import from a FlorisBoard backup.

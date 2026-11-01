package ua.pysanka.privacy

/**
 * Pysanka: whether the field being typed in is private (incognito mode, or the app asked for no
 * learning, as browsers do in private tabs). Set by the keyboard when a field opens and closes;
 * read by anything that would otherwise remember what was typed (clipboard history, emoji history).
 */
object FieldPrivacy {
    @Volatile
    var isPrivate: Boolean = false

    /** A password field (nothing from it goes anywhere, the AI included). */
    @Volatile
    var isPassword: Boolean = false
}

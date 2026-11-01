/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.ClipData
import android.content.ClipDescription.EXTRA_IS_REMOTE_DEVICE
import android.content.ClipDescription.EXTRA_IS_SENSITIVE
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore.Images.Media
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.UriSerializer
import org.florisboard.lib.android.query
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.kotlin.tryOrNull

private const val CLIPBOARD_HISTORY_TABLE = "clipboard_history"
private const val CLIPBOARD_FILES_TABLE = "clipboard_files"

enum class ItemType(val value: Int) {
    TEXT(1),
    IMAGE(2),
    VIDEO(3);

    companion object {
        fun fromInt(value : Int) : ItemType {
            return entries.first { it.value == value }
        }
    }
}

/**
 * Represents an item on the clipboard.
 *
 * If type == ItemType.IMAGE there must be a uri set
 * if type == ItemType.TEXT there must be a text set
 */
@Serializable
@Entity(tableName = CLIPBOARD_HISTORY_TABLE)
data class ClipboardItem @OptIn(ExperimentalSerializationApi::class) constructor(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID, index = true)
    var id: Long = 0,
    val type: ItemType,
    val text: String?,
    @Serializable(with = UriSerializer::class)
    val uri: Uri?,
    val creationTimestampMs: Long,
    val isPinned: Boolean,
    val mimeTypes: List<String>,
    @EncodeDefault
    @ColumnInfo(name = "is_sensitive", defaultValue = "0")
    val isSensitive: Boolean = false,
    @EncodeDefault
    @ColumnInfo(name= "is_remote_device", defaultValue = "0")
    val isRemoteDevice: Boolean = false,
    /** Pysanka: ids of the owner's own tags, comma-separated (",3,7,"), empty when none. */
    @EncodeDefault
    @ColumnInfo(name = "pysanka_tags", defaultValue = "")
    val tags: String = "",
) {
    /** Pysanka: the tag ids as numbers. */
    fun tagIds(): Set<Long> = tags.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** Pysanka: a copy with these tag ids. */
    fun withTagIds(ids: Collection<Long>): ClipboardItem =
        copy(tags = if (ids.isEmpty()) "" else ids.sorted().joinToString(",", prefix = ",", postfix = ","))

    companion object {
        /**
         * So that every item doesn't have to allocate its own array.
         */
        private val TEXT_PLAIN = listOf("text/plain")
        private val MEDIA_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME)

        const val FLORIS_CLIP_LABEL = "florisboard/clipboard_item"

        /** Pysanka: true when the app that copied marked the clip as sensitive (a password, a code). */
        fun isSensitive(data: ClipData): Boolean =
            data.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) ?: false

        fun text(text: String): ClipboardItem {
            return ClipboardItem(
                type = ItemType.TEXT,
                text = text,
                uri = null,
                creationTimestampMs = System.currentTimeMillis(),
                isPinned = false,
                mimeTypes = TEXT_PLAIN,
            )
        }

        /**
         * Returns a new ClipboardItem based on a ClipData.
         *
         * @param data The ClipData to clone.
         * @param cloneUri Whether to store the image using [ClipboardMediaProvider].
         */
        fun fromClipData(context: Context, data: ClipData, cloneUri: Boolean) : ClipboardItem {
            val dataItem = data.getItemAt(0)
            val type = when {
                dataItem?.uri != null && data.description.hasMimeType("image/*") -> ItemType.IMAGE
                dataItem?.uri != null && data.description.hasMimeType("video/*") -> ItemType.VIDEO
                else -> ItemType.TEXT
            }

            // Pysanka: password managers set this flag on every Android version, not only 13+
            val isSensitive = isSensitive(data)

            val isRemoteDevice = if (AndroidVersion.ATLEAST_API34_U) {
                data.description?.extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE) ?: false
            } else {
                false
            }

            val uri = if (type == ItemType.IMAGE || type == ItemType.VIDEO) {
                if (dataItem.uri.authority == ClipboardMediaProvider.AUTHORITY || !cloneUri) {
                    dataItem.uri
                } else {
                    var displayName = when (type) {
                        ItemType.IMAGE -> "Image"
                        ItemType.VIDEO -> "Video"
                    }
                    tryOrNull {
                        context.contentResolver.query(dataItem.uri, MEDIA_PROJECTION)?.use { cursor ->
                            val displayNameColumn = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToNext()) {
                                cursor.getStringOrNull(displayNameColumn)?.let { displayName = it }
                            }
                        }
                    }
                    val values = ContentValues(3).apply {
                        put(OpenableColumns.DISPLAY_NAME, displayName)
                        put(ClipboardMediaProvider.Columns.MediaUri, dataItem.uri.toString())
                        put(ClipboardMediaProvider.Columns.MimeTypes, data.description.filterMimeTypes("*/*").joinToString(","))
                    }
                    context.contentResolver.insert(when (type) {
                        ItemType.IMAGE -> ClipboardMediaProvider.IMAGE_CLIPS_URI
                        ItemType.VIDEO -> ClipboardMediaProvider.VIDEO_CLIPS_URI
                    }, values)
                }
            } else { null }

            val text = dataItem.text?.toString()
            val mimeTypes = when (type) {
                ItemType.TEXT -> TEXT_PLAIN
                ItemType.IMAGE, ItemType.VIDEO -> {
                    List(data.description.mimeTypeCount) { data.description.getMimeType(it) }
                }
            }

            return ClipboardItem(0, type, text, uri, System.currentTimeMillis(), false, mimeTypes, isSensitive, isRemoteDevice)
        }
    }

    @Composable
    inline fun displayText(): String {
        val context = LocalContext.current
        return displayText(context)
    }

    fun displayText(context: Context): String {
        return if (isSensitive) {
            context.stringRes(R.string.clipboard__sensitive_clip_content)
        } else {
            stringRepresentation()
        }
    }

    infix fun isEqualTo(other: ClipData?): Boolean {
        if (other == null) return false
        return when (type) {
            ItemType.TEXT -> text == other.getItemAt(0).text
            ItemType.IMAGE, ItemType.VIDEO -> uri == other.getItemAt(0).uri
        }
    }

    /**
     * Creates a new ClipData which has the same contents as this.
     */
    fun toClipData(context: Context): ClipData {
        return when (type) {
            ItemType.TEXT -> {
                ClipData.newPlainText(FLORIS_CLIP_LABEL, text)
            }
            ItemType.IMAGE, ItemType.VIDEO -> {
                ClipData.newUri(context.contentResolver, FLORIS_CLIP_LABEL, uri)
            }
        }
    }

    /**
     * Instructs the content provider to delete this URI. If not an image, is a noop
     */
    fun close(context: Context) {
        if (type == ItemType.IMAGE) {
            tryOrNull { context.contentResolver.delete(this.uri!!, null, null) }
        }
    }

    fun stringRepresentation(): String {
        return when {
            // Pysanka: an image may hold the text recognised on it; it is still shown as an image
            type == ItemType.TEXT && text != null -> text
            uri != null -> "(Image) $uri"
            else -> "#ERROR"
        }
    }
}

class Converters {
    @TypeConverter
    fun uriFromString(value: String?): Uri? {
        return value?.toUri()
    }

    @TypeConverter
    fun stringFromUri(value: Uri?): String {
        return value.toString()
    }

    @TypeConverter
    fun itemTypeToInt(value: ItemType?): Int? {
        return value?.value
    }

    @TypeConverter
    fun intToItemType(value: Int?): ItemType? {
        return value?.let { ItemType.fromInt(it) }
    }

    /**
     * Only works because the string array is a mimetype.
     * DOES NOT USE A GENERALIZED FORMAT.
     */
    @TypeConverter
    fun mimeTypesToString(mimeTypes: List<String>): String {
        return mimeTypes.joinToString(",")
    }

    @TypeConverter
    fun stringToMimeTypes(value: String): List<String> {
        return value.split(",")
    }
}

@Dao
interface ClipboardHistoryDao {
    @Query("SELECT * FROM $CLIPBOARD_HISTORY_TABLE")
    fun getAll(): List<ClipboardItem>

    @Query("SELECT * FROM $CLIPBOARD_HISTORY_TABLE")
    fun getAllAsFlow(): Flow<List<ClipboardItem>>

    @Insert
    fun insert(item: ClipboardItem): Long

    @Update
    fun update(item: ClipboardItem)

    @Update
    fun update(items: List<ClipboardItem>)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE ${BaseColumns._ID} = :id")
    fun delete(id: Long)

    // Pysanka: changes of one field only, so two changes at once never undo each other
    /** The text recognised on an image (type 2). */
    @Query("UPDATE $CLIPBOARD_HISTORY_TABLE SET text = :text WHERE ${BaseColumns._ID} = :id AND type = 2")
    fun setImageText(id: Long, text: String)

    @Query("UPDATE $CLIPBOARD_HISTORY_TABLE SET isPinned = :pinned WHERE ${BaseColumns._ID} = :id")
    fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE $CLIPBOARD_HISTORY_TABLE SET pysanka_tags = :tags WHERE ${BaseColumns._ID} = :id")
    fun setTags(id: Long, tags: String)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE ${BaseColumns._ID} = :id AND not isPinned ")
    fun deleteIfUnpinned(id: Long)

    @Delete
    fun delete(items: List<ClipboardItem>)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE")
    fun deleteAll()

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE type = :type")
    fun deleteAllFromType(type: ItemType)

    @Query("DELETE FROM $CLIPBOARD_HISTORY_TABLE WHERE NOT isPinned")
    fun deleteAllUnpinned()
}

/** Pysanka: the owner's own clipboard tag: a name and a colour (always shown with its name). */
@Entity(tableName = "pysanka_clipboard_tags")
data class ClipboardTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** Index into the tag palette (ua.pysanka.clipboard.TagColors). */
    val color: Int,
    val position: Int,
)

@Dao
interface ClipboardTagDao {
    @Query("SELECT * FROM pysanka_clipboard_tags ORDER BY position, id")
    fun getAllAsFlow(): Flow<List<ClipboardTag>>

    @Query("SELECT * FROM pysanka_clipboard_tags ORDER BY position, id")
    fun getAll(): List<ClipboardTag>

    @Insert
    fun insert(tag: ClipboardTag): Long

    @Update
    fun update(tag: ClipboardTag)

    @Query("DELETE FROM pysanka_clipboard_tags WHERE id = :id")
    fun delete(id: Long)

    /** Removes a deleted tag from every item. */
    @Query("UPDATE $CLIPBOARD_HISTORY_TABLE SET pysanka_tags = REPLACE(pysanka_tags, ',' || :id || ',', ',') WHERE pysanka_tags LIKE '%,' || :id || ',%'")
    fun untagAll(id: Long)

    /** Tidies items left with only "," after untagging. */
    @Query("UPDATE $CLIPBOARD_HISTORY_TABLE SET pysanka_tags = '' WHERE pysanka_tags = ','")
    fun clearEmptyTags()
}

@Database(
    entities = [ClipboardItem::class, ClipboardTag::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 2, to = 4),
        AutoMigration(from = 3, to = 4, spec = ClipboardHistoryDatabase.MIGRATE_3_TO_4::class),
        // Pysanka: tags (a new table and a column with an empty default; nothing existing changes)
        AutoMigration(from = 4, to = 5),
    ],
)
@TypeConverters(Converters::class)
abstract class ClipboardHistoryDatabase : RoomDatabase() {
    abstract fun clipboardItemDao(): ClipboardHistoryDao
    abstract fun clipboardTagDao(): ClipboardTagDao

    @RenameColumn(
        tableName = CLIPBOARD_HISTORY_TABLE,
        fromColumnName = "isSensitive",
        toColumnName = "is_sensitive",
    )
    @RenameColumn(
        tableName = CLIPBOARD_HISTORY_TABLE,
        fromColumnName = "isRemoteDevice",
        toColumnName = "is_remote_device",
    )
    class MIGRATE_3_TO_4 : AutoMigrationSpec

    companion object {
        fun new(context: Context): ClipboardHistoryDatabase {
            return Room
                .databaseBuilder(
                    context, ClipboardHistoryDatabase::class.java, CLIPBOARD_HISTORY_TABLE,
                )
                // Pysanka: encrypted with SQLCipher; the caller has opened the clipboard vault
                .openHelperFactory(ua.pysanka.clipboard.SecureClipboard.openHelperFactory())
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

@Serializable
@Entity(tableName = CLIPBOARD_FILES_TABLE)
data class ClipboardFileInfo(
    @PrimaryKey @ColumnInfo(name=BaseColumns._ID, index=true) val id: Long,
    @ColumnInfo(name=OpenableColumns.DISPLAY_NAME) val displayName: String,
    @ColumnInfo(name=OpenableColumns.SIZE) val size: Long,
    @ColumnInfo(name=Media.ORIENTATION) val orientation: Int,
    val mimeTypes: List<String>,
)

@Dao
interface ClipboardFilesDao {
    @Query("SELECT * FROM $CLIPBOARD_FILES_TABLE WHERE ${BaseColumns._ID} == (:uid)")
    fun getById(uid: Long) : ClipboardFileInfo

    @Query("SELECT * FROM $CLIPBOARD_FILES_TABLE WHERE ${BaseColumns._ID} == (:uid)")
    fun getCursorById(uid: Long) : Cursor

    @Query("SELECT (:projection) FROM $CLIPBOARD_FILES_TABLE WHERE ${BaseColumns._ID} == (:uid)")
    fun getCurserByIdWithColums(uid: Long, projection: String) : Cursor

    @Query("DELETE FROM $CLIPBOARD_FILES_TABLE WHERE ${BaseColumns._ID} == (:id)")
    fun delete(id: Long)

    @Insert
    fun insert(vararg clipboardFileInfos: ClipboardFileInfo)

    @Query("SELECT * FROM $CLIPBOARD_FILES_TABLE")
    fun getAll(): List<ClipboardFileInfo>
}

@Database(entities = [ClipboardFileInfo::class], version = 2)
@TypeConverters(Converters::class)
abstract class ClipboardFilesDatabase : RoomDatabase() {
    abstract fun clipboardFilesDao() : ClipboardFilesDao

    companion object {
        fun new(context: Context): ClipboardFilesDatabase {
            return Room
                .databaseBuilder(
                    context, ClipboardFilesDatabase::class.java, CLIPBOARD_FILES_TABLE,
                )
                // Pysanka: encrypted with SQLCipher; the caller has opened the clipboard vault
                .openHelperFactory(ua.pysanka.clipboard.SecureClipboard.openHelperFactory())
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

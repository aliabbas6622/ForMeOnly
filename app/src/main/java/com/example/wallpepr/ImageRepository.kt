package com.example.wallpepr

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

object ImageRepository {
    data class ImageItem(val name: String, val uri: Uri)

    private var cachedTreeUri: Uri? = null
    private var cachedImages: List<ImageItem> = emptyList()
    private var cacheLoaded = false

    @Synchronized
    fun invalidate() {
        cachedTreeUri = null
        cachedImages = emptyList()
        cacheLoaded = false
    }

    /** Drop only the in-process RAM cache; persisted cache in SharedPreferences is kept. */
    @Synchronized
    fun clearMemoryCache() {
        cachedImages = emptyList()
        cacheLoaded = false
        // Note: cachedTreeUri is intentionally kept so readPersistedCache() can reload
    }

    fun invalidate(context: Context) {
        invalidate()
        Prefs.clearCachedImages(context)
    }

    @Synchronized
    fun listImages(context: Context, treeUri: Uri): List<ImageItem> {
        if (cacheLoaded && cachedTreeUri == treeUri) return cachedImages
        readPersistedCache(context, treeUri)?.let { return it }

        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<ImageItem>()
        readChildren(context, treeUri, rootDocumentId, results)
        cachedTreeUri = treeUri
        cachedImages = results.sortedBy { it.name.lowercase() }
        cacheLoaded = true
        persistCache(context, treeUri, cachedImages)
        return cachedImages
    }

    fun peekNext(context: Context): Uri? {
        val folder = Prefs.folderUri(context) ?: return null
        val images = listImages(context, folder)
        if (images.isEmpty()) return null

        val candidates = images.filter { Prefs.failureCount(context, it.uri) < MAX_FAILURES }
        if (candidates.isEmpty()) return null

        return when (Prefs.mode(context)) {
            Prefs.MODE_SHUFFLE -> candidates.random().uri
            else -> candidates[Prefs.nextIndex(context).floorMod(candidates.size)].uri
        }
    }

    fun takeNext(context: Context): Uri? {
        val folder = Prefs.folderUri(context) ?: return null
        val images = listImages(context, folder)
        if (images.isEmpty()) return null

        val candidates = images.filter { Prefs.failureCount(context, it.uri) < MAX_FAILURES }
        if (candidates.isEmpty()) return null

        return when (Prefs.mode(context)) {
            Prefs.MODE_SHUFFLE -> candidates.random().uri
            else -> {
                val selectedIndex = Prefs.nextIndex(context).floorMod(candidates.size)
                Prefs.setNextIndex(context, (selectedIndex + 1).floorMod(candidates.size))
                candidates[selectedIndex].uri
            }
        }
    }

    private fun readChildren(
        context: Context,
        treeUri: Uri,
        documentId: String,
        results: MutableList<ImageItem>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val childId = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: "Image"
                val mime = cursor.getString(mimeColumn) ?: continue

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    readChildren(context, treeUri, childId, results)
                } else if (mime.startsWith("image/")) {
                    results += ImageItem(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    )
                }
            }
        }
    }

    private fun readPersistedCache(context: Context, treeUri: Uri): List<ImageItem>? {
        if (Prefs.cachedTreeUri(context) != treeUri) return null
        val raw = Prefs.cachedImages(context) ?: return null
        val parsed = runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index ->
                val item = json.getJSONObject(index)
                ImageItem(item.getString("name"), Uri.parse(item.getString("uri")))
            }
        }.getOrNull() ?: return null

        cachedTreeUri = treeUri
        cachedImages = parsed
        cacheLoaded = true
        return cachedImages
    }

    private fun persistCache(context: Context, treeUri: Uri, images: List<ImageItem>) {
        val json = JSONArray()
        images.take(MAX_CACHE_ITEMS).forEach { image ->
            json.put(
                JSONObject()
                    .put("name", image.name)
                    .put("uri", image.uri.toString())
            )
        }
        Prefs.setCachedImages(context, treeUri, json.toString())
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private const val MAX_FAILURES = 2
    private const val MAX_CACHE_ITEMS = 500
}

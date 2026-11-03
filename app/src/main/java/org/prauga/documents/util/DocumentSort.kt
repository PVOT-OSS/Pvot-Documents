package org.prauga.documents.util

import org.prauga.documents.models.DocumentEntry

/** How a document list is ordered. */
enum class DocumentSort {
    Name,
    DateNewest,
    SizeLargest
}

/** [items] ordered by [sort], reading each element's [DocumentEntry] through [document]. */
fun <T> List<T>.sortedByDocument(sort: DocumentSort, document: (T) -> DocumentEntry): List<T> =
    when (sort) {
        DocumentSort.Name -> sortedBy { document(it).name.lowercase() }
        DocumentSort.DateNewest -> sortedByDescending { document(it).lastModified }
        DocumentSort.SizeLargest -> sortedByDescending { document(it).sizeBytes }
    }

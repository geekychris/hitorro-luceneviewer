package com.hitorro.luceneviewer;

public record LuceneIndexStats(
        int maxDoc,
        int numDocs,
        boolean hasDeletions
) {
}

package com.hitorro.luceneviewer;

public record LuceneTermSummary(
        String term,
        int docFreq,
        long totalTermFreq
) {
}

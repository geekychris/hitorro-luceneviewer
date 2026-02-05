package com.hitorro.luceneviewer;

import java.util.List;

public record LuceneSearchPage(
        String query,
        String defaultField,
        long totalHits,
        int limit,
        List<LuceneSearchHit> hits,
        String nextPageToken
) {
}

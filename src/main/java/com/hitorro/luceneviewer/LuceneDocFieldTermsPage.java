package com.hitorro.luceneviewer;

import java.util.List;

public record LuceneDocFieldTermsPage(
        int docId,
        String field,
        String source,
        String after,
        int limit,
        List<LuceneDocFieldTermsPage.DocTerm> terms,
        String nextAfter,
        String message
) {
    public record DocTerm(
            String term,
            long freq
    ) {
    }
}

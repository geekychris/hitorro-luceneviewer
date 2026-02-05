package com.hitorro.luceneviewer;

import java.util.List;

public record LuceneTermsPage(
        String field,
        String after,
        int limit,
        List<LuceneTermSummary> terms,
        String nextAfter
) {
}

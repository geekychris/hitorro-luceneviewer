package com.hitorro.luceneviewer;

import java.util.List;
import java.util.Map;

public record LuceneSearchHit(
        int docId,
        float score,
        Map<String, List<Object>> storedFields,
        String kvStatus,
        String kvKey,
        Object kvDoc
) {
}

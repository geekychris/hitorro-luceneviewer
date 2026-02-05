package com.hitorro.luceneviewer;

import java.util.List;
import java.util.Map;

public record LuceneStoredDocument(
        int docId,
        boolean deleted,
        Map<String, List<Object>> storedFields
) {
}

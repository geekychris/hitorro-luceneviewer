package com.hitorro.luceneviewer;

import java.util.List;

/**
 * Postings data for a specific term in a specific field,
 * showing per-document positions, offsets, and payloads.
 */
public record LuceneTermPostingsPage(
        String field,
        String term,
        int limit,
        int totalDocsWithTerm,
        List<DocPostings> docs,
        String message
) {
    public record DocPostings(
            int docId,
            int freq,
            List<PositionInfo> positions
    ) {}

    public record PositionInfo(
            int position,
            int startOffset,
            int endOffset,
            String payload
    ) {}
}

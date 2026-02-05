package com.hitorro.luceneviewer;

/**
 * Summary of a Lucene field as observed from {@link org.apache.lucene.index.FieldInfo}.
 *
 * <p>Note: Lucene does not expose a single "stored" flag in FieldInfo; storage is best
 * inferred from stored-fields retrieval at the document level.
 */
public record LuceneFieldSummary(
        String name,
        String indexOptions,
        String docValuesType,
        boolean hasNorms,
        boolean hasPayloads,
        boolean hasTermVectors,
        int pointDimensionCount,
        int pointIndexDimensionCount,
        int pointNumBytes,
        int vectorDimension,
        String vectorEncoding,
        String vectorSimilarity
) {
}

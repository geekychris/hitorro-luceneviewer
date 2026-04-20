package com.hitorro.luceneviewer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.queryparser.classic.QueryParser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;

public final class LuceneViewer {

    private LuceneViewer() {
    }

    public static Directory openDirectory(Path indexPath) throws IOException {
        return FSDirectory.open(indexPath);
    }

    public static DirectoryReader openReader(Directory directory) throws IOException {
        return DirectoryReader.open(directory);
    }

    public static LuceneIndexStats stats(IndexReader reader) {
        return new LuceneIndexStats(reader.maxDoc(), reader.numDocs(), reader.hasDeletions());
    }

    public static List<LuceneFieldSummary> listFields(IndexReader reader) throws IOException {
        FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        List<LuceneFieldSummary> out = new ArrayList<>(fieldInfos.size());

        for (FieldInfo fi : fieldInfos) {
            out.add(new LuceneFieldSummary(
                    fi.name,
                    fi.getIndexOptions() != null ? fi.getIndexOptions().toString() : null,
                    fi.getDocValuesType() != null ? fi.getDocValuesType().toString() : null,
                    fi.hasNorms(),
                    fi.hasPayloads(),
                    fi.hasVectors(),
                    fi.getPointDimensionCount(),
                    fi.getPointIndexDimensionCount(),
                    fi.getPointNumBytes(),
                    fi.getVectorDimension(),
                    fi.getVectorEncoding() != null ? fi.getVectorEncoding().toString() : null,
                    fi.getVectorSimilarityFunction() != null ? fi.getVectorSimilarityFunction().toString() : null
            ));
        }

        out.sort(Comparator.comparing(LuceneFieldSummary::name));
        return out;
    }

    public static LuceneStoredDocument getStoredDocument(IndexReader reader, int docId) throws IOException {
        if (docId < 0 || docId >= reader.maxDoc()) {
            throw new IllegalArgumentException("docId out of range: " + docId);
        }

        boolean deleted = isDeleted(reader, docId);
        if (deleted) {
            return new LuceneStoredDocument(docId, true, Map.of());
        }

        Document doc = reader.storedFields().document(docId);
        return new LuceneStoredDocument(docId, false, extractStoredFields(doc));
    }

    public static List<LuceneStoredDocument> listDocuments(IndexReader reader,
                                                          int docIdStart,
                                                          int limit,
                                                          boolean includeStoredFields,
                                                          boolean includeDeleted) throws IOException {
        int start = Math.max(0, docIdStart);
        int end = Math.min(reader.maxDoc(), start + Math.max(0, limit));

        List<LuceneStoredDocument> out = new ArrayList<>(Math.max(0, end - start));
        for (int docId = start; docId < end; docId++) {
            boolean deleted = isDeleted(reader, docId);
            if (deleted && !includeDeleted) {
                continue;
            }

            Map<String, List<Object>> fields = Map.of();
            if (includeStoredFields && !deleted) {
                Document doc = reader.storedFields().document(docId);
                fields = extractStoredFields(doc);
            }

            out.add(new LuceneStoredDocument(docId, deleted, fields));
        }

        return out;
    }

    public static LuceneTermsPage listTerms(IndexReader reader,
                                           String field,
                                           String after,
                                           int limit) throws IOException {
        int effectiveLimit = Math.max(1, Math.min(limit, 5000));

        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new LuceneTermsPage(field, after, effectiveLimit, List.of(), null);
        }

        TermsEnum te = terms.iterator();

        // Position enum
        if (after != null && !after.isBlank()) {
            BytesRef afterBytes = new BytesRef(after);
            TermsEnum.SeekStatus status = te.seekCeil(afterBytes);
            if (status == TermsEnum.SeekStatus.END) {
                return new LuceneTermsPage(field, after, effectiveLimit, List.of(), null);
            }
            if (status == TermsEnum.SeekStatus.FOUND) {
                // "after" is exclusive
                if (te.next() == null) {
                    return new LuceneTermsPage(field, after, effectiveLimit, List.of(), null);
                }
            }
        } else {
            // No cursor: must call next() to move to first term
            if (te.next() == null) {
                return new LuceneTermsPage(field, after, effectiveLimit, List.of(), null);
            }
        }

        List<LuceneTermSummary> out = new ArrayList<>(effectiveLimit);
        String nextAfter = null;

        for (int i = 0; i < effectiveLimit; i++) {
            BytesRef term = te.term();
            if (term == null) {
                break;
            }

            String termText = term.utf8ToString();
            out.add(new LuceneTermSummary(termText, te.docFreq(), te.totalTermFreq()));
            nextAfter = termText;

            if (te.next() == null) {
                break;
            }
        }

        return new LuceneTermsPage(field, after, effectiveLimit, out, nextAfter);
    }

    public static LuceneSearchPage search(IndexSearcher searcher,
                                         String queryString,
                                         String defaultField,
                                         int limit,
                                         String pageToken,
                                         boolean includeStoredFields) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit, 200));
        String effectiveDefaultField = (defaultField == null || defaultField.isBlank()) ? "content" : defaultField;

        QueryParser parser = new QueryParser(effectiveDefaultField, new StandardAnalyzer());
        Query query = parser.parse(queryString);

        ScoreDoc after = decodePageToken(pageToken);

        TopDocs topDocs = searcher.searchAfter(after, query, effectiveLimit);

        List<LuceneSearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Map<String, List<Object>> stored = Map.of();
            if (includeStoredFields) {
                Document doc = searcher.storedFields().document(sd.doc);
                stored = extractStoredFields(doc);
            }
            hits.add(new LuceneSearchHit(sd.doc, sd.score, stored, null, null, null));
        }

        String nextToken = null;
        if (topDocs.scoreDocs.length > 0) {
            ScoreDoc last = topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
            nextToken = encodePageToken(last);
        }

        return new LuceneSearchPage(queryString, effectiveDefaultField, topDocs.totalHits.value, effectiveLimit, hits, nextToken);
    }

    /**
     * List terms present for a single document+field.
     *
     * <p>Prefers term vectors if available. If term vectors are not available, this
     * falls back to analyzing the stored field(s) with StandardAnalyzer (best-effort).
     */
    public static LuceneDocFieldTermsPage listDocFieldTerms(IndexReader reader,
                                                            int docId,
                                                            String field,
                                                            String after,
                                                            int limit,
                                                            Map<String, List<Object>> storedFieldsFallback) throws IOException {

        int effectiveLimit = Math.max(1, Math.min(limit, 5000));

        if (docId < 0 || docId >= reader.maxDoc()) {
            throw new IllegalArgumentException("docId out of range: " + docId);
        }

        if (isDeleted(reader, docId)) {
            return new LuceneDocFieldTermsPage(docId, field, "none", after, effectiveLimit, List.of(), null,
                    "Document is deleted");
        }

        // 1) Term vectors
        Terms tv = reader.getTermVector(docId, field);
        if (tv != null) {
            return listTermsFromTermsEnum(docId, field, after, effectiveLimit, tv.iterator(), "termVectors", null);
        }

        // 2) Fallback: analyze stored field values
        Map<String, Long> counts = analyzeStoredFieldTerms(field, storedFieldsFallback);
        if (counts.isEmpty()) {
            return new LuceneDocFieldTermsPage(docId, field, "analyzedStoredField", after, effectiveLimit, List.of(), null,
                    "No term vectors for field, and no stored text to analyze");
        }

        return pageDocTerms(docId, field, after, effectiveLimit, counts, "analyzedStoredField",
                "No term vectors; terms derived by analyzing stored field value(s) with StandardAnalyzer (may differ from index analyzer)");
    }

    private static LuceneDocFieldTermsPage listTermsFromTermsEnum(int docId,
                                                                  String field,
                                                                  String after,
                                                                  int limit,
                                                                  TermsEnum te,
                                                                  String source,
                                                                  String message) throws IOException {

        // Position enum
        if (after != null && !after.isBlank()) {
            BytesRef afterBytes = new BytesRef(after);
            TermsEnum.SeekStatus status = te.seekCeil(afterBytes);
            if (status == TermsEnum.SeekStatus.END) {
                return new LuceneDocFieldTermsPage(docId, field, source, after, limit, List.of(), null, message);
            }
            if (status == TermsEnum.SeekStatus.FOUND) {
                // after is exclusive
                if (te.next() == null) {
                    return new LuceneDocFieldTermsPage(docId, field, source, after, limit, List.of(), null, message);
                }
            }
        } else {
            if (te.next() == null) {
                return new LuceneDocFieldTermsPage(docId, field, source, after, limit, List.of(), null, message);
            }
        }

        List<LuceneDocFieldTermsPage.DocTerm> out = new ArrayList<>(limit);
        String nextAfter = null;

        for (int i = 0; i < limit; i++) {
            BytesRef term = te.term();
            if (term == null) {
                break;
            }

            String termText = term.utf8ToString();
            long freq = te.totalTermFreq();
            out.add(new LuceneDocFieldTermsPage.DocTerm(termText, freq));
            nextAfter = termText;

            if (te.next() == null) {
                break;
            }
        }

        return new LuceneDocFieldTermsPage(docId, field, source, after, limit, out, nextAfter, message);
    }

    private static Map<String, Long> analyzeStoredFieldTerms(String field, Map<String, List<Object>> storedFields) {
        if (storedFields == null) {
            return Map.of();
        }

        List<Object> vals = storedFields.get(field);
        if (vals == null || vals.isEmpty()) {
            return Map.of();
        }

        StringBuilder sb = new StringBuilder();
        for (Object v : vals) {
            if (v == null) {
                continue;
            }
            // Only analyze string-ish values
            if (v instanceof String s) {
                if (!s.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(s);
                }
            }
        }

        String text = sb.toString();
        if (text.isBlank()) {
            return Map.of();
        }

        Analyzer analyzer = new StandardAnalyzer();
        Map<String, Long> counts = new HashMap<>();

        try (TokenStream ts = analyzer.tokenStream(field, new StringReader(text))) {
            CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                String t = termAttr.toString();
                counts.merge(t, 1L, Long::sum);
            }
            ts.end();
        } catch (Exception e) {
            return Map.of();
        }

        return counts;
    }

    private static LuceneDocFieldTermsPage pageDocTerms(int docId,
                                                        String field,
                                                        String after,
                                                        int limit,
                                                        Map<String, Long> counts,
                                                        String source,
                                                        String message) {

        List<String> terms = new ArrayList<>(counts.keySet());
        terms.sort(String::compareTo);

        int startIdx = 0;
        if (after != null && !after.isBlank()) {
            int pos = Collections.binarySearch(terms, after);
            startIdx = pos >= 0 ? pos + 1 : (-pos - 1);
        }

        int endIdx = Math.min(terms.size(), startIdx + limit);
        List<LuceneDocFieldTermsPage.DocTerm> out = new ArrayList<>(Math.max(0, endIdx - startIdx));
        String nextAfter = null;

        for (int i = startIdx; i < endIdx; i++) {
            String t = terms.get(i);
            out.add(new LuceneDocFieldTermsPage.DocTerm(t, counts.getOrDefault(t, 0L)));
            nextAfter = t;
        }

        return new LuceneDocFieldTermsPage(docId, field, source, after, limit, out, nextAfter, message);
    }

    /**
     * List postings (positions, offsets, payloads) for a specific term in a specific field.
     * Shows exactly how the term is stored in the index at each document position.
     *
     * @param reader the IndexReader
     * @param field  the field name
     * @param term   the term text to look up
     * @param limit  max documents to return (capped at 1000)
     * @return postings page with per-document position details
     */
    public static LuceneTermPostingsPage listTermPostings(IndexReader reader,
                                                           String field,
                                                           String term,
                                                           int limit) throws IOException {
        int effectiveLimit = Math.max(1, Math.min(limit, 1000));

        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new LuceneTermPostingsPage(field, term, effectiveLimit, 0, List.of(),
                    "Field not found: " + field);
        }

        TermsEnum te = terms.iterator();
        if (!te.seekExact(new BytesRef(term))) {
            return new LuceneTermPostingsPage(field, term, effectiveLimit, 0, List.of(),
                    "Term not found in field: " + term);
        }

        int docFreq = te.docFreq();

        // Request ALL flags: positions, offsets, payloads
        PostingsEnum postings = te.postings(null, PostingsEnum.ALL);
        List<LuceneTermPostingsPage.DocPostings> docs = new ArrayList<>(effectiveLimit);
        int count = 0;

        while (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS && count < effectiveLimit) {
            int docId = postings.docID();
            int freq = postings.freq();

            List<LuceneTermPostingsPage.PositionInfo> positions = new ArrayList<>(freq);
            for (int i = 0; i < freq; i++) {
                int pos = postings.nextPosition();
                int startOff = postings.startOffset();
                int endOff = postings.endOffset();
                BytesRef payload = postings.getPayload();
                String payloadStr = null;
                if (payload != null) {
                    byte[] copy = Arrays.copyOfRange(payload.bytes, payload.offset, payload.offset + payload.length);
                    payloadStr = Base64.getEncoder().encodeToString(copy);
                }
                positions.add(new LuceneTermPostingsPage.PositionInfo(pos, startOff, endOff, payloadStr));
            }

            docs.add(new LuceneTermPostingsPage.DocPostings(docId, freq, positions));
            count++;
        }

        return new LuceneTermPostingsPage(field, term, effectiveLimit, docFreq, docs, null);
    }

    /**
     * Lucene internal docId delete (best-effort).
     *
     * @return Lucene sequence number for the delete, or -1 if the docId cannot be resolved in this reader.
     */
    public static final long tryDeleteDocument(IndexWriter indexWriter, DirectoryReader reader, int docId) throws IOException {
        return indexWriter.tryDeleteDocument(reader, docId);
    }

    public static boolean wasDeleted(long deleteSeqNo) {
        return deleteSeqNo != -1;
    }

    public static void forceMerge(IndexWriter indexWriter, int maxSegments) throws IOException {
        indexWriter.forceMerge(Math.max(1, maxSegments));
    }

    public static Map<String, List<Object>> extractStoredFields(Document doc) {
        Map<String, List<Object>> out = new TreeMap<>();
        for (IndexableField f : doc.getFields()) {
            String name = f.name();
            Object val = extractStoredFieldValue(f);
            out.computeIfAbsent(name, ignored -> new ArrayList<>()).add(val);
        }
        return out;
    }

    private static Object extractStoredFieldValue(IndexableField f) {
        if (f.numericValue() != null) {
            return f.numericValue();
        }
        if (f.binaryValue() != null) {
            BytesRef bytes = f.binaryValue();
            byte[] copy = Arrays.copyOfRange(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
            return Base64.getEncoder().encodeToString(copy);
        }
        if (f.stringValue() != null) {
            return f.stringValue();
        }
        return null;
    }

    private static boolean isDeleted(IndexReader reader, int docId) {
        if (!(reader instanceof CompositeReader composite)) {
            return false;
        }
        try {
            // MultiBits works for CompositeReaders (DirectoryReader, MultiReader, etc.)
            var liveDocs = MultiBits.getLiveDocs(composite);
            return liveDocs != null && !liveDocs.get(docId);
        } catch (Exception e) {
            return false;
        }
    }

    private static String encodePageToken(ScoreDoc sd) {
        String raw = sd.doc + ":" + sd.score;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ScoreDoc decodePageToken(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(pageToken);
            String raw = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            int idx = raw.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            int doc = Integer.parseInt(raw.substring(0, idx));
            float score = Float.parseFloat(raw.substring(idx + 1));
            return new ScoreDoc(doc, score);
        } catch (Exception e) {
            return null;
        }
    }
}

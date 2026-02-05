package com.hitorro.luceneviewer;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneViewerTest {

    @Test
    void canListFieldsAndFetchStoredDoc() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();

        try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document d = new Document();
            d.add(new StringField("id", "doc-1", StringField.Store.YES));
            d.add(new TextField("title", "Hello World", TextField.Store.YES));
            w.addDocument(d);
            w.commit();
        }

        try (DirectoryReader r = DirectoryReader.open(dir)) {
            var fields = LuceneViewer.listFields(r);
            assertThat(fields).extracting(LuceneFieldSummary::name).contains("id", "title");

            var doc = LuceneViewer.getStoredDocument(r, 0);
            assertThat(doc.deleted()).isFalse();
            assertThat(doc.storedFields()).containsKey("id");

            var terms = LuceneViewer.listTerms(r, "title", null, 10);
            assertThat(terms.terms()).isNotEmpty();
        }
    }
}

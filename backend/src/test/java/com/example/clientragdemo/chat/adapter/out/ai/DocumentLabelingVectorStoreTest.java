package com.example.clientragdemo.chat.adapter.out.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentLabelingVectorStoreTest {

    /** A store that returns canned results and records what is written through it. */
    private static final class FakeStore implements VectorStore {
        final List<Document> results;
        final List<Document> added = new ArrayList<>();
        SearchRequest lastRequest;

        FakeStore(List<Document> results) {
            this.results = results;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            lastRequest = request;
            return results;
        }

        @Override
        public void add(List<Document> documents) {
            added.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }
    }

    private static Document chunk(String id, String text, Map<String, Object> metadata, double score) {
        return Document.builder().id(id).text(text).metadata(metadata).score(score).build();
    }

    @Test
    void prefixesEachChunkWithItsDocumentIdAndFilename() {
        FakeStore store = new FakeStore(List.of(chunk("c1", "Podman needs a running machine.", Map.of("document_id", 42L, "filename", "notes.txt"), 0.9)));

        List<Document> found = new DocumentLabelingVectorStore(store).similaritySearch(SearchRequest.builder().query("podman").topK(3).build());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getText()).isEqualTo("[document 42: notes.txt]\nPodman needs a running machine.");
    }

    @Test
    void keepsTheIdMetadataAndScoreSoCitationsStayTheSame() {
        FakeStore store = new FakeStore(List.of(chunk("c1", "text", Map.of("document_id", 7L, "filename", "a.pdf"), 0.5)));

        Document found = new DocumentLabelingVectorStore(store).similaritySearch(SearchRequest.builder().query("q").build()).get(0);

        assertThat(found.getId()).isEqualTo("c1");
        assertThat(found.getMetadata()).containsEntry("document_id", 7L).containsEntry("filename", "a.pdf");
        assertThat(found.getScore()).isEqualTo(0.5);
    }

    @Test
    void leavesAChunkWithoutADocumentIdUntouched() {
        Document unlabelled = chunk("c1", "text", Map.of("source", "elsewhere"), 0.4);

        assertThat(new DocumentLabelingVectorStore(new FakeStore(List.of(unlabelled))).similaritySearch(SearchRequest.builder().query("q").build()))
                .containsExactly(unlabelled);
    }

    @Test
    void passesTheSearchRequestAndWritesStraightThrough() {
        FakeStore store = new FakeStore(List.of());
        DocumentLabelingVectorStore view = new DocumentLabelingVectorStore(store);
        SearchRequest request = SearchRequest.builder().query("q").topK(3).build();

        view.similaritySearch(request);
        view.add(List.of(chunk("n", "new", Map.of(), 0.0)));

        assertThat(store.lastRequest).isSameAs(request);
        assertThat(store.added).hasSize(1);
    }
}

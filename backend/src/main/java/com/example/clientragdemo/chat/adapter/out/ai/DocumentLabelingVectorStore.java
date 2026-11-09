package com.example.clientragdemo.chat.adapter.out.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Optional;

/**
 * A view of the vector store for the retrieval advisor that prefixes each retrieved chunk with
 * {@code [document <id>: <filename>]}. The advisor puts only a chunk's text in the prompt, so without this the model
 * never sees a document id and cannot cite one. Everything but search is passed straight through, and the
 * documents keep their id, metadata and score, so the sources the UI shows are unchanged.
 */
class DocumentLabelingVectorStore implements VectorStore {

    private final VectorStore delegate;

    DocumentLabelingVectorStore(VectorStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request).stream().map(DocumentLabelingVectorStore::label).toList();
    }

    static Document label(Document document) {
        Object documentId = document.getMetadata().get("document_id");
        if (documentId == null) {
            return document;
        }
        Object filename = document.getMetadata().get("filename");
        String label = "[document " + documentId + (filename == null ? "" : ": " + filename) + "]\n";
        return Document.builder()
                .id(document.getId())
                .text(label + document.getText())
                .metadata(document.getMetadata())
                .score(document.getScore())
                .build();
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }
}

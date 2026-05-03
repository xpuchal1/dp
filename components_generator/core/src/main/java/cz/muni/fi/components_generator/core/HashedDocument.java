package cz.muni.fi.components_generator.core;

import org.openprovenance.prov.model.Document;

class HashedDocument {
    private final Document document;
    private final String hash;

    public HashedDocument(Document document, String hash) {
        this.document = document;
        this.hash = hash;
    }

    public Document getDocument() {
        return document;
    }

    public String getHash() {
        return hash;
    }
}

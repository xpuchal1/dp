package document_generator.Models;

import org.openprovenance.prov.model.Document;

public class HashedDocument {
    public final Document document;
    public final String hash;

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

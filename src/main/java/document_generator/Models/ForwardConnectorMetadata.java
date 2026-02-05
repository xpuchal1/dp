package document_generator.Models;

import cz.muni.fi.cpm.template.schema.HashAlgorithms;
import org.openprovenance.prov.model.QualifiedName;

import java.util.ArrayList;
import java.util.List;

public class ForwardConnectorMetadata {
    private final QualifiedName connectorId;
    private final QualifiedName referenceBundleId;
    private final QualifiedName referenceMetaBundleId;
    private final String referenceBundleHash;
    private final HashAlgorithms referenceBundleHashAlgorithm;
    private final List<QualifiedName> generatedEntityIds;

    public ForwardConnectorMetadata(QualifiedName connectorId, QualifiedName referenceBundleId, QualifiedName referenceMetaBundleId, String hash, HashAlgorithms referenceBundleHashAlgorithm) {
        this.connectorId = connectorId;
        this.referenceBundleId = referenceBundleId;
        this.referenceMetaBundleId = referenceMetaBundleId;
        this.referenceBundleHash = hash;
        this.referenceBundleHashAlgorithm = referenceBundleHashAlgorithm;
        this.generatedEntityIds = new ArrayList<>();
    }

    public QualifiedName getConnectorId() {
        return connectorId;
    }

    public QualifiedName getReferenceBundleId() {
        return referenceBundleId;
    }

    public QualifiedName getReferenceMetaBundleId() {
        return referenceMetaBundleId;
    }

    public String getReferenceBundleHash() {
        return referenceBundleHash;
    }

    public HashAlgorithms getReferenceBundleHashAlgorithm() {
        return referenceBundleHashAlgorithm;
    }

    public List<QualifiedName> getGeneratedEntityIds() {
        return generatedEntityIds;
    }

}

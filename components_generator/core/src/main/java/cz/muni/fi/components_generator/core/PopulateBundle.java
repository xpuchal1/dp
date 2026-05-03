package cz.muni.fi.components_generator.core;

import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import org.openprovenance.prov.model.*;

import java.util.ArrayList;
import java.util.UUID;

class PopulateBundle {
    public static void Execute(
        String bundlePath,
        String storageUrlBase,
        String orgId,
        String keyPath,
        String bundleId,
        String connectorId,
        int forwardDistance,
        int backwardDistance,
        int entityCount,
        String type,
        String outputFolder,
        boolean createGraph
    ) {
        if (bundlePath == null && storageUrlBase == null) {
            throw new RuntimeException("Storage url base must be set if bundle path is null");
        }

        if (storageUrlBase != null && (keyPath == null || bundleId == null || orgId == null)) {
            throw new RuntimeException("Key path and bundle id are required if storage url base is set");
        }

        var pF = new org.openprovenance.prov.vanilla.ProvFactory();
        var cPF = new CpmProvFactory(pF);
        var cpmFactory = new CpmOrderedFactory();

        Document document;
        if (bundlePath != null) {
            CustomSerializer serializer = new CustomSerializer();
            document = serializer.readDocument(bundlePath);
        } else {
            document = ProvenanceStorageClient.getDocument(storageUrlBase, orgId, bundleId).getDocument();
        }
        var cpmDocument = new CpmDocument(document, pF, cPF, cpmFactory);
        if (bundleId == null) {
            bundleId = cpmDocument.getBundleId().getLocalPart();
        }

        var connectorOptional = cpmDocument.getForwardConnectors()
            .stream()
            .filter(fc -> fc.getId().getLocalPart().equals(connectorId))
            .findFirst();
        if (connectorOptional.isEmpty()) {
            System.err.printf("No connector found for id: %s%n", connectorId);
            return;
        }

        // Get backward connectors from which forward connector was derived
        var derivation = connectorOptional.get()
            .getEffectEdges()
            .stream()
            .filter(k -> k.getKind() == StatementOrBundle.Kind.PROV_DERIVATION)
            .map(k -> k.getCause().getElements().getFirst())
            .toList();

        var lastDerivedEntityId = connectorOptional.get().getId();
        var statements = new ArrayList<Statement>();

        // Make sure the distance to dc is generated correctly if
        forwardDistance = forwardDistance == 0 ? (int) (Math.random() * entityCount - backwardDistance) : forwardDistance;
        // backwardDistance = backwardDistance == 0 ? (int) (Math.random() * entityCount - forwardDistance) : backwardDistance;
        if (derivation.isEmpty()) {
            backwardDistance = 0;
        } else if (backwardDistance == 0) {
            backwardDistance = (int) (Math.random() * entityCount - forwardDistance);
        }

        for (int i = 0; i < entityCount; i++) {
            var nextEntity = pF.newEntity(CpmQualifiedName(connectorId + "-entity-" + i, pF));
            statements.add(nextEntity);
            if (i <= forwardDistance + backwardDistance) {
                if (i == 0) {
                    var specialization = pF.newSpecializationOf(nextEntity.getId(), lastDerivedEntityId);
                    statements.add(specialization);
                } else {
                    var wasDerivedFrom = pF.newWasDerivedFrom(lastDerivedEntityId, nextEntity.getId());
                    statements.add(wasDerivedFrom);
                }
                lastDerivedEntityId = nextEntity.getId();
            }
            if (i == forwardDistance) {
                var valueId = UUID.randomUUID();
                var value = pF.newOther(CpmQualifiedName(type + "Id", pF), valueId, XsdQualifiedName("string", pF));
                pF.addType(nextEntity, CpmQualifiedName(type, pF));
                pF.addAttribute(nextEntity, value);
                lastDerivedEntityId = nextEntity.getId();
            }

            if (i == forwardDistance + backwardDistance) {
                for (var d : derivation) {
                    var specialization = pF.newSpecializationOf(nextEntity.getId(), d.getId());
                    statements.add(specialization);
                }
            }
        }

        var doc = cpmDocument.toDocument();
        var bundle = (Bundle) doc.getStatementOrBundle().getFirst();
        bundle.getStatement().addAll(statements);
        var fullBundleId = bundle.getId();

        var originalLocalPartPrefix = fullBundleId.getLocalPart().split("-v")[0];
        var newId = pF.newQualifiedName(
            fullBundleId.getNamespaceURI(),
            originalLocalPartPrefix + "-v" + System.currentTimeMillis(),
            fullBundleId.getPrefix()
        );
        bundle.setId(newId);

        if (storageUrlBase != null) {
            ProvenanceStorageClient.storeDocument(
                storageUrlBase,
                doc,
                bundleId,
                orgId,
                keyPath,
                true
            );
        }

        if (outputFolder != null) {
            var path = outputFolder + newId.getLocalPart();
            DocumentGenerator.exportDocument(doc, path, createGraph);
        }
    }

    private static QualifiedName CpmQualifiedName(String name, org.openprovenance.prov.model.ProvFactory pf) {
        return pf.newQualifiedName("https://www.commonprovenancemodel.org/cpm-namespace-v1-0/", name, "cpm");
    }

    private static QualifiedName XsdQualifiedName(String name, org.openprovenance.prov.model.ProvFactory pf) {
        return pf.newQualifiedName("http://www.w3.org/2001/XMLSchema#", name, "xsd");
    }
}

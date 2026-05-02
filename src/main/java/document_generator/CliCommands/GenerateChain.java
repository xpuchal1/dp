package document_generator.CliCommands;

import cz.muni.fi.cpm.constants.CpmAttribute;
import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.template.mapper.TemplateProvMapper;
import cz.muni.fi.cpm.template.schema.ForwardConnector;
import cz.muni.fi.cpm.template.schema.HashAlgorithms;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import document_generator.DocumentGenerator;
import document_generator.Models.ForwardConnectorMetadata;
import document_generator.Models.ProvenanceStorageResponse;
import document_generator.ProvenanceStorageClient;
import org.openprovenance.prov.model.Bundle;
import org.openprovenance.prov.model.Document;
import org.openprovenance.prov.model.QualifiedName;
import org.openprovenance.prov.model.Statement;
import org.openprovenance.prov.vanilla.ProvFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


@Command(name = "generate-chain", description = "Generates a CPM provenance chain")
public class GenerateChain implements Runnable {
    @Spec
    Model.CommandSpec spec;

    int provenanceChainLength;
    int branching;

    @Option(names = {"-o", "--bundle-name"}, required = true, description = "Bundle name prefix")
    String bundleNameBase;

    @Option(names = {"-n", "--length"}, required = true, description = "length of the provenance chain")
    public void setProvenanceChainLength(int value) {
        if (value <= 0) {
            throw new ParameterException(spec.commandLine(), "provenanceChainLength must be greater than 0");
        }
        provenanceChainLength = value;
    }

    @Option(names = {"-b", "--branching"}, required = true, description = "number of forward connectors in the first bundle")
    public void setBranching(int value) {
        if (value < 0) {
            throw new ParameterException(spec.commandLine(), "branching must be greater than 0");
        }
        branching = value;
    }

    @Option(names = {"-O", "--organization-id",}, required = true)
    String organizationId;

    @Option(names = {"-s", "--storage-base-url"}, description = "base url of prov storage")
    String storageUrlBase;

    @Option(names = {"-k", "--key-path",})
    String keyPath;

    @Option(names = {"-d", "--directory"}, description = "bundles output directory")
    String outputFolder;

    @Option(names = {"-g", "--create-graph"}, description = "Creates a graph representation of the bundle. Will be ignored is directory is not set. Requires graphviz to work.")
    Boolean createGraph;

    String storageUrlBaseInternal = "http://prov-storage-hospital:8000/";

    @Override
    public void run() {
        if (storageUrlBase == null && outputFolder == null) {
            throw new ParameterException(spec.commandLine(), "Storage url base or output folder must be set");
        }

        if (storageUrlBase != null && keyPath == null) {
            throw new ParameterException(spec.commandLine(), "Key path must be set if storage url base is set");
        }

        var metaPrefix = "meta";
        var metaUrl = storageUrlBaseInternal + "api/v1/documents/meta/";

        var pF = new ProvFactory();
        var cPF = new CpmProvFactory(pF);
        var templateProvMapper = new TemplateProvMapper(cPF);

        // Mapping of backward connector and forward connector derived from it
        var connectorDerivationMapping = new HashMap<QualifiedName, List<QualifiedName>>();

        var previousConnectors = new ArrayList<ForwardConnectorMetadata>();
        var redundantConnectors = new ArrayList<ForwardConnectorMetadata>();

        var bundles = new HashMap<QualifiedName, CpmDocument>();

        for (int i = 0; i < provenanceChainLength; i++) {
            System.out.println("Starting index: " + i);
            var documentGenerator = new DocumentGenerator(storageUrlBaseInternal, organizationId);
            var doc = documentGenerator.createDocument(
                bundleNameBase + i,
                i == 0 ? branching : 1,
                previousConnectors,
                redundantConnectors,
                connectorDerivationMapping
            );
            bundles.put(doc.getBundleId(), doc);
            redundantConnectors.addAll(previousConnectors);

            if (outputFolder != null) {
                var path = outputFolder + doc.getBundleId().getLocalPart();
                DocumentGenerator.exportDocument(doc.toDocument(), path, createGraph);
            }

            ProvenanceStorageResponse savedDoc = null;
            if (storageUrlBase != null) {
                savedDoc = ProvenanceStorageClient.storeDocument(
                    storageUrlBase,
                    doc.toDocument(),
                    doc.getBundleId().getLocalPart(),
                    organizationId,
                    keyPath,
                    false
                );
            }

            // Add specialized forward connectors to referenced bundle(s)
            var previousConnectorsIds = previousConnectors.stream().map(ForwardConnectorMetadata::getConnectorId).toList();
            var nonRedundantBcs = doc.getBackwardConnectors().stream()
                .filter(bc -> previousConnectorsIds.contains(bc.getId()))
                .toList();

            for (var bc : nonRedundantBcs) {
                var originalId = (QualifiedName) bc.getElements().getFirst().getOther()
                    .stream()
                    .filter(o -> o.getElementName().getLocalPart().equals(CpmAttribute.REFERENCED_BUNDLE_ID.toString()))
                    .findFirst().get().getValue();
                var referencedBundleId = bundles.get(originalId).getBundleId();

                var cpmDocument = bundles.get(originalId);
                var referencedBundle = documentGenerator.addSpecializedForwardConnector(
                    cpmDocument,
                    bc,
                    organizationId,
                    doc.getBundleId(),
                    pF.newQualifiedName(metaUrl, doc.getBundleId().getLocalPart() + "_meta", metaPrefix),
                    // TODO: Calculate instead of using return value
                    savedDoc != null ? savedDoc.getToken().getData().getDocumentDigest() : "DUMMY"
                );

                if (outputFolder != null) {
                    var path = outputFolder + originalId.getLocalPart();
                    DocumentGenerator.exportDocument(referencedBundle, path, createGraph);
                }

                if (storageUrlBase != null) {
                    ProvenanceStorageClient.storeDocument(
                        storageUrlBase,
                        referencedBundle,
                        referencedBundleId.getLocalPart(),
                        organizationId,
                        keyPath,
                        true
                    );
                }
                bundles.put(originalId, new CpmDocument(referencedBundle, pF, cPF, new CpmOrderedFactory()));
            }

            if (!previousConnectors.isEmpty() && !doc.getForwardConnectors().isEmpty()) {
                for (var fc : doc.getForwardConnectors()) {
                    for (var bc : previousConnectors) {
                        if (connectorDerivationMapping.containsKey(fc.getId())) {
                            connectorDerivationMapping.get(fc.getId()).add(bc.getConnectorId());
                        } else {
                            var list = new ArrayList<QualifiedName>();
                            list.add(bc.getConnectorId());
                            connectorDerivationMapping.put(fc.getId(), list);
                        }
                    }
                }
            }
            previousConnectors.clear();

            for (var fc : doc.getForwardConnectors()) {
                previousConnectors.add(
                    new ForwardConnectorMetadata(
                        fc.getId(),
                        doc.getBundleId(),
                        pF.newQualifiedName(metaUrl, doc.getBundleId().getLocalPart() + "_meta", metaPrefix),
                        // TODO: Calculate instead of using return value
                        savedDoc != null ? savedDoc.getToken().getData().getDocumentDigest() : "DUMMY",
                        HashAlgorithms.SHA256
                    )
                );
            }
        }
        redundantConnectors.addAll(previousConnectors);

        System.out.println("Finished creating base documents.");
        System.out.println("------");

        // Add redundant forward connectors
        var reverseConnectorDerivation = reverseMapping(connectorDerivationMapping);
        for (var entry : bundles.entrySet()) {
            var bundleId = entry.getKey();
            var cpmDocument = entry.getValue();

            var statements = new ArrayList<Statement>();
            var created = new HashSet<QualifiedName>();
            cpmDocument.getForwardConnectors().forEach(fc -> {
                var connectedFc = fc.getId();

                while (reverseConnectorDerivation.containsKey(connectedFc)
                    && !created.contains(reverseConnectorDerivation.get(connectedFc))) {
                    var nextId = reverseConnectorDerivation.get(connectedFc);

                    // Skip creation of redundant fc pointing to subsequent component
                    if (connectedFc.equals(fc.getId())) {
                        if (reverseConnectorDerivation.containsKey(nextId)) {
                            nextId = reverseConnectorDerivation.get(nextId);
                        } else {
                            break;
                        }
                    }

                    if (!created.contains(nextId)) {
                        QualifiedName finalNextId = nextId;
                        var metadataOptional = redundantConnectors
                            .stream()
                            .filter(c -> c.getConnectorId().equals(finalNextId))
                            .findFirst();
                        if (metadataOptional.isEmpty()) {
                            throw new RuntimeException("Could not find redundant connector for " + nextId);
                        }

                        var metadata = metadataOptional.get();
                        var redundantFc = new ForwardConnector();
                        redundantFc.setId(nextId);
                        if (bundles.containsKey(metadata.getReferenceBundleId())) {
                            var updatedId = bundles.get(metadata.getReferenceBundleId()).getBundleId();
                            redundantFc.setReferencedBundleId(updatedId);
                        } else {
                            redundantFc.setReferencedBundleId(metadata.getReferenceBundleId());
                        }
                        redundantFc.setReferencedBundleId(metadata.getReferenceBundleId());
                        redundantFc.setReferencedMetaBundleId(metadata.getReferenceMetaBundleId());
                        redundantFc.setReferencedBundleHashValue(metadata.getReferenceBundleHash());
                        redundantFc.setHashAlg(HashAlgorithms.SHA256);
                        statements.addAll(templateProvMapper.map(redundantFc));
                    }
                    var wasDerivedFrom = pF.newWasDerivedFrom(nextId, connectedFc);
                    statements.add(wasDerivedFrom);

                    created.add(nextId);
                    connectedFc = nextId;
                }
            });

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
                    bundleId.getLocalPart(),
                    organizationId,
                    keyPath,
                    true
                );
            }

            if (outputFolder != null) {
                var path = outputFolder + entry.getKey().getLocalPart();
                DocumentGenerator.exportDocument(doc, path, createGraph);
            }
            bundles.put(entry.getKey(), new CpmDocument(doc, pF, cPF, new CpmOrderedFactory()));
        }

        bundles.forEach((k, v) -> {
            System.out.println("The most recent bundle id for " + k.getLocalPart() + " is " + v.getBundleId().getLocalPart());
        });
    }

    private HashMap<QualifiedName, QualifiedName> reverseMapping(HashMap<QualifiedName, List<QualifiedName>> map) {
        var result = new HashMap<QualifiedName, QualifiedName>();
        for (var entry : map.entrySet()) {
            entry.getValue().forEach(q -> result.put(q, entry.getKey()));
        }

        return result;
    }
}

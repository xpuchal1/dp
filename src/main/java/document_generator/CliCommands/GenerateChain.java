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
import document_generator.ProvenanceStorageClient;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.Bundle;
import org.openprovenance.prov.model.QualifiedName;
import org.openprovenance.prov.model.Statement;
import org.openprovenance.prov.model.interop.Formats;
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

    // Prefix of bundle id
    @Option(names = {"-s", "--storage-base-url"}, required = true, defaultValue = "http://prov-storage-hospital:8000/", description = "base url of prov storage")
    String storageUrlBase;

    @Option(names = {"-o", "--bundle-name"}, required = true, defaultValue = "test-ah", description = "base of the bundle name")
    String bundleNameBase;

    @Option(names = {"-C", "--directory"}, required = true, description = "bundles output directory")
    String outputFolder;

    @Option(names = {"-n", "--length"}, required = true, description = "length of the provenance chain (highest number of consecutive main activities)")
    public void setProvenanceChainLength(int value) {
        if (value <= 0) {
            throw new ParameterException(spec.commandLine(), "provenanceChainLength must be greater than 0");
        }
        provenanceChainLength = value;
    }


    @Option(names = {"-b", "--branching"}, required = true, description = "sum of all (main activities inputs- 1)")
    public void setBranching(int value) {
        if (value < 0) {
            throw new ParameterException(spec.commandLine(), "branching must be greater than 0");
        }
        branching = value;
    }

    private static final String orgId = "XXXXXXXX";

    @Override
    public void run() {
        var firstBundleOutputs = branching - provenanceChainLength + 2;

        var metaPrefix = "meta";
        var metaUrl = storageUrlBase + "api/v1/documents/meta/";

        var pF = new org.openprovenance.prov.vanilla.ProvFactory();
        var cPF = new CpmProvFactory(pF);
        var templateProvMapper = new TemplateProvMapper(cPF);

        // Mapping of backward connector and forward connector derived from it
        var connectorDerivationMapping = new HashMap<QualifiedName, List<QualifiedName>>();

        var previousConnectors = new ArrayList<ForwardConnectorMetadata>();
        var redundantConnectors = new ArrayList<ForwardConnectorMetadata>();

        // Mapping of original names (on creation) to updated names
        var updatedBundleIds = new HashMap<QualifiedName, QualifiedName>();

        for (int i = 0; i < provenanceChainLength; i++) {
            System.out.println("Starting index: " + i);
            var documentGenerator = new DocumentGenerator(storageUrlBase, orgId);
            var doc = documentGenerator.createDocument(
                bundleNameBase + i,
                i == 0 ? branching : 1,
                previousConnectors,
                redundantConnectors,
                connectorDerivationMapping
            );
            redundantConnectors.addAll(previousConnectors);

            if (outputFolder != null) {
                InteropFramework interop = new InteropFramework();
                var path = outputFolder + doc.getBundleId().getLocalPart();
                interop.writeDocument(path + ".json", doc.toDocument(), Formats.ProvFormat.JSON);
                interop.writeDocument(path + ".svg", doc.toDocument());
                System.out.println("Document: " + doc.getBundleId().getLocalPart() + " saved to " + path);
            }

            var savedDoc = ProvenanceStorageClient.storeDocument(doc.toDocument(), doc.getBundleId().getLocalPart(), orgId, false);

            // Add specialized forward connectors to referenced bundle(s)
            var previousConnectorsIds = previousConnectors.stream().map(ForwardConnectorMetadata::getConnectorId).toList();
            var nonRedundantBcs = doc.getBackwardConnectors().stream()
                .filter(bc -> previousConnectorsIds.contains(bc.getId()))
                .toList();

            for (var bc : nonRedundantBcs) {
                var referencedBundleId = (QualifiedName) bc.getElements().getFirst().getOther()
                    .stream()
                    .filter(o -> o.getElementName().getLocalPart().equals(CpmAttribute.REFERENCED_BUNDLE_ID.toString()))
                    .findFirst().get().getValue();
                var originalId = referencedBundleId;
                if (updatedBundleIds.containsKey(referencedBundleId)) {
                    referencedBundleId = updatedBundleIds.get(referencedBundleId);
                }

                var referencedBundle = documentGenerator.addSpecializedForwardConnector(
                    bc,
                    orgId,
                    referencedBundleId,
                    doc.getBundleId(),
                    pF.newQualifiedName(metaUrl, doc.getBundleId().getLocalPart() + "_meta", metaPrefix),
                    savedDoc.getToken().getData().getDocumentDigest()
                );

                if (outputFolder != null) {
                    InteropFramework interop = new InteropFramework();
                    var path = outputFolder + originalId.getLocalPart();
                    interop.writeDocument(path + ".json", referencedBundle, Formats.ProvFormat.JSON);
                    interop.writeDocument(path + ".svg", referencedBundle);
                    System.out.println("Document: " + originalId.getLocalPart() + " saved to " + path);
                }

                ProvenanceStorageClient.storeDocument(referencedBundle, referencedBundleId.getLocalPart(), orgId, true);
                var bundle = (Bundle) (referencedBundle.getStatementOrBundle().getFirst());

                updatedBundleIds.put(originalId, bundle.getId());
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
                        savedDoc.getToken().getData().getDocumentDigest(),
                        HashAlgorithms.SHA256
                    )
                );
            }
        }

        // Add redundant forward connectors
        var reverseConnectorDerivation = reverseMapping(connectorDerivationMapping);
        for (var entry : updatedBundleIds.entrySet()) {
            var bundleId = entry.getValue();
            var document = ProvenanceStorageClient.getDocument(orgId, bundleId.getLocalPart()).getDocument();
            var cpmDocument = new CpmDocument(document, pF, cPF, new CpmOrderedFactory());

            var statements = new ArrayList<Statement>();
            var processed = new HashSet<QualifiedName>();
            cpmDocument.getForwardConnectors().forEach(fc -> {
                var connectedFc = fc.getId();
                while (reverseConnectorDerivation.containsKey(connectedFc) && !processed.contains(connectedFc)) {
                    if (!processed.contains(reverseConnectorDerivation.get(connectedFc))) {
                        var redundantFc = new ForwardConnector();
                        redundantFc.setId(reverseConnectorDerivation.get(connectedFc));
                        statements.addAll(templateProvMapper.map(redundantFc));
                    }
                    var wasDerivedFrom = pF.newWasDerivedFrom(reverseConnectorDerivation.get(connectedFc), connectedFc);
                    statements.add(wasDerivedFrom);

                    processed.add(connectedFc);
                    connectedFc = reverseConnectorDerivation.get(connectedFc);
                }
            });

            var doc = cpmDocument.toDocument();
            var bundle = (Bundle) doc.getStatementOrBundle().getFirst();
            bundle.getStatement().addAll(statements);
            var fullBundleId = bundle.getId();

            var originalLocalPartPrefix = fullBundleId.getLocalPart().split("-v")[0];
            bundle.setId(pF.newQualifiedName(
                fullBundleId.getNamespaceURI(),
                originalLocalPartPrefix + "-v" + System.currentTimeMillis(),
                fullBundleId.getPrefix())
            );

            ProvenanceStorageClient.storeDocument(doc, bundleId.getLocalPart(), orgId, true);
            InteropFramework interop = new InteropFramework();
            var path = "src/main/resources/output/" + entry.getKey().getLocalPart();
            interop.writeDocument(path + ".json", doc);
            interop.writeDocument(path + ".svg", doc);
        }
    }

    private HashMap<QualifiedName, QualifiedName> reverseMapping(HashMap<QualifiedName, List<QualifiedName>> map) {
        var result = new HashMap<QualifiedName, QualifiedName>();
        for (var entry : map.entrySet()) {
            entry.getValue().forEach(q -> result.put(q, entry.getKey()));
        }

        return result;
    }
}


// vystup - sample, WSI, hocico really, je spec forward connector
// ta ista entita, moze mat iny nazov, ale musi mat rovnaky typ/id, je spec back connectoru v dalsom docu,


// TODO: One command to populate domain specific part with type entity type and number of entities
// TODO: Transform to PROV-DM chain - PROV-DM forward connector - nie je z nich nic derivovane, backward connector - nederivuje z nicoho
// Musi existovat derivation path z vystupu do vstupu
// Branching m - je minimalne m/2 entit, dlzka cesty z inputu do output

// TODO: Update document
// Musi to mat rovnake id, ale nazov bundlu musi byt iny


//    @Option(names = {"-m", "--graph-size"}, required = true, description = "number of graph nodes in each bundles")
//    public void setGraphSize(int value) {
//        if (value < 0) {
//            throw new ParameterException(spec.commandLine(), "graphSize must be greater or equal to 0");
//        }
//        graphSize = value;
//    }
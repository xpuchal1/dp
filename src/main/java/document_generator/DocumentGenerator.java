package document_generator;

import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.model.ICpmProvFactory;
import cz.muni.fi.cpm.model.INode;
import cz.muni.fi.cpm.template.mapper.TemplateProvMapper;
import cz.muni.fi.cpm.template.schema.*;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import document_generator.Models.ForwardConnectorMetadata;
import org.openprovenance.prov.model.Bundle;
import org.openprovenance.prov.model.Document;
import org.openprovenance.prov.model.ProvFactory;
import org.openprovenance.prov.model.QualifiedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DocumentGenerator {
    private final String CpmNamespaceUrl = "https://www.commonprovenancemodel.org/cpm-namespace-v1-0/";
    private final String CpmPrefix = "cpm";
    private final String MetaUrl;
    private final String MetaPrefix = "meta";
    private final String StorageUrl;
    private final String StoragePrefix = "storage";

    private final ProvFactory pF;
    private final ICpmProvFactory cPF;
    private final TemplateProvMapper templateProvMapper;

    public DocumentGenerator(String storageUrlBase, String orgId) {
        MetaUrl = storageUrlBase + "api/v1/documents/meta/";
        StorageUrl = storageUrlBase + "api/v1/organizations/" + orgId + "/documents/";

        pF = new org.openprovenance.prov.vanilla.ProvFactory();
        cPF = new CpmProvFactory(pF);
        templateProvMapper = new TemplateProvMapper(cPF);
    }

    public CpmDocument createDocument(
        String bundleName,
        int outputCount,
        List<ForwardConnectorMetadata> bcs,
        List<ForwardConnectorMetadata> rbcs,
        Map<QualifiedName, List<QualifiedName>> mappings
    ) {
        TraversalInformation ti = new TraversalInformation();
        ti.setPrefixes(Map.of(CpmPrefix, CpmNamespaceUrl, StoragePrefix, StorageUrl, MetaPrefix, MetaUrl));
        ti.setBundleName(pF.newQualifiedName(StorageUrl, bundleName, StoragePrefix));

        if (outputCount <= 0) {
            throw new IllegalArgumentException("outputCount must be greater than 0");
        }

        // Generate forward connectors
        var forwardConnectors = new ArrayList<ForwardConnector>();
        for (int i = 0; i < outputCount; i++) {
            var localPart = outputCount != 1 ? bundleName + "-connector-" + i : bundleName + "-connector";
            QualifiedName fcID = pF.newQualifiedName(CpmNamespaceUrl, localPart, CpmPrefix);
            var forwardConnector = new ForwardConnector(fcID);
            forwardConnectors.add(forwardConnector);
        }
        ti.setForwardConnectors(forwardConnectors);

        // Generate backward connectors
        var backwardConnectors = bcs.stream().map(data -> {
            // TODO: Correct sender agent
            var referenceBundleId = data.getReferenceBundleId();
            var bcAgent = new SenderAgent(pF.newQualifiedName(referenceBundleId.getNamespaceURI(), "Agent" + referenceBundleId.getLocalPart(), referenceBundleId.getPrefix()));
            ti.getSenderAgents().add(bcAgent);

            var previousBundleFc = data.getConnectorId();
            var bc = new BackwardConnector(previousBundleFc);
            bc.setAttributedTo(new ConnectorAttributed(bcAgent.getId()));
            for (var fc : forwardConnectors) {
                if (fc.getDerivedFrom() == null) {
                    fc.setDerivedFrom(new ArrayList<>());
                }
                fc.getDerivedFrom().add(previousBundleFc);
                data.getGeneratedEntityIds().add(previousBundleFc);
            }

            bc.setReferencedBundleId(data.getReferenceBundleId());
            bc.setReferencedMetaBundleId(data.getReferenceMetaBundleId());
            bc.setReferencedBundleHashValue(data.getReferenceBundleHash());
            bc.setHashAlg(data.getReferenceBundleHashAlgorithm());

            return bc;
        }).toList();
        var allConnectors = new ArrayList<>(backwardConnectors);
        rbcs.forEach(data -> {
            var referenceBundleId = data.getReferenceBundleId();
            var bcAgent = new SenderAgent(pF.newQualifiedName(referenceBundleId.getNamespaceURI(), "Agent" + referenceBundleId.getLocalPart(), referenceBundleId.getPrefix()));
            ti.getSenderAgents().add(bcAgent);

            var previousBundleFc = data.getConnectorId();
            var bc = new BackwardConnector(previousBundleFc);
            bc.setAttributedTo(new ConnectorAttributed(bcAgent.getId()));

            bc.setDerivedFrom(data.getGeneratedEntityIds());

            bc.setReferencedBundleId(data.getReferenceBundleId());
            bc.setReferencedMetaBundleId(data.getReferenceMetaBundleId());
            bc.setReferencedBundleHashValue(data.getReferenceBundleHash());
            bc.setHashAlg(data.getReferenceBundleHashAlgorithm());

            allConnectors.add(bc);
        });
        ti.setBackwardConnectors(allConnectors);
        mappings.forEach((key, values) -> {
            var optionalDerived = ti.getBackwardConnectors()
                .stream()
                .filter(bc -> bc.getId().equals(key))
                .findFirst();
            if (optionalDerived.isEmpty()) {
                System.err.println("No backward connector found for key: " + key + " in bundle: " + bundleName);
                return;
            }
            var derived = optionalDerived.get();
            var derivedFrom = derived.getDerivedFrom() == null ? new ArrayList<QualifiedName>() : derived.getDerivedFrom();
            derivedFrom.addAll(values);
            derived.setDerivedFrom(derivedFrom);
        });

        QualifiedName activityId = pF.newQualifiedName(CpmNamespaceUrl, "bundleActivity" + bundleName, CpmPrefix);
        var mainActivity = new MainActivity(activityId);
        ti.setMainActivity(mainActivity);
        mainActivity.setGenerated(forwardConnectors.stream().map(Connector::getId).toList());
        mainActivity.setUsed(backwardConnectors.stream().map(bc -> new MainActivityUsed(bc.getId())).toList());
        mainActivity.setReferencedMetaBundleId(pF.newQualifiedName(MetaUrl, bundleName + "_meta", MetaPrefix));

        var document = templateProvMapper.map(ti);

        // return document;
        return new CpmDocument(document, pF, cPF, new CpmOrderedFactory());
    }

    public Document addSpecializedForwardConnector(INode connector, String orgId, QualifiedName bundleId, QualifiedName referencedBundleId, QualifiedName metaId, String hash) {
        var storedDocument = ProvenanceStorageClient.getDocument(orgId, bundleId.getLocalPart());
        var cpmDocument = new CpmDocument(storedDocument.getDocument(), pF, cPF, new CpmOrderedFactory());

        var connectorIdLocal = connector.getId().getLocalPart();
        var fc = cpmDocument
            .getForwardConnectors()
            .stream()
            .filter(c -> c.getId().getLocalPart().equals(connectorIdLocal))
            .findFirst()
            .get();

        var spec_fc = new ForwardConnector();
        spec_fc.setId(pF.newQualifiedName(CpmNamespaceUrl, connectorIdLocal + "-spec-" + orgId, CpmPrefix));
        spec_fc.setReferencedBundleId(referencedBundleId);
        spec_fc.setReferencedMetaBundleId(metaId);
        spec_fc.setReferencedBundleHashValue(hash);
        spec_fc.setHashAlg(HashAlgorithms.SHA256);
        spec_fc.setSpecializationOf(fc.getId());

        var document = cpmDocument.toDocument();
        var bundle = ((Bundle) document.getStatementOrBundle().getFirst());
        bundle.getStatement().addAll(templateProvMapper.map(spec_fc));
        var originalLocalPartPrefix = bundleId.getLocalPart().split("-v")[0];
        bundle.setId(pF.newQualifiedName(bundleId.getNamespaceURI(), originalLocalPartPrefix + "-v" + System.currentTimeMillis(), bundleId.getPrefix()));

        return document;
    }
}

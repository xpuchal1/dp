package cz.muni.fi.components_generator.core;

import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.model.ICpmProvFactory;
import cz.muni.fi.cpm.model.INode;
import cz.muni.fi.cpm.template.mapper.TemplateProvMapper;
import cz.muni.fi.cpm.template.schema.*;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.Bundle;
import org.openprovenance.prov.model.Document;
import org.openprovenance.prov.model.ProvFactory;
import org.openprovenance.prov.model.QualifiedName;
import org.openprovenance.prov.model.interop.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ComponentGenerator {
    private final String CpmNamespaceUrl = "https://www.commonprovenancemodel.org/cpm-namespace-v1-0/";
    private final String CpmPrefix = "cpm";
    private final String MetaUrl;
    private final String MetaPrefix = "meta";
    private final String StorageUrl;
    private final String StoragePrefix = "storage";

    private final ProvFactory pF;
    private final ICpmProvFactory cPF;
    private final TemplateProvMapper templateProvMapper;

    public ComponentGenerator(String storageUrlBase, String orgId) {
        MetaUrl = storageUrlBase + "api/v1/documents/meta/";
        StorageUrl = storageUrlBase + "api/v1/organizations/" + orgId + "/documents/";

        pF = new org.openprovenance.prov.vanilla.ProvFactory();
        cPF = new CpmProvFactory(pF);
        templateProvMapper = new TemplateProvMapper(cPF);
    }

    public CpmDocument createBundle(
        String bundleName,
        int fcCount,
        List<ForwardConnectorMetadata> bcs,
        List<ForwardConnectorMetadata> rbcs,
        Map<QualifiedName, List<QualifiedName>> mappings
    ) {
        TraversalInformation ti = new TraversalInformation();
        ti.setPrefixes(Map.of(CpmPrefix, CpmNamespaceUrl, StoragePrefix, StorageUrl, MetaPrefix, MetaUrl));
        ti.setBundleName(pF.newQualifiedName(StorageUrl, bundleName, StoragePrefix));

        if (fcCount <= 0) {
            throw new IllegalArgumentException("outputCount must be greater than 0");
        }

        // Generate forward connectors
        var forwardConnectors = new ArrayList<ForwardConnector>();
        for (int i = 0; i < fcCount; i++) {
            var localPart = fcCount != 1 ? bundleName + "-connector-" + i : bundleName + "-connector";
            QualifiedName fcID = pF.newQualifiedName(CpmNamespaceUrl, localPart, CpmPrefix);
            var forwardConnector = new ForwardConnector(fcID);
            forwardConnectors.add(forwardConnector);
        }
        ti.setForwardConnectors(forwardConnectors);

        // Generate backward connectors
        var backwardConnectors = bcs.stream().map(data -> {
            var referenceBundleId = data.getReferenceBundleId();
            var bcAgentId = pF.newQualifiedName(
                referenceBundleId.getNamespaceURI(),
                "SenderAgent-" + referenceBundleId.getLocalPart(),
                referenceBundleId.getPrefix()
            );
            var agents = ti.getSenderAgents();
            if (agents.stream().filter(a -> a.getId().equals(bcAgentId)).findAny().isEmpty()) {
                var bcAgent = new SenderAgent(bcAgentId);
                agents.add(bcAgent);
            }

            var previousBundleFc = data.getConnectorId();
            var bc = new BackwardConnector(previousBundleFc);
            bc.setAttributedTo(new ConnectorAttributed(bcAgentId));
            for (var fc : forwardConnectors) {
                if (fc.getDerivedFrom() == null) {
                    fc.setDerivedFrom(new ArrayList<>());
                }
                fc.getDerivedFrom().add(previousBundleFc);
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

            var previousBundleFc = data.getConnectorId();
            var bc = new BackwardConnector(previousBundleFc);

            bc.setReferencedBundleId(data.getReferenceBundleId());
            bc.setReferencedMetaBundleId(data.getReferenceMetaBundleId());
            bc.setReferencedBundleHashValue(data.getReferenceBundleHash());
            bc.setHashAlg(data.getReferenceBundleHashAlgorithm());

            var agentId = pF.newQualifiedName(referenceBundleId.getNamespaceURI(), "SenderAgent-" + referenceBundleId.getLocalPart(), referenceBundleId.getPrefix());
            bc.setAttributedTo(new ConnectorAttributed(agentId));
            if (ti.getSenderAgents().stream().noneMatch(a -> a.getId().equals(agentId))) {
                var agent = new SenderAgent(agentId);
                ti.getSenderAgents().add(agent);
            }

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

        QualifiedName activityId = pF.newQualifiedName(CpmNamespaceUrl, "activity-" + bundleName, CpmPrefix);
        var mainActivity = new MainActivity(activityId);
        ti.setMainActivity(mainActivity);
        mainActivity.setGenerated(forwardConnectors.stream().map(Connector::getId).toList());
        mainActivity.setUsed(backwardConnectors.stream().map(bc -> new MainActivityUsed(bc.getId())).toList());
        mainActivity.setReferencedMetaBundleId(pF.newQualifiedName(MetaUrl, bundleName + "_meta", MetaPrefix));

        var document = templateProvMapper.map(ti);

        // return document;
        return new CpmDocument(document, pF, cPF, new CpmOrderedFactory());
    }

    public Document addSpecializedForwardConnector(CpmDocument cpmDocument, INode connector, QualifiedName referencedBundleId, QualifiedName metaId, String hash) {
        var bundleId = cpmDocument.getBundleId();
        var connectorIdLocal = connector.getId().getLocalPart();
        var fc = cpmDocument
            .getForwardConnectors()
            .stream()
            .filter(c -> c.getId().getLocalPart().equals(connectorIdLocal))
            .findFirst()
            .get();

        var spec_fc = new ForwardConnector();
        spec_fc.setId(pF.newQualifiedName(CpmNamespaceUrl, connectorIdLocal + "-spec", CpmPrefix));
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

    public static void exportDocument(Document document, String path, boolean createSvg) {
        InteropFramework interop = new InteropFramework();
        interop.writeDocument(path + ".json", document, Formats.ProvFormat.JSON);
        if (createSvg) {
            interop.writeDocument(path + ".svg", document);
        }

        var bundleId = ((Bundle) document.getStatementOrBundle().getFirst()).getId();
        System.out.println("Document: " + bundleId.getLocalPart() + " saved to " + path);
    }
}

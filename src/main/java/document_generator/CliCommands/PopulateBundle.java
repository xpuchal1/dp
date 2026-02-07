package document_generator.CliCommands;

import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import document_generator.ProvenanceStorageClient;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.ArrayList;
import java.util.UUID;

@Command(name = "populate-bundle", description = "Populates a CPM bundles with entities of specified type")
public class PopulateBundle implements Runnable {
    @Spec
    Model.CommandSpec spec;

    // Prefix of bundle id
    @Option(names = {"-s", "--storage-base-url"}, defaultValue = "http://prov-storage-hospital:8000/", description = "base url of prov storage")
    String storageUrlBase;

    @Option(names = {"-O", "--organization-id"}, defaultValue = "XXXXXXXX", description = "id of the organization")
    String orgId;

    @Option(names = {"-C", "--certificate-path",}, required = true)
    String certificatePath;

    @Option(names = {"-B", "--bundle-id"}, required = true, description = "id of the updated bundle")
    String bundleId;

    @Option(names = {"-c", "--connector-id"}, required = true, description = "id of derived forward connector")
    String connectorId;

    @Option(names = {"-f", "--forward-distance"}, defaultValue = "0", description = "distance from the forward connector")
    int forwardDistance;

    @Option(names = {"-b", "--backward-distance"}, defaultValue = "0", description = "distance from the backward connector")
    int backwardDistance;

    @Option(names = {"-e", "--entity-count"}, required = true, description = "number of new entities")
    public void setEntityCount(int value) {
        if (value <= 0) {
            throw new CommandLine.ParameterException(spec.commandLine(), "entity count must be greater than 0");
        }
        if (value <= forwardDistance + backwardDistance) {
            throw new CommandLine.ParameterException(spec.commandLine(), "entity count must be greater than sum of forward and backward distance");
        }
        entityCount = value;
    }

    int entityCount;

    @Option(names = {"-t", "--type"}, required = true, description = "type")
    String type;

    @Override
    public void run() {
        var pF = new org.openprovenance.prov.vanilla.ProvFactory();
        var cPF = new CpmProvFactory(pF);
        var cpmFactory = new CpmOrderedFactory();

        var document = ProvenanceStorageClient.getDocument(orgId, bundleId);
        var cpmDocument = new CpmDocument(document.getDocument(), pF, cPF, cpmFactory);

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
        bundle.setId(pF.newQualifiedName(
            fullBundleId.getNamespaceURI(),
            originalLocalPartPrefix + "-v" + System.currentTimeMillis(),
            fullBundleId.getPrefix())
        );

        ProvenanceStorageClient.storeDocument(
            doc,
            bundleId,
            orgId,
            certificatePath,
            true
        );
        InteropFramework interop = new InteropFramework();
        var path = "src/main/resources/output/fixed";
        interop.writeDocument(path + ".provn", doc);
        interop.writeDocument(path + ".svg", doc);
    }

    public QualifiedName CpmQualifiedName(String name, ProvFactory pf) {
        return pf.newQualifiedName("https://www.commonprovenancemodel.org/cpm-namespace-v1-0/", name, "cpm");
    }

    public QualifiedName XsdQualifiedName(String name, ProvFactory pf) {
        return pf.newQualifiedName("http://www.w3.org/2001/XMLSchema#", name, "xsd");
    }
}

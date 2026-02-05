package document_generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.muni.fi.cpm.constants.CpmAttribute;
import cz.muni.fi.cpm.constants.CpmType;
import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.model.CpmDocument;
import cz.muni.fi.cpm.model.CpmUtilities;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.*;
import org.openprovenance.prov.model.interop.Formats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Transformer {

    public final ProvFactory pF;
    public final CpmProvFactory cPF;
    public final CpmOrderedFactory cF;
    public final ProvUtilities u;
    private final InteropFramework interop;

    public Transformer() {
        this.pF = new org.openprovenance.prov.vanilla.ProvFactory();
        this.cPF = new CpmProvFactory(pF);
        this.cF = new CpmOrderedFactory(pF);
        this.u = new ProvUtilities();
        this.interop = new InteropFramework();
    }

    public Document transform(Document doc) {
        Bundle bun = (Bundle) doc.getStatementOrBundle().getFirst();
        List<Statement> toRemove = new ArrayList<>();

        bun.getStatement().stream()
            .filter(st -> st instanceof Entity)
            .map(st -> (Entity) st)
            .filter(e -> CpmUtilities.hasCpmType(e, CpmType.FORWARD_CONNECTOR) &&
                CpmUtilities.containsCpmAttribute(e, CpmAttribute.REFERENCED_BUNDLE_ID))
            .forEach(toRemove::add);

        bun.getStatement().stream()
            .filter(st -> st instanceof Entity)
            .map(st -> (Entity) st)
            .filter(e -> CpmUtilities.hasCpmType(e, CpmType.FORWARD_CONNECTOR))
//            .filter(e -> Dataset3Transformer.IDENTIFIED_SPECIES_CON.equals(e.getId().getLocalPart()))
            .findFirst().ifPresent(toRemove::add);

        List<QualifiedName> ids = toRemove.stream().map(e -> ((Identifiable) e).getId()).toList();

        bun.getStatement().stream()
            .filter(st -> st instanceof SpecializationOf || st instanceof WasDerivedFrom || st instanceof WasAttributedTo)
            .map(st -> (Relation) st)
            .filter(r -> ids.contains(u.getEffect(r)))
            .forEach(toRemove::add);

        bun.getStatement().stream()
            .filter(st -> st instanceof Agent)
            .map(st -> (Agent) st)
            .filter(e -> CpmUtilities.hasCpmType(e, CpmType.RECEIVER_AGENT))
            .forEach(e -> {
                if (CpmUtilities.hasCpmType(e, CpmType.SENDER_AGENT)) {
                    e.getType().removeIf(t -> t.getValue() instanceof QualifiedName qN &&
                        CpmType.RECEIVER_AGENT.toString().equals(qN.getLocalPart()));
                } else {
                    toRemove.add(e);
                }
            });

        bun.getStatement().removeAll(toRemove);
        CpmDocument cpmDoc = new CpmDocument(doc, pF, cPF, cF);

        // setReferenceAttributes(cpmDoc.getForwardConnectors());
        // setReferenceAttributes(cpmDoc.getBackwardConnectors());

        return cpmDoc.toDocument();
    }

    private Document addMissingStorageAndMetaNs(CpmDocument cpmDoc, String suffix) {
        String origBunId = cpmDoc.getBundleId().getLocalPart();
        cpmDoc.setBundleId(pF.newQualifiedName("storage", origBunId + suffix, "storage"));

        cpmDoc.getMainActivity().getElements().forEach(e -> {
                Activity mainActivity = (Activity) e;
                mainActivity.getOther().add(cPF.newCpmAttribute(CpmAttribute.REFERENCED_META_BUNDLE_ID,
                    pF.newQualifiedName("meta", origBunId + "_meta", "meta")));
            }
        );

        return cpmDoc.toDocument();
    }

    public String createProvStorageJson(Document doc) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        interop.writeDocument(outputStream, doc, Formats.ProvFormat.JSON);

        InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(inputStream);
        removeJsonKeyRecursive((ObjectNode) json, "@id");

        return json.toString();
    }

    public String getProvStorageDocumentHash(Document doc) {
        try {
            var jsonDocument = createProvStorageJson(doc);
            var base64doc = Base64.getEncoder().encodeToString(jsonDocument.getBytes());
            var docBytes = Base64.getDecoder().decode(base64doc);
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hashDecoded = md.digest(docBytes);
            return bytesToHex(hashDecoded);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public void removeJsonKeyRecursive(ObjectNode node, String keyToRemove) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();

            if (entry.getKey().equals(keyToRemove)) {
                fields.remove();
            } else if (entry.getValue().isObject()) {
                removeJsonKeyRecursive((ObjectNode) entry.getValue(), keyToRemove);
            } else if (entry.getValue().isArray()) {
                for (JsonNode element : entry.getValue()) {
                    if (element.isObject()) {
                        removeJsonKeyRecursive((ObjectNode) element, keyToRemove);
                    }
                }
            }
        }
    }

/*    private void setReferenceAttributes(List<INode> connectors) {
        connectors.forEach(n ->
            n.getElements().forEach(e -> {
                Entity con = (Entity) e;
                con.getOther().stream()
                    .filter(o -> CpmAttribute.REFERENCED_BUNDLE_ID.toString().equals(o.getElementName().getLocalPart()))
                    .findFirst().ifPresent(o -> {
                        QualifiedName val = (QualifiedName) o.getValue();
                        con.getOther().remove(o);
                        try {
                            pST.addFileReferenceToConnector(con, getFilePath(val.getLocalPart()), Formats.ProvFormat.JSON);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
            }));
    }*/
}

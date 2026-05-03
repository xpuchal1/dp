package cz.muni.fi.components_generator.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.*;
import org.openprovenance.prov.model.interop.Formats;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

class CustomSerializer {

    public final ProvFactory pF;
    private final InteropFramework interop;

    public CustomSerializer() {
        this.pF = new org.openprovenance.prov.vanilla.ProvFactory();
        this.interop = new InteropFramework();
    }

    public Document readDocument(String path) {
        try (InputStream inputStream = new FileInputStream(path)) {
            var document = interop.readDocument(inputStream, Formats.ProvFormat.JSON);
            RenameBundle(document);

            return document;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

    private void removeJsonKeyRecursive(ObjectNode node, String keyToRemove) {
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

    public static void AddIdToBundle(JsonNode document) {
        var bundle = document.get("bundle");
        var fields = bundle.fields();
        if (fields.hasNext()) {
            var bundleItem = fields.next();

            var key = bundleItem.getKey();
            var innerObj = (ObjectNode) bundle.get(key);
            innerObj.put("@id", key);
        }
    }

    public static void RenameBundle(Document document) {
        var bundle = (Bundle) document.getStatementOrBundle().getFirst();
        var ns = document.getNamespace();
        var namespaceUri = ns.lookupPrefix(bundle.getId().getPrefix());
        var pF = new org.openprovenance.prov.vanilla.ProvFactory();
        var updatedBundleId = pF.newQualifiedName(namespaceUri, bundle.getId().getLocalPart(), bundle.getId().getPrefix());
        bundle.setId(updatedBundleId);
    }

    public static String ProvStorageJsonHash(String documentJson)  {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(documentJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}

package document_generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import document_generator.Models.HashedDocument;
import document_generator.Models.ProvenanceStorageResponse;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.Document;
import org.openprovenance.prov.model.interop.Formats;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Base64;

public class ProvenanceStorageClient {
    private static final String baseUrl = "http://localhost:8001";

    public static ProvenanceStorageResponse storeDocument(Document document, String bundleId, String orgId, String certificatePath, boolean update) {
        try {
            var transformer = new Transformer();
            var documentJson = transformer.createProvStorageJson(document);

            ObjectMapper objectMapper = new ObjectMapper();

            var base64doc = Base64.getEncoder().encodeToString(documentJson.getBytes());

            var jsonBody = objectMapper.createObjectNode();
            jsonBody.put("document", base64doc);
            jsonBody.put("documentFormat", "json");
            jsonBody.put("signature", createSignature(documentJson, certificatePath));
            jsonBody.put("createdOn", Instant.now().getEpochSecond() - 60);

            try (HttpClient client = HttpClient.newHttpClient()) {
                var url = MessageFormat.format(
                    "{0}/api/v1/organizations/{1}/documents/{2}",
                    baseUrl,
                    orgId,
                    bundleId
                );

                var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json");
                if (update) {
                    requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody.toString()));
                } else {
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()));
                }
                var request = requestBuilder.build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException(response.body());
                }

                System.out.println("Status code: " + response.statusCode());
                System.out.println("Response body: " + response.body());

                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(response.body(), ProvenanceStorageResponse.class);
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static HashedDocument getDocument(String orgId, String bundleId) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var url = MessageFormat.format(
                "{0}/api/v1/organizations/{1}/documents/{2}",
                baseUrl,
                orgId,
                bundleId
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            var base64Doc = root.path("document").asText();
            var hash = root.path("token").path("data").path("documentDigest").asText();
            JsonNode documentJsonNode = mapper.readTree(Base64.getDecoder().decode(base64Doc));
            AddIdAndPrefixToBundle(documentJsonNode);

            var docJson = documentJsonNode.toString().replace("https://openprovenance.org/blank#", "https://openprovenance.org/blank");
            InputStream stream = new ByteArrayInputStream(docJson.getBytes(StandardCharsets.UTF_8));
            InteropFramework interop = new InteropFramework();

            var document = interop.readDocument(stream, Formats.ProvFormat.JSON);
            return new HashedDocument(document, hash);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }

    }

    private static void AddIdAndPrefixToBundle(JsonNode document) {
        var bundle = document.get("bundle");
        var fields = bundle.fields();
        if (fields.hasNext()) {
            var bundleItem = fields.next();

            var key = bundleItem.getKey();
            var innerObj = (ObjectNode) bundle.get(key);
            innerObj.put("@id", key);
            innerObj.put("prefix", document.get("prefix"));
        }
    }

    private static String createSignature(String json, String path) throws Exception {
        var privateKey = Certificates.loadPrivateKey(Path.of(path));

        Signature ecdsaSign = Signature.getInstance("SHA256withECDSA");
        ecdsaSign.initSign(privateKey);
        ecdsaSign.update(json.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = ecdsaSign.sign();

        return Base64.getEncoder().encodeToString(signatureBytes);
    }
}

package cz.muni.fi.components_generator.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class RegisterOrganisation {
    public static void Execute(
        String storageUrlBase,
        String[] intermediateCertificates,
        String clientCertificate,
        String lastIntermediateKey,
        String outputFolder,
        String organizationId
    ) {
        if (clientCertificate == null && outputFolder == null) {
            throw new RuntimeException("Client certificate must be set if base path is null");
        }
        if (clientCertificate == null && lastIntermediateKey == null) {
            throw new RuntimeException("Last intermediate key must be set if client certificate is null");
        }
        organizationId = organizationId == null ? UUID.randomUUID().toString().substring(0, 8) : organizationId;

        try {
            List<String> intermediateCertificatesList = new ArrayList<>();
            for (String intermediateCertificate : intermediateCertificates) {
                var intermediate = Files.readString(Path.of(intermediateCertificate));
                intermediateCertificatesList.add(intermediate);
            }

            String cert;
            if (clientCertificate == null) {
                var bundle = Certificates.generateCertificate(
                    "CZ",
                    organizationId,
                    Certificates.loadPrivateKey(Path.of(lastIntermediateKey)),
                    Certificates.loadCertificate(Path.of(intermediateCertificates[intermediateCertificates.length - 1])),
                    false,
                    null
                );

                var keyPath = Path.of(outputFolder + "keys/" + organizationId + ".key");
                Certificates.exportKey(bundle.getKey(), keyPath);
                System.out.println("Generated key saved as: " + keyPath);
                var certPath = Path.of(outputFolder + "certificates/" + organizationId + ".pem");
                Certificates.exportCert(bundle.getCert(), certPath);
                System.out.println("Generated certificate saved as: " + certPath);
                cert = Certificates.exportCertToString(bundle.getCert());
            } else {
                cert = Files.readString(Path.of(clientCertificate));
            }

            HttpClient client = HttpClient.newHttpClient();
            var url = MessageFormat.format(
                "{0}/api/v1/organizations/{1}",
                storageUrlBase,
                organizationId
            );

            ObjectMapper objectMapper = new ObjectMapper();
            var jsonBody = objectMapper.createObjectNode();
            jsonBody.put("clientCertificate", cert);
            var intermediate = jsonBody.putArray("intermediateCertificates");
            for (String intermediateCertificate : intermediateCertificatesList) {
                intermediate.add(intermediateCertificate);
            }

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(response.body());
            }

            System.out.println("Registered organisation: " + organizationId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

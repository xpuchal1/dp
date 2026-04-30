package document_generator.CliCommands;

import com.fasterxml.jackson.databind.ObjectMapper;
import document_generator.Certificates;
import picocli.CommandLine;

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

@CommandLine.Command(name = "register-org", description = "Creates a new organization")
public class RegisterOrganisation implements Runnable {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--storage-base-url"}, required = true, defaultValue = "http://localhost:8001", description = "base url of prov storage")
    String storageUrlBase;

    String[] intermediateCertificates;

    @CommandLine.Option(names = {"-i", "--intermediate-certificates"}, required = true, arity = "2..*", description = "base url of prov storage")
    public void setIntermediateCertificates(String[] values) {
        if (values.length < 2) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Please provide at least two intermediate certificates");
        }
        intermediateCertificates = values;
    }

    @CommandLine.Option(names = {"-c", "--client-certificate"},
        description = "certificate of the client org.\n" +
            "certificate will be created and signed by last intermediate certificate if not provided")
    String clientCertificate;

    @CommandLine.Option(names = {"-k", "--intermediate-key"}, description = "Signing key of the last intermediate certificate")
    String lastIntermediateKey;

    @CommandLine.Option(names = {"-d", "--directory"},
        description = "Base directory where created certificate will be exported.\n Must be set if client certificate is omitted.")
    String outputFolder;

    @CommandLine.Option(names = {"-O", "--organization-id"}, description = "id of the created organization")
    String organizationId;

    @Override
    public void run() {
        organizationId = organizationId == null ? UUID.randomUUID().toString().substring(0, 8) : organizationId;
        // Need to be checked here, setXXX validation only runs if the option is present
        if (clientCertificate == null && outputFolder == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Client certificate must be set if base path is null");
        }
        if (clientCertificate == null && lastIntermediateKey == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Last intermediate key must be set if client certificate is null");
        }

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



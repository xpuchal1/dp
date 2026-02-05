package document_generator.CliCommands;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@CommandLine.Command(name = "register-org", description = "Creates a new organization")
public class RegisterOrganisation implements Runnable {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--storage-base-url"}, required = true, defaultValue = "http://localhost:8001", description = "base url of prov storage")
    String storageUrlBase;

    @CommandLine.Option(names = {"-c", "--client-certificate"}, required = true, description = "base url of prov storage")
    String clientCertificate;

    @CommandLine.Option(names = {"-i", "--intermediate-certificates"}, required = true, arity = "2..*", description = "base url of prov storage")
    public void setIntermediateCertificates(String[] values) {
        if (values.length < 2) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Please provide at least two intermediate certificates");
        }
        intermediateCertificates = values;
    }

    String[] intermediateCertificates;

    @CommandLine.Option(names = {"-o", "--organization-id"}, description = "id of the created organization")
    String organizationId;

    @Override
    public void run() {
        organizationId = organizationId == null ? UUID.randomUUID().toString().substring(0, 8) : organizationId;
        String cert;
        List<String> intermediateCertificatesList = new ArrayList<>();
        try {
            cert = Files.readString(Path.of(clientCertificate));
            for (String intermediateCertificate : intermediateCertificates) {
                var intermediate = Files.readString(Path.of(intermediateCertificate));
                intermediateCertificatesList.add(intermediate);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (HttpClient client = HttpClient.newHttpClient()) {
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
            System.out.println(request.uri());

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(response.body());
            }

            System.out.println("Registered organisation: " + organizationId);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }
}



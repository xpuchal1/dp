package cz.muni.fi.components_generator.cli.CliCommands;

import cz.muni.fi.components_generator.core.Commands;
import picocli.CommandLine;

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
        // Need to be checked here, setXXX validation only runs if the option is present
        if (clientCertificate == null && outputFolder == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Client certificate must be set if base path is null");
        }
        if (clientCertificate == null && lastIntermediateKey == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Last intermediate key must be set if client certificate is null");
        }

        Commands.RegisterOrganisation(
            storageUrlBase,
            intermediateCertificates,
            clientCertificate,
            lastIntermediateKey,
            outputFolder,
            organizationId
        );
    }
}



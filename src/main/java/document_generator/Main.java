package document_generator;

import document_generator.CliCommands.GenerateCertificates;
import document_generator.CliCommands.GenerateChain;
import document_generator.CliCommands.PopulateBundle;
import document_generator.CliCommands.RegisterOrganisation;
import picocli.CommandLine;

@CommandLine.Command(
    name = "cpm-generator",
    description = "Main application",
    subcommands = {
        GenerateChain.class,
        PopulateBundle.class,
        RegisterOrganisation.class,
        GenerateCertificates.class,
    }
)
public class Main implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {

    }
}
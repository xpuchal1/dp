package cz.muni.fi.components_generator.cli;

import cz.muni.fi.components_generator.cli.CliCommands.*;
import picocli.CommandLine;

@CommandLine.Command(
    name = "cpm-generator",
    description = "Main application",
    subcommands = {
        GenerateChain.class,
        PopulateBundle.class,
        RegisterOrganisation.class,
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
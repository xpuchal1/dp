package document_generator;

import document_generator.CliCommands.GenerateChain;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
    name = "cpm-generator",
    description = "Main application",
    subcommands = {
        GenerateChain.class,
    }
)
public class App implements Runnable {

    @Override
    public void run() {
        System.out.println("MyApp: no subcommand supplied.");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }
}
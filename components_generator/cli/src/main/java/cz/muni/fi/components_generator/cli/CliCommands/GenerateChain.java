package cz.muni.fi.components_generator.cli.CliCommands;

import cz.muni.fi.components_generator.core.Commands;
import picocli.CommandLine.*;

@Command(name = "generate-chain", description = "Generates a CPM provenance chain")
public class GenerateChain implements Runnable {
    @Spec
    Model.CommandSpec spec;

    int provenanceChainLength;
    int branching;

    @Option(names = {"-o", "--bundle-name"}, required = true, description = "Bundle name prefix")
    String bundleNameBase;

    @Option(names = {"-l", "--length"}, required = true, description = "length of the provenance chain")
    public void setProvenanceChainLength(int value) {
        if (value <= 0) {
            throw new ParameterException(spec.commandLine(), "provenanceChainLength must be greater than 0");
        }
        provenanceChainLength = value;
    }

    @Option(names = {"-b", "--branching"}, required = true, description = "number of forward connectors in the first bundle")
    public void setBranching(int value) {
        if (value < 0) {
            throw new ParameterException(spec.commandLine(), "branching must be greater than 0");
        }
        branching = value;
    }

    @Option(names = {"-O", "--organization-id",}, required = true)
    String organizationId;

    @Option(names = {"-s", "--storage-base-url"}, description = "base url of prov storage")
    String storageUrlBase;

    @Option(names = {"-k", "--key-path",})
    String keyPath;

    @Option(names = {"-d", "--directory"}, description = "bundles output directory")
    String outputFolder;

    @Option(names = {"-g", "--create-graph"}, description = "Creates a graph representation of the bundle. Will be ignored is directory is not set. Requires graphviz to work.")
    boolean createGraph;

    String storageUrlBaseInternal = "http://prov-storage-hospital:8000/";

    @Override
    public void run() {
        if (storageUrlBase == null && outputFolder == null) {
            throw new ParameterException(spec.commandLine(), "Storage url base or output folder must be set");
        }

        if (storageUrlBase != null && keyPath == null) {
            throw new ParameterException(spec.commandLine(), "Key path must be set if storage url base is set");
        }

        Commands.GenerateChain(
            provenanceChainLength,
            branching,
            bundleNameBase,
            organizationId,
            storageUrlBase,
            keyPath,
            outputFolder,
            createGraph,
            storageUrlBaseInternal
        );
    }
}

package cz.muni.fi.components_generator.cli.CliCommands;

import cz.muni.fi.components_generator.core.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "populate-bundle", description = "Populates a CPM bundles with entities of specified type")
public class PopulateBundle implements Runnable {
    @Spec
    Model.CommandSpec spec;

    @Option(names = {"-p", "--bundle-path"}, description = "Path to the bundle stored on folder system.")
    String bundlePath;

    @Option(names = {"-s", "--storage-base-url"}, description = "base url of prov storage")
    String storageUrlBase;

    @Option(names = {"-O", "--organization-id"}, description = "id of the organization")
    String orgId;

    @Option(names = {"-k", "--key-path",})
    String keyPath;

    @Option(names = {"-B", "--bundle-id"}, description = "id of the updated bundle")
    String bundleId;

    @Option(names = {"-c", "--connector-id"}, required = true, description = "id of derived forward connector")
    String connectorId;

    @Option(names = {"-f", "--forward-distance"}, defaultValue = "0", description = "distance from the forward connector")
    public void setForwardDistance(int value) {
        if (value < 0) {
            throw new CommandLine.ParameterException(spec.commandLine(), "forward distance must be positive");
        }
        forwardDistance = value;
    }

    int forwardDistance;

    @Option(names = {"-b", "--backward-distance"}, defaultValue = "0", description = "distance from the backward connector")
    public void setBackwardDistance(int value) {
        if (value < 0) {
            throw new CommandLine.ParameterException(spec.commandLine(), "backward distance must be positive");
        }
        backwardDistance = value;
    }

    int backwardDistance;

    @Option(names = {"-e", "--entity-count"}, required = true, description = "number of new entities")
    public void setEntityCount(int value) {
        if (value <= 0) {
            throw new CommandLine.ParameterException(spec.commandLine(), "entity count must be greater than 0");
        }
        if (value <= forwardDistance + backwardDistance) {
            throw new CommandLine.ParameterException(spec.commandLine(), "entity count must be greater than sum of forward and backward distance");
        }
        entityCount = value;
    }

    int entityCount;

    @Option(names = {"-t", "--type"}, required = true, description = "type")
    String type;

    @Option(names = {"-d", "--directory"}, description = "bundles output directory")
    String outputFolder;

    @Option(names = {"-g", "--create-graph"}, description = "Creates a graph representation of the bundle. Will be ignored is directory is not set. Requires graphviz to work.")
    boolean createGraph;

    @Override
    public void run() {
        if (bundlePath == null && storageUrlBase == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Storage url base must be set if bundle path is null");
        }

        if (storageUrlBase != null && (keyPath == null || bundleId == null || orgId == null)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Key path and bundle id are required if storage url base is set");
        }

        Commands.PopulateBundle(
            bundlePath,
            storageUrlBase,
            orgId,
            keyPath,
            bundleId,
            connectorId,
            forwardDistance,
            backwardDistance,
            entityCount,
            type,
            outputFolder,
            createGraph
        );
    }
}

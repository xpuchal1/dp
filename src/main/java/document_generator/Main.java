package document_generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.muni.fi.cpm.template.deserialization.ITraversalInformationDeserializer;
import cz.muni.fi.cpm.template.deserialization.TraversalInformationDeserializer;
import cz.muni.fi.cpm.template.mapper.ITemplateProvMapper;
import cz.muni.fi.cpm.template.mapper.TemplateProvMapper;
import cz.muni.fi.cpm.template.schema.TraversalInformation;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import document_generator.CliCommands.GenerateCertificates;
import document_generator.CliCommands.GenerateChain;
import document_generator.CliCommands.PopulateBundle;
import document_generator.CliCommands.RegisterOrganisation;
import org.openprovenance.prov.notation.ProvSerialiser;
import org.openprovenance.prov.vanilla.ProvFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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
    private static final String orgId = "65XX8XUR";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    public static void fileTransformer() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        ProvFactory pF = new ProvFactory();
        CpmProvFactory cF = new CpmProvFactory(pF);
        ITemplateProvMapper mapper = new TemplateProvMapper(cF);

        try (InputStream inputStream = classLoader.getResourceAsStream("test.json")) {
            ITraversalInformationDeserializer deserializer = new TraversalInformationDeserializer();
            TraversalInformation ti = deserializer.deserializeTI(inputStream);
            ProvSerialiser serialiser = new ProvSerialiser(pF);
            org.openprovenance.prov.model.Document doc = mapper.map(ti);

            File outputFile = new File("src/main/resources/output.provn");
            serialiser.serialiseDocument(new FileOutputStream(outputFile), doc, true);

            var transformer = new Transformer();
            var doc2 = transformer.transform(doc);
            var json = transformer.createProvStorageJson(doc2);

            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File("src/main/resources/output-trans.json"), json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {

    }
}
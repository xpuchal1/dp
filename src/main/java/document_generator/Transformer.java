package document_generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.muni.fi.cpm.divided.ordered.CpmOrderedFactory;
import cz.muni.fi.cpm.vanilla.CpmProvFactory;
import org.openprovenance.prov.interop.InteropFramework;
import org.openprovenance.prov.model.*;
import org.openprovenance.prov.model.interop.Formats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class Transformer {

    public final ProvFactory pF;
    public final CpmProvFactory cPF;
    public final CpmOrderedFactory cF;
    public final ProvUtilities u;
    private final InteropFramework interop;

    public Transformer() {
        this.pF = new org.openprovenance.prov.vanilla.ProvFactory();
        this.cPF = new CpmProvFactory(pF);
        this.cF = new CpmOrderedFactory(pF);
        this.u = new ProvUtilities();
        this.interop = new InteropFramework();
    }

    public String createProvStorageJson(Document doc) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        interop.writeDocument(outputStream, doc, Formats.ProvFormat.JSON);

        InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(inputStream);
        removeJsonKeyRecursive((ObjectNode) json, "@id");

        return json.toString();
    }

    public void removeJsonKeyRecursive(ObjectNode node, String keyToRemove) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();

            if (entry.getKey().equals(keyToRemove)) {
                fields.remove();
            } else if (entry.getValue().isObject()) {
                removeJsonKeyRecursive((ObjectNode) entry.getValue(), keyToRemove);
            } else if (entry.getValue().isArray()) {
                for (JsonNode element : entry.getValue()) {
                    if (element.isObject()) {
                        removeJsonKeyRecursive((ObjectNode) element, keyToRemove);
                    }
                }
            }
        }
    }
}

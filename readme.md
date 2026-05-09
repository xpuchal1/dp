# CPM CLI Tools Documentation

Command-line tools for managing Common Provenance Model (CPM) organizations, certificates, and provenance chains.

## Table of Contents

- [Overview](#overview)
- [Commands](#commands)
    - [register-org](#register-org)
    - [generate-chain](#generate-chain)
    - [populate-bundle](#populate-bundle)
- [Common Concepts](#common-concepts)
- [Examples](#examples)

---

## Overview

This suite of CLI tools provides functionality for:

- **Organization Management**: Register organizations with certificate-based authentication
- **Provenance Chain Generation**: Create interconnected CPM provenance documents
- **Bundle Manipulation**: Populate existing bundles with typed entities

All commands interact with a provenance storage service and support cryptographic certificate management for document signing and authentication.

---

## Requirements 

### Dependencies
- Java 23
- Docker
- graphviz is required to generate svg graph if svg representation of the documents are to be stored locally

### Running the app
Run the following commands from the `dbprov-master/` directory
- `docker build -f Dockerfile.ProvStorage -t dbprov-prov-storage .`
- `docker build -f Dockerfile.TrustedParty -t dbprov-trusted-party .`
- `docker compose up -d`
Build the java CLI in the root directory
- `mvn package -f ./components_generator/pom.xml`
Run the desired command with necessary options
- `java -jar ./components_generator/cli/target/cli-1.0.0.jar <command> <options>`

## Commands

### register-org

Creates and registers a new organization with a provenance storage service, handling certificate management.

#### Description

The `register-org` command registers a new organization by either using an existing client certificate or generating a new one signed by the last intermediate certificate in the chain. The command sends the organization registration data (including the client certificate and intermediate certificate chain) to the specified storage backend via HTTP POST.

#### Usage

```bash
register-org -s <storage-url> -i <cert1> <cert2> [options]
```

#### Required Options

- `-s, --storage-base-url <url>`  
  Base URL of the provenance storage service  
  Default: `http://localhost:8001`

- `-i, --intermediate-certificates <cert1> <cert2> [cert3...]`  
  Paths to intermediate certificate files (minimum 2 required)  
  These form the certificate chain for the organization  
  The last certificate in this list will be used to sign generated client certificates

#### Optional Options

- `-o, --organization-id <id>`  
  Custom organization ID  
  If not provided, a random 8-character UUID will be generated

- `-c, --client-certificate <path>`  
  Path to an existing client certificate file  
  If omitted, a new certificate will be generated

- `-k, --intermediate-key <path>`  
  Path to the signing key of the last intermediate certificate  
  **Required if `-c` is not provided** (used to sign the generated client certificate)

- `-d, --directory <path>`  
  Base directory path for exporting generated certificates and keys  
  **Required if `-c` is not provided**  
  Generated files will be saved as:
    - `<base-path>/keys/<org-id>.key`
    - `<base-path>/certificates/<org-id>.pem`

#### Validation Rules

When generating a new client certificate (i.e., when `-c` is **not** provided):
- `-d, --directory` must be specified
- `-k, --intermediate-key` must be specified
- The last certificate from `-i` and the key from `-k` will be used to sign the new certificate

#### Examples

**Register with auto-generated certificate:**

```bash
register-org \
  -s http://localhost:8001 \
  -d ./output/ \
  -i ./certs/int1.pem ./certs/int2.pem \
  -k ./keys/int2.key \
  -o myorg123
```

**Register with existing certificate:**

```bash
register-org \
  -s https://storage.example.com \
  -c ./existing-cert.pem \
  -i ./certs/int1.pem ./certs/int2.pem ./certs/int3.pem
```

**Register with auto-generated ID and certificate:**

```bash
register-org \
  -s http://localhost:8001 \
  -d ./output/ \
  -i ./certs/int1.pem ./certs/int2.pem \
  -k ./keys/int2.key
```

#### Notes

- The generated client certificate is signed using the **last** intermediate certificate and its corresponding private key
- The command makes an HTTP POST request to `{storage-url}/api/v1/organizations/{org-id}` with a JSON payload containing the client certificate and intermediate certificate chain
- Exit codes: Non-zero on failure (network errors, invalid certificates, or HTTP errors)

[Back to top](#table-of-contents)

---

### generate-chain

Generates a CPM provenance chain with configurable length and branching structure.

#### Description

The `generate-chain` command creates a series of interconnected Common Provenance Model (CPM) provenance documents, each containing forward and backward connectors that link documents together. The command handles both the creation of new documents and the updating of referenced documents with specialized forward connectors. All generated documents are stored in the provenance storage service and optionally exported locally in JSON and SVG formats.

#### Usage

```bash
generate-chain -s <storage-url> -o <bundle-name> -d <output-dir> -L <length> -b <branching> -O <org-id> -C <cert-path>
```

#### Options

- `"-o", "–bundle-name"` - Required attribute. Prefix of names of the created bundles. The bundle name will be a combination of this prefix and the distance of the bundle from the beginning of the provenance chain. The other named traversal information, such as forward connectors or the main activity, is then derived from the bundle’s name.
- `"-L", "–length"` - Required attribute, must be an integer greater than 0. Determines the length of the provenance chain and the number of generated bundles.
- `"-b", "–branching"` - Required attribute, must be an integer greater than 0. Determines the number of forward connectors in the bundle at the beginning of the chain. The forward connectors will be used as input to the subsequent main activity
- `"-O", "–organization-id"` - Required attribute. Identifier of the organisation under which the generated bundles will be stored.
- `"-s", "–storage-base-url"` - URI of the CPF store where generated bundles will be stored. Required if directory option is not set.
- `"-k", "–key-path"` - Path to the signing key associated with the certificate associated with the registered organisation. It is needed to successfully store the created and updated bundles in the CPF store. Required if storage base url is set.
- `"-d", "–directory"` - The path to a directory, where a JSON serialisation of the bundle will be stored. Required if storage base url option is not set.
- `"-g", "–create-graph"` - Boolean option. Controls whether a visual representation of the given bundle will be created in the output directory. It is ignored if the output directory is not set. Requires graphviz installed

#### Validation Rules

- Chain length (`L`) must be greater than 0
- Branching factor (`-b`) must be greater than or equal to 0
- All required paths (certificate, output directory) must be accessible

#### Examples

**Generate a simple linear chain:**

```bash
generate-chain \
  -s http://localhost:8000/ \
  -o medical-procedure \
  -d ./output/bundles/ \
  -L 5 \
  -b 4 \
  -O hospital-org-001 \
  -k ./certs/hospital.key
```

**Generate a complex branching chain:**

```bash
generate-chain \
  -s https://prov-storage.example.com/ \
  -o experiment \
  -d /var/data/provenance/ \
  -L 10 \
  -b 15 \
  -O research-lab-42 \
  -k ./certs/lab-cert.key
```

#### How It Works

1. **Document Generation**: Creates `n` provenance documents sequentially, each named `<bundle-name><index>`
2. **Connector Management**:
    - Links documents using forward and backward connectors
    - Maintains connector derivation mappings
    - Adds specialized forward connectors to referenced bundles
3. **Storage**: Uploads each document to the provenance storage service and receives a cryptographic token
4. **Redundant Connectors**: After base document creation, adds redundant forward connectors to ensure complete provenance traceability
5. **Local Export**: Saves documents in both JSON and SVG formats to the specified output directory

#### Output

For each generated bundle, the command creates:
- `<output-dir>/<bundle-name><index>.json` - JSON representation of the provenance document
- `<output-dir>/<bundle-name><index>.svg` - SVG visualization of the provenance graph
- Metadata bundles with `_meta` suffix for connector references

#### Notes

- The first bundle in the chain has `branching - length + 2` outputs
- All subsequent bundles have 1 output by default
- Documents are versioned automatically with timestamps when updated
- The command uses SHA-256 for hash algorithms in connectors
- Meta bundles are created at `{storage-url}/api/v1/documents/meta/{bundle-id}_meta`
- Progress is logged to console showing each document creation and save operation

[Back to top](#table-of-contents)

---

### populate-bundle

Populates an existing CPM bundle with a specified number of entities of a given type.

#### Description

The `populate-bundle` command adds entities to an existing Common Provenance Model (CPM) bundle. It creates a chain of entities connected through specialization and derivation relationships, anchored to a specified forward connector. The command allows control over the positioning of the typed entity within the chain by specifying distances from forward and backward connectors.

#### Usage

```bash
populate-bundle -B <bundle-id> -c <connector-id> -e <entity-count> -t <type> -C <cert-path> [options]
```

#### Options

- `"-p", "--bundle-path"` - Path to a bundle stored in the file system in json format. Required if storage base url is not set.
- `"-s", "--storage-base-url"` - URI of the CPF store, where the bundle to update is stored. The bundle is loaded from the file system if bundle path is set, however, the updated version is still uploaded to CPF store. Required if bundle path is not set.
- `"-O", "--organization-id"` - Identifier of the organisation under which the bundle to update is stored. Required if storage base url is set.
- `"-k", "--key-path"` - Path to the signing key of the organisation under which the bundle to update is stored. Needed for the successful update of the bundle. Required if storage base url is set.
- `"-B", "--bundle-id"` - Required attribute. Identifier of the bundle to update.
- `"-c", "--connector-id"` - Required attribute. Identifier of the forward connector, to which a derivation path should be created.
- `"-t", "--type"` - Required attribute. Determines the type of the special entity.
- `"-f", "--forward-distance"` - Length of the derivation path from "type" entity to the forward connector. Must be a positive integer.
- `"-b", "--backward-distance"` - Length of the derivation path from the related backward connector to the "type" entity. Must be a positive integer. Value is ignored if the forward connectors derive from no backward connectors.
- `"-e", "--entity-count"` - Required attribute. Must be greater than 0. Must be greater than or equal to the sum of the backward distance and forward distance. Total count of the created entities.
- `"-d", "--directory"` - The path to a directory, where a JSON serialisation of the bundle will be stored.
- `"-g", "--create-graph"` - Boolean option. Controls whether a visual representation of the given bundle will be created at the output directory. It is ignored if the output directory is not set. Requires Graphviz to be installed.

#### Validation Rules

- Entity count must be greater than 0
- Entity count must be greater than `forward_distance + backward_distance`
- The specified connector ID must exist in the bundle as a forward connector
- If no backward connectors exist (derivation is empty), backward distance is automatically set to 0

#### Examples

**Populate with 10 Patient entities:**

```bash
populate-bundle \
  -B medical-bundle-001 \
  -c fc-connector-123 \
  -e 10 \
  -t Patient \
  -C ./certs/hospital.pem \
  -O hospital-org-001
```

**Populate with specific positioning:**

```bash
populate-bundle \
  -s http://localhost:8000/ \
  -B experiment-bundle \
  -c connector-abc \
  -e 20 \
  -t Measurement \
  -f 5 \
  -b 3 \
  -C ./certs/lab-cert.pem \
  -O research-lab-42
```

**Populate with random positioning:**

```bash
populate-bundle \
  -B data-bundle \
  -c forward-xyz \
  -e 15 \
  -t Observation \
  -C ./certs/org.pem
```

#### How It Works

1. **Fetch Bundle**: Retrieves the specified bundle from the provenance storage service
2. **Locate Connector**: Finds the forward connector with the specified ID
3. **Determine Derivation**: Identifies backward connectors from which the forward connector was derived
4. **Create Entity Chain**: Generates a chain of entities with the following structure:
    - Entities from index 0 to `forward_distance`: Connected via specialization/derivation to the forward connector
    - Entity at index `forward_distance`: Tagged with the specified type and a unique ID attribute
    - Entities from `forward_distance + 1` to `forward_distance + backward_distance`: Continue the derivation chain
    - Entity at index `forward_distance + backward_distance`: Specialized from the backward connector(s)
    - Remaining entities: Added without additional connections
5. **Update Bundle**: Adds all new statements to the bundle and versions it with a new timestamp
6. **Store**: Uploads the updated bundle to the provenance storage service
7. **Export**: Saves the bundle locally in PROVN and SVG formats

#### Entity Naming

Entities are named with the pattern: `<connector-id>-entity-<index>`

For example, if connector ID is `fc-123` and 5 entities are created:
- `fc-123-entity-0`
- `fc-123-entity-1`
- `fc-123-entity-2`
- `fc-123-entity-3`
- `fc-123-entity-4`

#### Output

The command generates:
- `src/main/resources/output/fixed.provn` - PROVN representation of the updated bundle
- `src/main/resources/output/fixed.svg` - SVG visualization of the updated bundle
- Updated bundle stored in the provenance storage service with versioned ID

#### Notes

- If forward distance is 0 (default), it's randomly determined within valid bounds
- If backward distance is 0 (default) and derivations exist, it's randomly determined within valid bounds
- The bundle is automatically versioned with a timestamp suffix (e.g., `bundle-v1738963200000`)
- The typed entity receives both a type attribute (CPM namespace) and a type-specific ID attribute (e.g., `PatientId`)
- All entities use the CPM namespace: `https://www.commonprovenancemodel.org/cpm-namespace-v1-0/`

[Back to top](#table-of-contents)

---

## Common Concepts

### CPM (Common Provenance Model)

The Common Provenance Model is a framework for representing and managing provenance information in a cryptographically verifiable manner. It extends the W3C PROV standard with cryptographic capabilities.

### Certificates

All commands use X.509 certificates for:
- **Authentication**: Identifying organizations
- **Signing**: Cryptographically signing provenance documents
- **Verification**: Ensuring document integrity and authenticity

### Forward and Backward Connectors

- **Forward Connectors**: References from a document to documents that depend on it
- **Backward Connectors**: References from a document to documents it depends on
- These connectors create a verifiable chain of provenance across documents

### Bundles

A bundle is a container for provenance statements in the CPM model. Each bundle:
- Has a unique identifier
- Contains entities, activities, and relationships
- Can be cryptographically signed
- May reference other bundles through connectors

### Document Versioning

Documents are versioned using timestamps:
- Format: `<bundle-name>-v<timestamp>`
- Example: `medical-procedure-v1738963200000`
- Ensures immutability while allowing updates

[Back to top](#table-of-contents)

---

## Examples

### Complete Workflow: Setup to Provenance Chain

#### Step 1: Register an Organization

```bash
# Generate and register a new organization with certificates
register-org \
  -s http://localhost:8001 \
  -p ./org-data/ \
  -i ./certs/root.pem ./certs/intermediate.pem \
  -k ./keys/intermediate.key \
  -o hospital-001
```

Output:
```
Generated key saved as: ./org-data/keys/hospital-001.key
Generated certificate saved as: ./org-data/certificates/hospital-001.pem
Registered organisation: hospital-001
```

#### Step 2: Generate a Provenance Chain

```bash
# Create a chain of 5 provenance documents
generate-chain \
  -s http://localhost:8000/ \
  -o patient-journey \
  -d ./provenance-output/ \
  -L 5 \
  -b 4 \
  -O hospital-001 \
  -C ./org-data/certificates/hospital-001.pem
```

Output:
```
Starting index: 0
Document: patient-journey0 saved to ./provenance-output/patient-journey0
Starting index: 1
Document: patient-journey1 saved to ./provenance-output/patient-journey1
...
Finished creating base documents.
```

#### Step 3: Populate a Bundle with Entities

```bash
# Add patient entities to the first bundle
populate-bundle \
  -s http://localhost:8000/ \
  -B patient-journey0 \
  -c forward-connector-001 \
  -e 10 \
  -t Patient \
  -f 3 \
  -b 2 \
  -C ./org-data/certificates/hospital-001.pem \
  -O hospital-001
```

### Multi-Organization Setup

```bash
# Register first organization (hospital)
register-org \
  -s http://localhost:8001 \
  -p ./hospital-data/ \
  -i ./certs/root.pem ./certs/int.pem \
  -k ./keys/int.key \
  -o hospital-001

# Register second organization (lab)
register-org \
  -s http://localhost:8001 \
  -p ./lab-data/ \
  -i ./certs/root.pem ./certs/int.pem \
  -k ./keys/int.key \
  -o lab-001

# Each organization can now create their own provenance chains
```

### Research Data Workflow

```bash
# Register research organization
register-org \
  -s https://research-prov.example.com \
  -p ./research-org/ \
  -i ./certs/university-root.pem ./certs/dept-intermediate.pem \
  -k ./keys/dept-intermediate.key \
  -o research-lab-42

# Generate experimental data chain
generate-chain \
  -s https://research-prov.example.com/ \
  -o experiment-2024 \
  -d ./experiments/ \
  -L 8 \
  -b 12 \
  -O research-lab-42 \
  -C ./research-org/certificates/research-lab-42.pem

# Add measurement entities to specific bundles
populate-bundle \
  -s https://research-prov.example.com/ \
  -B experiment-2024-3 \
  -c measurement-connector \
  -e 50 \
  -t Measurement \
  -C ./research-org/certificates/research-lab-42.pem \
  -O research-lab-42
```

[Back to top](#table-of-contents)

---

## License

[Add your license information here]

## Support

[Add support contact information here]

---

**Last Updated**: February 2026
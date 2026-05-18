# CPM CLI Tools Documentation

Command-line tools for managing Common Provenance Model (CPM) organizations, certificates, and provenance chains.

## Table of Contents

- [Requirements](#Requirements)
- [Commands](#commands)
    - [register-org](#register-org)
    - [generate-chain](#generate-chain)
    - [populate-bundle](#populate-bundle)
---

## Requirements 

### Dependencies
- Java 23
- Docker
- graphviz is required to generate svg graph if svg representation of the bundles are to be stored locally

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

#### Options

- `-s, --storage-base-url <url>`  
  Base URL of the provenance storage service  
  Default: `http://localhost:8001`

- `-i, --intermediate-certificates <cert1> <cert2> [cert3...]`  
  Paths to intermediate certificate files (minimum 2 required)  
  These form the certificate chain for the organization  
  The last certificate in this list will be used to sign generated client certificates

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

[Back to top](#table-of-contents)

---

### generate-chain

Generates a CPM provenance chain with configurable length and branching structure.

#### Description

The `generate-chain` command creates a series of interconnected provenance bundles, each containing forward and backward connectors that link bundles together. The command handles both the creation of new bundles and the updating of referenced bundles with specialized forward connectors. All generated bundles are stored in the provenance storage service and optionally exported locally in JSON and SVG formats.

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

#### Examples

**Generate a simple chain:**

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

#### Output

For each generated bundle, the command creates:
- `<output-dir>/<bundle-name><index>.json` - JSON representation of the provenance bundle
- `<output-dir>/<bundle-name><index>.svg` - SVG visualization of the provenance graph
- Metadata bundles with `_meta` suffix for connector references


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

[Back to top](#table-of-contents)

## Support

[Add support contact information here]

---

**Last Updated**: February 2026

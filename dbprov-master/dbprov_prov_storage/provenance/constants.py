from distributed_prov_system.settings import config
from prov.identifier import Namespace

# Templates
DEFAULT_NAMESPACE_PLACEHOLDER = (
    f"{config.fqdn}/api/v1/organizations/{{org_name}}/documents/{{bundle_name}}#"
)

# Normative namespaces
CPM = Namespace("cpm", "https://www.commonprovenancemodel.org/cpm-namespace-v1-0/")
META = Namespace("meta", f"{config.fqdn}/api/v1/documents/meta/")
PAV = Namespace("pav", "http://purl.org/pav/")
DCT = Namespace("dct", "http://purl.org/dc/terms/")

# CPM Structure
CPM_ID = CPM["id"]
CPM_SENDER_AGENT = CPM["senderAgent"]
CPM_RECEIVER_AGENT = CPM["receiverAgent"]
CPM_CURRENT_AGENT = CPM["currentAgent"]
CPM_MAIN_ACTIVITY = CPM["mainActivity"]
CPM_BACKWARD_CONNECTOR = CPM["backwardConnector"]
CPM_FORWARD_CONNECTOR = CPM["forwardConnector"]
CPM_SPEC_FORWARD_CONNECTOR = CPM["specForwardConnector"]


# CPM Attributes
CPM_HAS_ID = CPM["hasId"]
CPM_EXTERNAL_ID = CPM["externalId"]
CPM_EXTERNAL_ID_TYPE = CPM["externalIdType"]
CPM_REFERENCED_BUNDLE_ID = CPM["referencedBundleId"]
CPM_REFERENCED_META_BUNDLE_ID = CPM["referencedMetaBundleId"]
CPM_REFERENCED_BUNDLE_HASH_VALUE = CPM["referencedBundleHashValue"]
CPM_PROVENANCE_SERVICE_URI = CPM["provenanceServiceUri"]
CPM_DESCRIBED_OBJECT_TYPE = CPM["describedObjectType"]
CPM_HASH_VALUE = CPM["hashValue"]
CPM_HASH_ALG = CPM["hashAlg"]
CPM_COMMENT = CPM["comment"]
CPM_TOKEN_GENERATION = CPM["tokenGeneration"]
CPM_TOKEN = CPM["token"]
CPM_TRUSTED_PARTY = CPM["trustedParty"]
CPM_TRUSTED_PARTY_URI = CPM["trustedPartyUri"]
CPM_TRUSTED_PARTY_CERTIFICATE = CPM["trustedPartyCertificate"]
CPM_CONTACT_ID_PID = CPM["contactIdPid"]

# New constants
BACKWARDS_CONNECTOR_MANDATORY_ATTRIBUTES = (
    CPM_REFERENCED_BUNDLE_ID,
    CPM_REFERENCED_META_BUNDLE_ID,
    CPM_REFERENCED_BUNDLE_HASH_VALUE,
    CPM_HASH_ALG,
)
FORWARD_CONNECTOR_OPTIONAL_ATTRIBUTES = (
    CPM_REFERENCED_BUNDLE_ID,
    CPM_REFERENCED_META_BUNDLE_ID,
    CPM_REFERENCED_BUNDLE_HASH_VALUE,
    CPM_HASH_ALG,
)

# DCT attributes
DCT_HAS_PART = DCT["hasPart"]


# PAV attributes
PAV_VERSION = PAV["version"]

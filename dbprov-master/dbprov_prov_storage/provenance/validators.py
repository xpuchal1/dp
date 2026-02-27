import re

import prov.model as provm
import base64
import requests
import concurrent.futures
from urllib.parse import urlparse
import socket
import neomodel
import json

from prov.identifier import QualifiedName, Namespace
from prov.model import ProvAgent, ProvActivity, ProvEntity

from .CPM_validation_strategies import CPMValidatorFirst
from .controller import IS_BACKBONE
from .prov_doc_validators_strategies import ProvValidator
from .constants import (
    CPM_REFERENCED_META_BUNDLE_ID,
    CPM_REFERENCED_BUNDLE_ID,
    CPM_HASH_ALG,
    CPM_REFERENCED_BUNDLE_HASH_VALUE,
    CPM_FORWARD_CONNECTOR,
    CPM_BACKWARD_CONNECTOR,
)
from .models import Document, Organization, Entity, Bundle, TrustedParty
from neomodel.exceptions import DoesNotExist
from distributed_prov_system.settings import config


# change - CPM validator initialized here
CPM_VALIDATOR = CPMValidatorFirst()

# change the setting whether to test validity of PROV document if needed
CHECK_PROV_VALIDITY = False


class HasNoBundles(Exception):
    pass


class TooManyBundles(Exception):
    pass


class ConnectorReferenceInvalidError(Exception):
    pass


class DocumentError(Exception):
    pass


class OrganizationNotRegistered(Exception):
    pass


class UncheckedTrustedParty(Exception):
    pass


class InvalidTrustedParty(Exception):
    pass


def is_org_registered(organization_id) -> bool:
    try:
        # check if organization already exists
        Organization.nodes.get(identifier=organization_id)

        return True
    except DoesNotExist:
        return False


def check_organization_is_registered(organization_id):
    if not is_org_registered(organization_id):
        raise OrganizationNotRegistered(
            f"Organization with id [{organization_id}] is not registered! "
            f"Please register your organization first."
        )

    org = Organization.nodes.get(identifier=organization_id)
    tp = list(org.trusts.all())[0]
    if not tp.checked:
        raise UncheckedTrustedParty(
            f"Trusted party for organization with id [{organization_id}] has not yet been "
            f"checked for its validity. Please be patient."
        )

    if not tp.valid:
        raise InvalidTrustedParty(
            f"Trusted party for organization with id [{organization_id}] has been checked "
            f"and is not considered valid. For more information contact administrator."
        )


def graph_exists(organization_id, graph_id) -> bool:
    try:
        # check if document already exists
        Document.nodes.get(identifier=f"{organization_id}_{graph_id}")

        return True
    except DoesNotExist:
        return False


# change - removed check that the latest version of component had to be in request
def check_graph_id_belongs_to_meta(meta_provenance_id, graph_id, organization_id):
    entity = Entity.nodes.get(identifier=f"{organization_id}_{graph_id}")

    try:
        Bundle.nodes.get(identifier=meta_provenance_id)
    except DoesNotExist:
        raise DocumentError(
            f"Meta provenance with id [{meta_provenance_id}] does not exist!"
        )

    connected_meta_bundles = entity.contains
    assert (
        len(connected_meta_bundles) == 1
    ), "Entity cannot be part of more than one meta bundles"

    if (
        meta_identifier := connected_meta_bundles.single().identifier
    ) != meta_provenance_id:
        raise DocumentError(
            f"Graph with id [{graph_id}] is part of meta bundle with id [{meta_identifier}],"
            f" however main_activity from given bundle is resolvable to different id [{meta_provenance_id}]"
        )


def send_signature_verification_request(payload, organization_id, tp_url=None):
    if tp_url is None:
        tp_url = config.tp_fqdn

    url = f"http://{tp_url}/api/v1/verifySignature"

    payload["organizationId"] = organization_id
    resp = requests.post(url, json.dumps(payload))

    return resp


# change - this function now checks also the referencedBundleHashValue
def ping_connectors_and_check_hash(connector):
    if url := connector.get_attribute(CPM_REFERENCED_BUNDLE_ID):
        url = provm.first(url).uri
        resp = requests.head(url)
        # change - also check CPM_REFERENCED_META_BUNDLE_ID here, retrieve document because of its hash, and check it
        url2 = connector.get_attribute(CPM_REFERENCED_META_BUNDLE_ID)
        url2 = provm.first(url2).uri
        resp2 = requests.head(url2)
        res2 = requests.get(url)
        # if document available, check hash
        if res2.status_code == 200:
            json_data = json.loads(res2.content)
            # trusted party may be turned off so no token is returned
            if "token" in json_data:
                hash1 = provm.first(
                    connector.get_attribute(CPM_REFERENCED_BUNDLE_HASH_VALUE)
                )
                hash_alg = provm.first(connector.get_attribute(CPM_HASH_ALG))
                token = json_data["token"]
                correct = re.search(r'"(.*?)"', str(hash1)).group(1)
                return (
                    resp,
                    resp2,
                    (
                        token["data"]["documentDigest"] == correct
                        and token["data"]["additionalData"]["hashFunction"] == hash_alg
                    ),
                )
        # if document is not retrievable, hash does not have to be checked
        return resp, resp2, True
    return None


def filter_own_address(url):
    if contains_my_ip_addr(url):
        return ""
    return url.netloc


def contains_my_ip_addr(url):
    hostname = url.hostname

    try:
        socket.inet_aton(hostname)
    except socket.error:
        hostname = socket.gethostbyname(hostname)

    return hostname in socket.gethostbyname_ex(socket.gethostname())[-1]


def _check_connectors_references(futures):
    result = []
    for future in concurrent.futures.as_completed(futures):
        connector = futures[future]
        res = future.result()

        if res is not None:  # fix - added check for None
            # change - checking also meta bundle result and hash result
            referenced_bundle_resp, referenced_meta_bundle_resp, is_hash_ok = res
            if not referenced_bundle_resp.ok:
                return (
                    False,
                    f"Referenced bundle URI of connector [{connector.identifier.localpart}] not found.",
                )
            if not referenced_meta_bundle_resp.ok:
                return (
                    False,
                    f"Referenced meta bundle URI of connector [{connector.identifier.localpart}] not found.",
                )
            if not is_hash_ok:
                return (
                    False,
                    f"Hash of bundle [{connector.identifier.localpart}] has wrong value.",
                )
            parsed_url = urlparse(referenced_bundle_resp.url)
            result.append(
                (filter_own_address(parsed_url), connector)
            )  # used before for connectors - may be used in future
    return True, result


class InputGraphChecker:
    _NOT_YET_PARSED_ERROR_MSG = "Graph not yet parsed."
    _NOT_YET_VALIDATED_ERROR_MSG = "Graph not yet validated."

    def __init__(self, graph, format, request_url, prov_validity_checker):
        self._graph = base64.b64decode(graph)
        self._graph_format = format

        self._prov_document = None
        self._prov_bundle = None
        self._main_activity = None
        self._meta_provenance_id = None
        self._forward_connectors = None
        self._backward_connectors = None
        self._processed_forward_connectors = None
        self._processed_backward_connectors = None
        self._request_url = request_url
        self._prov_validator: ProvValidator = prov_validity_checker

    def get_document(self):
        assert self._prov_document is not None, self._NOT_YET_PARSED_ERROR_MSG

        return self._prov_document

    def get_bundle_id(self):
        assert self._prov_bundle is not None, self._NOT_YET_PARSED_ERROR_MSG

        return self._prov_bundle.identifier.localpart

    def get_meta_provenance_id(self):
        assert self._meta_provenance_id is not None, self._NOT_YET_PARSED_ERROR_MSG

        return self._meta_provenance_id

    def get_forward_connectors(self):
        assert (
            self._processed_forward_connectors is not None
        ), self._NOT_YET_VALIDATED_ERROR_MSG

        return self._processed_forward_connectors

    def get_backward_connectors(self):
        assert (
            self._processed_backward_connectors is not None
        ), self._NOT_YET_VALIDATED_ERROR_MSG

        return self._processed_backward_connectors

    # --- VALIDATIONS ---

    def parse_graph(self):
        self._prov_document = provm.ProvDocument.deserialize(
            content=self._graph, format=self._graph_format
        )

        self._prov_bundle = list(self._prov_document.bundles)[0]
        self._main_activity = self._retrieve_main_activity()
        self._meta_provenance_id = self._check_resolvability_and_retrieve_meta_id()
        self._forward_connectors, self._backward_connectors = (
            self._retrieve_connectors_from_graph()
        )

    def check_ids_match(self, graph_id):
        if self._prov_bundle.identifier.localpart != graph_id:
            raise DocumentError(
                f"The bundle id [{self._prov_bundle.identifier.localpart}] does not match the "
                f"specified id [{graph_id}] from query."
            )
        if not str(self._prov_bundle.identifier.uri).endswith(self._request_url):
            raise DocumentError(
                f"The bundle identifier [{self._prov_bundle.identifier.uri}] has not the same query parameters called to save it. [{self._request_url}]"
            )

    def validate_graph(self):
        assert (
            self._prov_document is not None and self._prov_bundle is not None
        ), self._NOT_YET_PARSED_ERROR_MSG

        if not self._prov_document.has_bundles():
            raise HasNoBundles("There are no bundles inside the document!")

        if len(self._prov_document.bundles) != 1:
            raise TooManyBundles("Only one bundle expected in document!")

        # change - next checks and their order done in this thesis
        are_ok_backward_connectors = CPM_VALIDATOR.check_backward_connectors_attributes(
            self._backward_connectors
        )
        if not are_ok_backward_connectors:
            raise DocumentError(
                f"Backward connector(s) is/are missing mandatory attributes."
            )

        are_ok_forward_connectors = CPM_VALIDATOR.check_forward_connectors_attributes(
            self._forward_connectors
        )
        if not are_ok_forward_connectors:
            raise DocumentError(
                f"Forward connector(s) is/are missing mandatory attributes."
            )

        # this one check was also in original program
        are_resolvable, error_msg = self._process_bundle_references()
        if not are_resolvable:
            raise ConnectorReferenceInvalidError(error_msg)

        is_cpm_ok, error_message = CPM_VALIDATOR.check_cpm_constraints(
            self._prov_bundle,
            self._forward_connectors,
            self._backward_connectors,
            self._main_activity,
        )
        if not is_cpm_ok:
            raise DocumentError(f"CPM problem: {error_message}")

        # FIXME - this check disabled for now, because it is too strict
        # if not self._check_namespaces():
        #    raise DocumentError(
        #        f"The bundle with id [{self._prov_bundle.identifier.localpart}] does not have all namespaces defined or some id is not in namespace."
        #    )

        if CHECK_PROV_VALIDITY:
            is_valid_prov = self._prov_validator.is_valid(document=self._prov_document)
            if not is_valid_prov:
                raise DocumentError(
                    f"The bundle with id [{self._prov_bundle.identifier.localpart}] is not valid according to PROV standard."
                )

    def _check_resolvability_and_retrieve_meta_id(self):
        if not (
            meta_bundle := provm.first(
                self._main_activity.get_attribute(CPM_REFERENCED_META_BUNDLE_ID)
            )
        ):
            raise DocumentError(
                f"Main activity missing required attribute '{CPM_REFERENCED_META_BUNDLE_ID}'."
            )
        resp = requests.head(meta_bundle.uri)
        parsed_url = urlparse(resp.url)
        if not contains_my_ip_addr(parsed_url):
            raise DocumentError(
                "Main activity URI is expected to be local to this server's "
                f"IP address, however it resolved to [{parsed_url.hostname}]"
            )

        if "/api/v1/documents/meta/" not in parsed_url.path:
            raise DocumentError(
                f"Main activity URI is not a valid metabundle location: [{parsed_url.path}]. "
                "Expected: /api/v1/documents/meta/"
            )

        return parsed_url.path.rpartition("/")[-1]

    def _retrieve_main_activity(self):
        main_activity = None

        for activity in self._prov_bundle.get_records(provm.ProvActivity):
            prov_types = activity.get_asserted_types()

            if prov_types is None:
                continue

            if activity.bundle.valid_qualified_name("cpm:mainActivity") in prov_types:
                if main_activity is not None:
                    raise DocumentError(
                        f"Multiple 'mainActivity' activities specified inside of bundle "
                        f"[{self._prov_bundle.identifier.localpart}]"
                    )
                main_activity = activity
        # change - added this check
        if main_activity is None:
            raise DocumentError(
                f"No 'mainActivity' activity specified inside of bundle "
                f"[{self._prov_bundle.identifier.localpart}]"
            )
        return main_activity

    def _retrieve_connectors_from_graph(self):
        forward_connectors = []
        backward_connectors = []

        for entity in self._prov_bundle.get_records(provm.ProvEntity):
            prov_types = entity.get_asserted_types()

            if prov_types is None:
                continue

            if IS_BACKBONE.is_backbone_element(
                entity, self._prov_bundle
            ):  # change - check whether connector belongs to traversal information
                for t in prov_types:
                    if t.localpart == "specForwardConnector":
                        forward_connectors.append(entity)
                    elif t.localpart == "backwardConnector":
                        backward_connectors.append(entity)

        return forward_connectors, backward_connectors

    def _process_bundle_references(self):
        self._processed_forward_connectors = []
        self._processed_backward_connectors = []
        # Concurrent check if resource exists using Http.HEAD request
        with concurrent.futures.ThreadPoolExecutor() as executor:
            backward_conns_futures = {
                executor.submit(ping_connectors_and_check_hash, connector): connector
                for connector in self._backward_connectors
            }
            forward_conns_futures = {
                executor.submit(ping_connectors_and_check_hash, connector): connector
                for connector in self._forward_connectors
            }

        # Processing backward connectors
        result = _check_connectors_references(backward_conns_futures)
        if result[0]:
            self._processed_backward_connectors.extend(result[1])
        if not result[0]:
            return result

        # Processing forward connectors
        result = _check_connectors_references(forward_conns_futures)
        if result[0]:
            self._processed_forward_connectors.extend(result[1])
        if not result[0]:
            return result

        return True, ""

    # change - this check added to check namespaces used in a component
    def _check_namespaces(self):
        namespaces = list(self._prov_bundle.namespaces)
        namespaces.extend(self._prov_document.namespaces)
        namespaces.extend(provm.DEFAULT_NAMESPACES)
        for element in self._prov_bundle.get_records(
            class_or_type_or_tuple=(ProvAgent, ProvActivity, ProvEntity)
        ):
            if element.identifier == "" or element.identifier is None:
                continue
            if not isinstance(element.identifier, QualifiedName):
                return False
            if not element.identifier.namespace in namespaces:
                return False
            # last character of namespace uri must be special
            pattern = re.compile(r".*[-\._~:/\?#\[\]@!\$&\'\(\)\*\+,;%=]$")
            if not re.match(pattern, element.identifier.namespace.uri):
                return False
        return True

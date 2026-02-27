import copy
import datetime
import json
import requests
from neomodel import db

import provenance.controller as controller
from distributed_prov_system.settings import config
from django.http import JsonResponse, HttpResponse, HttpResponseNotFound
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods
from django.views.decorators.http import require_GET
from django.views.decorators.http import require_safe
from neomodel.exceptions import DoesNotExist

from .prov_doc_validators_strategies import (
    ProvValidatorWithNormalization,
    ProvValidatorExternal,
)
from .prov2neomodel import import_graph

from .validators import (
    InputGraphChecker,
    graph_exists,
    check_graph_id_belongs_to_meta,
    ConnectorReferenceInvalidError,
    HasNoBundles,
    TooManyBundles,
    DocumentError,
    is_org_registered,
    InvalidTrustedParty,
    UncheckedTrustedParty,
    OrganizationNotRegistered,
    check_organization_is_registered,
    send_signature_verification_request,
)

# change - external PROV validator initialized here - to add option to change it at runtime by api call if needed in future
PROV_VALIDATOR = ProvValidatorExternal()


def get_dummy_token(organization_id="ORG"):
    return {
        "data": {
            "originatorId": organization_id,
            "authorityId": "TrustedParty",
            "tokenTimestamp": 0,
            "documentCreationTimestamp": 0,
            "documentDigest": "17fd7484d7cac628cfa43c348fe05a009a81d18c8a778e6488b707954addf2a3",
        },
        "signature": "bdysXEy2/sOSTN+Lh+v3x7cTdocMcndwuW5OT2wHpQOU/LM4os9Bow0sn4HTln9hRqFdCMukV6Cr6Nn8XvD96jlgEw9KqJj9I+cfBL81x9iqUJX/Wder3lkuIZXYUSeGsOOqUPdlqJAhapgr0V+vibAvPGoiRKqulNi/Xn0jn21lln1HEbHPsnOtM5Ca5wwXuTITJsiXCj+04y9V/XM9Uy9Ib4LLA1VYLCdifjg0ZuxJBcpS/HszlwW9B29rrkUGUsSrV9YU0ViYkeIMcS2bMXsur3EHi3/zSZ5IepUNOBDTu3BDUr33dbrgMOVraI8RU5DTZKmUOx8hzgtApZNotg==",
    }


@csrf_exempt
@require_http_methods(["POST", "PUT"])
def register(request, organization_id):
    if config.disable_tp:
        return JsonResponse({"info": "The registration is off when TP is disabled!"})

    if request.method == "POST":
        return register_org(request, organization_id)
    else:
        return modify_org(request, organization_id)


def register_org(request, organization_id):
    if is_org_registered(organization_id):
        return JsonResponse(
            {
                "error": f"Organization with id [{organization_id}] is already registered. "
                f"If you want to modify it, send PUT request!"
            },
            status=409,
        )

    json_data = json.loads(request.body)
    expected_json_fields = ("clientCertificate", "intermediateCertificates")
    for field in expected_json_fields:
        if field not in json_data:
            return JsonResponse(
                {"error": f"Mandatory field [{field}] not present in request!"},
                status=400,
            )

    resp = send_register_request_to_tp(json_data, organization_id)
    if (
        not resp.ok
    ):  # fix - changed to .ok - before was 401 - trusted party can return also 400, 409 and throws error
        return JsonResponse(
            {"error": "Trusted party was unable to verify certificate chain!"},
            status=401,
        )

    controller.create_and_store_organization(
        organization_id,
        json_data["clientCertificate"],
        json_data["intermediateCertificates"],
        json_data["TrustedPartyUri"] if "TrustedPartyUri" in json_data else None,
    )

    return HttpResponse(status=201)


def modify_org(request, organization_id):
    if not is_org_registered(organization_id):
        return JsonResponse(
            {"error": f"Organization with id [{organization_id}] is not registered!"},
            status=404,
        )

    json_data = json.loads(request.body)
    expected_json_fields = ("clientCertificate", "intermediateCertificates")
    for field in expected_json_fields:
        if field not in json_data:
            return JsonResponse(
                {"error": f"Mandatory field [{field}] not present in request!"},
                status=400,
            )

    resp = send_register_request_to_tp(json_data, organization_id, is_post=False)
    if resp.status_code == 401:
        return JsonResponse(
            {"error": "Trusted party was unable to verify certificate chain!"},
            status=401,
        )

    controller.modify_organization(
        organization_id,
        json_data["clientCertificate"],
        json_data["intermediateCertificates"],
        json_data["TrustedPartyUri"] if "TrustedPartyUri" in json_data else None,
    )

    return HttpResponse(status=200)


def send_register_request_to_tp(payload, organization_id, is_post=True):
    tp_url = (
        payload["TrustedPartyUri"] if "TrustedPartyUri" in payload else config.tp_fqdn
    )
    url = "http://" + tp_url + f"/api/v1/organizations/{organization_id}"
    payload["organizationId"] = organization_id

    if is_post:
        resp = requests.post(url, json.dumps(payload))
    else:
        resp = requests.put(url, json.dumps(payload))

    return resp


@csrf_exempt
@require_http_methods(["GET", "POST", "PUT", "HEAD"])
def document(request, organization_id, document_id):
    if request.method == "POST":
        return store_graph(request, organization_id, document_id)
    elif request.method == "PUT":
        return store_graph(request, organization_id, document_id, is_update=True)
    # change - added head request for checking resolvability
    elif request.method == "HEAD":
        if controller.bundle_exists(f"{organization_id}_{document_id}"):
            return HttpResponse(200)
        return (
            HttpResponseNotFound()
        )  # fix - before, 200 was returned as status and 404 as content
    else:
        return get_graph(request, organization_id, document_id)


def store_graph(request, organization_id, document_id, is_update=False):
    json_data = json.loads(request.body)
    url_requested = request.get_full_path()

    validator = InputGraphChecker(
        json_data["document"],
        json_data["documentFormat"],
        url_requested,
        PROV_VALIDATOR,
    )
    if validation_error := _validate_request(
        json_data, validator, document_id, organization_id, is_update, config.disable_tp
    ):
        return validation_error

    if not config.disable_tp:
        tp_url = controller.get_tp_url_by_organization(organization_id)
        payload = json_data.copy()
        payload["organizationId"] = organization_id
        payload["type"] = "graph"
        payload["graphId"] = document_id
        token = controller.send_token_request_to_tp(payload, tp_url)
    else:
        # fix - if trusted party off, component saved under organization identifier
        token = get_dummy_token(organization_id)

    document = validator.get_document()
    import_graph(
        document,
        json_data,
        copy.deepcopy(token),
        validator.get_meta_provenance_id(),
        document_id,
        is_update,
    )

    if not config.disable_tp:
        token2, neo_document, trusted_party = controller.get_token_to_store_into_db(
            token, validator.get_bundle_id()
        )
        with db.transaction:
            controller.store_token_into_db(token2, neo_document, trusted_party)
        response = {"token": token}
    else:
        response = {
            "info": "Trusted party is disabled therefore no token has been issued, "
            "however graph has been stored."
        }

    return JsonResponse(response, status=201)


def get_graph(_, organization_id, document_id):
    try:
        d = controller.get_provenance(organization_id, document_id)
        if not config.disable_tp:
            t = controller.get_token(organization_id, document_id, d)
    except DoesNotExist:
        return JsonResponse(
            {
                "error": f"Document with id [{document_id}] does not "
                f"exist under organization [{organization_id}]."
            },
            status=404,
        )

    if not config.disable_tp:
        response = {"document": d.graph, "token": t}
    else:
        response = {"document": d.graph}

    return JsonResponse(response)


# added in https://gitlab.ics.muni.cz/422328/dbprov
def _validate_request_fields(request_json, mandatory_fields):
    for field in mandatory_fields:
        if field not in request_json:
            return JsonResponse(
                {"error": f"Mandatory field [{field}] not present in request!"},
                status=400,
            )
    return None


# added in https://gitlab.ics.muni.cz/422328/dbprov - this is sub-function with validation extracted from store_graph function - refactor
def _validate_request(
    json_data, validator, document_id, organization_id, is_update, disable_tp
):
    # Validate organizations
    expected_json_fields = ("document", "documentFormat")
    if not disable_tp:
        try:
            check_organization_is_registered(organization_id)
        except (
            InvalidTrustedParty,
            UncheckedTrustedParty,
            OrganizationNotRegistered,
        ) as e:
            return JsonResponse({"error": str(e)}, status=404)
        expected_json_fields = ("document", "signature", "documentFormat", "createdOn")
        tp_url = controller.get_tp_url_by_organization(organization_id)
        resp = send_signature_verification_request(
            json_data.copy(), organization_id, tp_url
        )
        if not resp.ok:
            return JsonResponse(
                {
                    "error": "Unverifiable signature."
                    " Make sure to register your certificate with trusted party first."
                },
                status=401,
            )

    # Validate payload keys
    if (
        missing_field_error := _validate_request_fields(json_data, expected_json_fields)
    ) is not None:
        return missing_field_error

    # firstly parse graph
    if (
        parse_error := _parse_input_graph(validator)
    ) is not None:  # fix - added is not none
        return parse_error

    # validate update conditions because of meta provenance
    if is_update:
        if (
            update_conditions_unmet_error := _validate_update_conditions(
                validator, document_id, organization_id
            )
        ) is not None:  # fix - added is not none
            return update_conditions_unmet_error
    else:
        if (
            new_document_conditions_unmet_error := _validate_new_document_conditions(
                validator, document_id
            )
        ) is not None:  # fix - added is not none
            return new_document_conditions_unmet_error

    # check whether same document does not exist yet
    if ((not is_update) and (
        duplicate_bundle_error := _validate_duplicate_bundle(
            validator, document_id, organization_id
        )
    ) is not None):  # fix - is not none missing
        return duplicate_bundle_error

    # validation
    try:
        validator.validate_graph()
    except (
        ConnectorReferenceInvalidError,
        HasNoBundles,
        TooManyBundles,
        DocumentError,
    ) as e:
        return JsonResponse({"error": str(e)}, status=400)

    return None


# added in https://gitlab.ics.muni.cz/422328/dbprov - sub-function used when validating request - refactor
def _validate_update_conditions(validator, document_id, organization_id):
    try:
        check_graph_id_belongs_to_meta(
            validator.get_meta_provenance_id(), document_id, organization_id
        )
        if not graph_exists(organization_id, document_id):
            return JsonResponse(
                {
                    "error": f"Document with id [{document_id}] does not exist."
                    "Please check whether the ID you have given is correct."
                },
                status=404,
            )
    except DoesNotExist:
        return JsonResponse(
            {
                "error": f"Document with id [{document_id}] does not "
                f"exist under organization [{organization_id}]."
            },
            status=404,
        )
    except DocumentError as e:
        return JsonResponse({"error": str(e)}, status=400)


# added in https://gitlab.ics.muni.cz/422328/dbprov - sub-function used when validating request - refactor
def _validate_new_document_conditions(validator, document_id):
    try:
        validator.check_ids_match(document_id)
    except DocumentError as e:
        return JsonResponse({"error": str(e)}, status=400)


# added in https://gitlab.ics.muni.cz/422328/dbprov - sub-function used when validating request - refactor
def _parse_input_graph(validator):
    try:
        validator.parse_graph()
    except DocumentError as e:
        return JsonResponse({"error": str(e)}, status=400)


# added in https://gitlab.ics.muni.cz/422328/dbprov - sub-function used when validating request - refactor
def _validate_duplicate_bundle(validator, document_id, organization_id):
    if graph_exists(organization_id, validator.get_bundle_id()):
        return JsonResponse(
            {
                "error": f"Document with id [{validator.get_bundle_id()}] already "
                f"exists under organization [{organization_id}]."
            },
            status=409,
        )


@csrf_exempt
@require_safe
def graph_meta(request, meta_id):
    if request.method == "HEAD":
        if controller.meta_bundle_exists(meta_id):
            return HttpResponse(200)
        else:
            return HttpResponseNotFound()  # fix - before,it returned 200

    requested_format = request.GET.get("format", "rdf").lower()
    organization_id = request.GET.get("organizationId", None)

    if requested_format not in ("rdf", "json", "xml", "provn"):
        return JsonResponse(
            {"error": f"Requested format [{requested_format}] is not supported!"},
            status=400,
        )

    try:
        g = controller.get_b64_encoded_meta_provenance(meta_id, requested_format)
    except DoesNotExist:
        return JsonResponse(
            {"error": f"The meta-provenance with id [{meta_id}] does not exist."},
            status=404,
        )

    if not config.disable_tp:
        if organization_id is not None:
            tp_url = controller.get_tp_url_by_organization(organization_id)
        else:
            tp_url = None

        payload = {
            "document": g,
            "createdOn": int(datetime.datetime.now().timestamp()),
            "type": "meta",
            "organizationId": config.id,
            "documentFormat": requested_format,
            "graphId": meta_id,
        }
        t = controller.send_token_request_to_tp(payload, tp_url)
        response = {"graph": g, "token": t}
    else:
        response = {"graph": g}

    return JsonResponse(response)


@csrf_exempt
@require_GET
def graph_domain_specific(request, organization_id, document_id):
    return get_subgraph(request, organization_id, document_id, True)


@csrf_exempt
@require_GET
def graph_backbone(request, organization_id, document_id):
    return get_subgraph(request, organization_id, document_id, False)


def get_subgraph(request, organization_id, document_id, is_domain_specific):
    requested_format = request.GET.get("format", "rdf")

    if requested_format not in ("rdf", "json", "xml", "provn"):
        return JsonResponse(
            {"error": f"Requested format [{requested_format}] is not supported!"},
            status=400,
        )

    try:
        g, t = controller.query_db_for_subgraph(
            organization_id, document_id, requested_format, is_domain_specific
        )
    except DoesNotExist:
        try:
            g = controller.get_b64_encoded_subgraph(
                organization_id, document_id, is_domain_specific, requested_format
            )

            if not config.disable_tp:
                tp_url = controller.get_tp_url_by_organization(organization_id)

                payload = {
                    "document": g,
                    "createdOn": int(datetime.datetime.now().timestamp()),
                    "type": "domain_specific" if is_domain_specific else "backbone",
                    "organizationId": organization_id,
                    "documentFormat": requested_format,
                    "graphId": document_id,
                    "doc_format": requested_format,
                }
                t = controller.send_token_request_to_tp(payload, tp_url)
            else:
                t = None

            suffix = "domain" if is_domain_specific else "backbone"
            controller.store_subgraph_into_db(
                f"{organization_id}_{document_id}_{suffix}", requested_format, g, t
            )
        except DoesNotExist:
            return JsonResponse(
                {
                    "error": f"Document with id [{document_id}] does not "
                    f"exist under organization [{organization_id}]."
                },
                status=404,
            )

    if not config.disable_tp:
        response = {"document": g, "token": t}
    else:
        response = {"document": g}

    return JsonResponse(response)

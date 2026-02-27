from datetime import datetime
import prov.model as provm
import prov.constants as provc
from neomodel import db

from distributed_prov_system.settings import config
from .models import Bundle, Entity, Document, Activity, Agent
from neomodel.exceptions import DoesNotExist
import neomodel

from .constants import (
    PAV_VERSION,
    CPM_TOKEN_GENERATION,
    CPM_TOKEN,
    CPM_TRUSTED_PARTY,
    CPM_TRUSTED_PARTY_URI,
    CPM_TRUSTED_PARTY_CERTIFICATE,
    CPM,
)

from prov.constants import PROV_TYPE, PROV_ATTR_BUNDLE


def import_graph(
    document: provm.ProvDocument,
    json_data,
    token,
    meta_id,
    document_id,
    is_update=False,
):
    assert len(document.bundles) == 1, "Only one bundle expected per document"
    signature = token["signature"]
    token = token["data"]
    token["signature"] = signature
    organization_id = token["originatorId"]

    for bundle in document.bundles:  # not needed - only 1 bundle in document
        identifier = f"{organization_id}_{bundle.identifier.localpart}"

        neo_document = Document()
        neo_document.identifier = identifier
        neo_document.graph = json_data["document"]
        neo_document.format = json_data["documentFormat"]

        # change - utilizing different functions for creation of data and different for saving, transactions added
        # logic not changed
        if is_update:
            (gen_entity, latest_entity, meta_bundle, new_version, token) = (
                update_meta_prov(identifier, meta_id, token, document_id)
            )
            (activity, agent, new_agent, e, entity, meta_bundle) = (
                store_token_into_meta(meta_bundle, new_version, token)
            )

            with db.transaction:
                neo_document.save()
                update_meta_prov_save(
                    gen_entity, latest_entity, meta_bundle, new_version
                )
                if new_agent:
                    save_new_agent(agent, meta_bundle)
                store_token_into_meta_save(activity, agent, e, entity, meta_bundle)
        else:
            meta_bundle_new = False
            try:
                meta_bundle = Bundle.nodes.get(identifier=meta_id)
            except DoesNotExist:
                meta_bundle = Bundle()
                meta_bundle.identifier = meta_id
                meta_bundle_new = True

            (first_version, gen_entity) = create_gen_entity_and_first_version(
                identifier
            )
            (activity, agent, new_agent, e, entity, meta_bundle) = (
                store_token_into_meta(meta_bundle, first_version, token)
            )

            with db.transaction:
                neo_document.save()
                if meta_bundle_new:
                    meta_bundle.save()
                save_version_to_db(first_version, gen_entity, meta_bundle)
                if new_agent:
                    save_new_agent(agent, meta_bundle)
                store_token_into_meta_save(activity, agent, e, entity, meta_bundle)


def create_gen_entity_and_first_version(new_entity_id):
    gen_entity = Entity()
    # added in https://gitlab.ics.muni.cz/422328/dbprov - different creation of general entity id
    gen_entity.identifier = "_".join(new_entity_id.rsplit("_", 2)[::2]) + "_gen"
    gen_entity.attributes = {str(PROV_TYPE): str(PROV_ATTR_BUNDLE)}
    first_version = Entity()
    first_version.identifier = new_entity_id
    first_version.attributes = {
        str(PROV_TYPE): str(PROV_ATTR_BUNDLE),
        str(PAV_VERSION): 1,
    }
    return first_version, gen_entity


# change - added function for saving
def save_version_to_db(first_version, gen_entity, meta_bundle):
    first_version.save()
    gen_entity.save()
    meta_bundle.contains.connect(gen_entity)
    meta_bundle.contains.connect(first_version)
    first_version.specialization_of.connect(gen_entity)


# change - use document id from request (as before) to retrieve the document to update from meta component
def update_meta_prov(updated_bundle_id, meta_bundle_id, token, graph_id):
    # Retrieve the meta bundle
    meta_bundle = Bundle.nodes.get(identifier=meta_bundle_id)

    # Retrieve entity representing component which is being updated in meta component
    entity_to_update = Entity.nodes.get(
        identifier=token["originatorId"] + "_" + graph_id
    )

    # Retrieve the generator entity
    gen_entities = list(entity_to_update.specialization_of.all())
    assert (
        len(gen_entities) == 1
    ), "Only one gen entity can be specified for version chain!"
    generator_entity = gen_entities[0]
    last_version = entity_to_update.attributes[str(PAV_VERSION)]

    # Create new updated entity
    new_version = Entity()
    new_version.identifier = f"{updated_bundle_id}"
    new_version.attributes = dict(entity_to_update.attributes)
    new_version.attributes[str(PAV_VERSION)] = last_version + 1

    return generator_entity, entity_to_update, meta_bundle, new_version, token


# change - added function for saving
def update_meta_prov_save(gen_entity, latest_entity, meta_bundle, new_version):
    new_version.save()
    meta_bundle.contains.connect(new_version)
    new_version.specialization_of.connect(gen_entity)
    new_version.was_revision_of.connect(latest_entity)


# change - refactored - read, prepare data and then later write in transactions
def store_token_into_meta(meta_bundle, entity, token):
    token_attributes = dict()
    for key, value in token.items():
        if key == "additionalData":
            for k, v in value.items():
                token_attributes[str(CPM[k])] = v
            continue

        token_attributes[str(CPM[key])] = value
    token_attributes[str(PROV_TYPE)] = str(CPM_TOKEN)

    e = Entity()
    e.identifier = f"{entity.identifier}_token"
    e.attributes = token_attributes

    (agent, new_agent) = get_tp_agent(meta_bundle, token)

    activity = Activity()
    activity.identifier = f"{entity.identifier}_tokenGeneration"
    activity.start_time = datetime.fromtimestamp(token["tokenTimestamp"])
    activity.end_time = activity.start_time
    activity.attributes = {str(PROV_TYPE): str(CPM_TOKEN_GENERATION)}

    return activity, agent, new_agent, e, entity, meta_bundle


# change - added function for saving
def store_token_into_meta_save(activity, agent, e, entity, meta_bundle):
    activity.save()
    e.save()
    meta_bundle.contains.connect(e)
    meta_bundle.contains.connect(activity)
    activity.used.connect(entity)
    activity.was_associated_with.connect(agent)
    e.was_generated_by.connect(activity)
    e.was_attributed_to.connect(agent)


def get_tp_agent(meta_bundle, token):
    authority_id = token["authorityId"]
    definition = dict(
        node_class=Agent,
        direction=neomodel.OUTGOING,
        relation_type="contains",
        model=None,
    )
    traversal = neomodel.Traversal(meta_bundle, Agent.__label__, definition)
    agent = None
    new_agent = False

    for a in traversal.all():
        if a.identifier == authority_id:
            agent = a
            break

    if agent is None:
        agent = Agent()
        agent.identifier = authority_id
        # fix - if disabled tp, dummy agent returned
        if config.disable_tp:
            attrs = {str(PROV_TYPE): str(CPM_TRUSTED_PARTY)}
        else:
            attrs = {
                str(PROV_TYPE): str(CPM_TRUSTED_PARTY),
                str(CPM_TRUSTED_PARTY_URI): token["additionalData"][
                    CPM_TRUSTED_PARTY_URI.localpart
                ],
            }
            if CPM_TRUSTED_PARTY_CERTIFICATE.localpart in token["additionalData"]:
                attrs.update(
                    {
                        str(CPM_TRUSTED_PARTY_CERTIFICATE): token["additionalData"][
                            CPM_TRUSTED_PARTY_CERTIFICATE.localpart
                        ]
                    }
                )
        agent.attributes = attrs
        new_agent = True

    return agent, new_agent


# change - added function for saving
def save_new_agent(agent, meta_bundle):
    agent.save()
    meta_bundle.contains.connect(agent)

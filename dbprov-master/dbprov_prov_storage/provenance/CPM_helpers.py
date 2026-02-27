# change - whole file added in this thesis

from prov import model as provm
from prov.constants import (
    PROV_ATTR_ACTIVITY,
    PROV_ATTR_AGENT,
    PROV_ATTR_ALTERNATE1,
    PROV_ATTR_ALTERNATE2,
    PROV_ATTR_COLLECTION,
    PROV_ATTR_DELEGATE,
    PROV_ATTR_ENDTIME,
    PROV_ATTR_ENTITY,
    PROV_ATTR_GENERAL_ENTITY,
    PROV_ATTR_GENERATED_ENTITY,
    PROV_ATTR_INFLUENCEE,
    PROV_ATTR_INFLUENCER,
    PROV_ATTR_INFORMANT,
    PROV_ATTR_INFORMED,
    PROV_ATTR_RESPONSIBLE,
    PROV_ATTR_SPECIFIC_ENTITY,
    PROV_ATTR_STARTTIME,
    PROV_ATTR_USED_ENTITY,
    PROV_TYPE,
)

from .constants import (
    CPM,
    CPM_BACKWARD_CONNECTOR,
    CPM_CURRENT_AGENT,
    CPM_FORWARD_CONNECTOR,
    CPM_MAIN_ACTIVITY,
    CPM_RECEIVER_AGENT,
    CPM_SENDER_AGENT,
    CPM_SPEC_FORWARD_CONNECTOR,
    DCT,
    DCT_HAS_PART,
)


def get_prov_generations_usages_attributions_agents(bundle):
    """Gets generation, usage, attribution, sender agents etc from bundle

    :param bundle: Prov bundle
    :returns: generations, usages, attributions, sender_agents, receiver_agents, specializations, derivations, all_relations
    """
    generations = []
    usages = []
    attributions = []
    sender_agents = []
    receiver_agents = []
    all_relations = []
    specializations = []
    derivations = []
    for record in bundle.get_records():
        if isinstance(record, provm.ProvGeneration):
            generations.append(record)
        if isinstance(record, provm.ProvUsage):
            usages.append(record)
        if isinstance(record, provm.ProvAttribution):
            attributions.append(record)
        if isinstance(record, provm.ProvSpecialization):
            specializations.append(record)
        if isinstance(record, provm.ProvAgent):
            if CPM_SENDER_AGENT in record.get_attribute(PROV_TYPE):
                sender_agents.append(record)
            if CPM_RECEIVER_AGENT in record.get_attribute(PROV_TYPE):
                receiver_agents.append(record)
        if record.is_relation():
            all_relations.append(record)
        if isinstance(record, provm.ProvDerivation):
            derivations.append(record)
    return (
        generations,
        usages,
        attributions,
        sender_agents,
        receiver_agents,
        specializations,
        derivations,
        all_relations,
    )


def get_backbone_and_domain(graph, graph_format, is_backbone_strategy):
    """Gets generation, usage, attribution, sender agents etc from bundle

    :param graph: serialized provenance document
    :param graph_format: format of the document
    :param is_backbone_strategy: strategy for deciding whether is an element from traversal information or not
    :returns: bundle, document, records_bb, records_ds: bundle from graph, deserialized document, record from traversal information part, records from domain part
    """
    document = provm.ProvDocument.deserialize(content=graph, format=graph_format)
    bundle = None
    for b in document.bundles:
        bundle = b
    records = bundle.records
    records_ds = []  # domain specific
    records_bb_ids = []
    records_bb = []  # backbone
    elements = []
    relations = []

    for record in records:
        if (
            isinstance(record, provm.ProvActivity)
            or isinstance(record, provm.ProvEntity)
            or isinstance(record, provm.ProvAgent)
        ):
            elements.append(record)
        else:
            relations.append(record)

    for record in elements:
        if is_backbone_strategy.is_backbone_element(record, bundle):
            records_bb.append(record)
        else:
            records_ds.append(record)

    for x in records_bb:
        records_bb_ids.append(x.identifier)
    # add relations that include entities from domain - relationship belongs to bb when between  bb entities - check every type of relationship because of different attributes
    for relation in relations:
        if relation_belongs_to_bb(records_bb_ids, relation):
            records_bb.append(relation)
    for relation in relations:
        if relation not in records_bb:
            records_ds.append(relation)
    return bundle, document, records_bb, records_ds


def has_any_cpm_type(entity):
    """Check whether a record has cpm type defined

    :param entity: Prov entity
    :returns: bool: true if has cpm type otherwise false
    """
    for a, value in entity.attributes:
        if a == PROV_TYPE and value in (
            CPM_BACKWARD_CONNECTOR,
            CPM_FORWARD_CONNECTOR,
            CPM_SPEC_FORWARD_CONNECTOR,
            CPM_MAIN_ACTIVITY,
            CPM_RECEIVER_AGENT,
            CPM_SENDER_AGENT,
            CPM_CURRENT_AGENT,
        ):
            return True
    return False


def contains_non_backbone_attribute(entity):
    """Check whether a record has attribute not common in traversal information part

    :param entity: Prov entity
    :returns: bool: true if has attribute  not common in traversal information part otherwise false
    """
    for type, value in entity.attributes:
        # ignore time attributes - all activities have them
        if type in (PROV_ATTR_STARTTIME, PROV_ATTR_ENDTIME):
            continue
        if type == PROV_TYPE:
            if value.namespace != CPM:
                return True
            continue
        if type.namespace != CPM:
            if type.namespace == DCT and type == DCT_HAS_PART:
                continue
            return True
    return False


def relation_belongs_to_bb(records_bb_ids, relation):
    """Check whether a relation is between two records from traversal information part

    :param records_bb_ids: list of identifiers of records from traversal information part
    :param relation: Prov relation between some records
    :returns: bool: true if the relation is between the records form list otherwise false
    """
    if isinstance(relation, provm.ProvInfluence):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_INFLUENCEE))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_INFLUENCER)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvCommunication):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_INFORMED))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_INFORMANT)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvAlternate):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ALTERNATE1))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_ALTERNATE2)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvGeneration):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ENTITY))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvUsage):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_ENTITY))) in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvStart):
        if next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids:
            return True
    if isinstance(relation, provm.ProvEnd):
        if next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids:
            return True
    if isinstance(relation, provm.ProvInvalidation):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ENTITY))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvDerivation):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_GENERATED_ENTITY)))
            in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_USED_ENTITY)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvAttribution):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ENTITY))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_AGENT))) in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvAssociation):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_ACTIVITY))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_AGENT))) in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvDelegation):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_DELEGATE))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_RESPONSIBLE)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvSpecialization):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_SPECIFIC_ENTITY)))
            in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_GENERAL_ENTITY)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvMention):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_SPECIFIC_ENTITY)))
            in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_GENERAL_ENTITY)))
            in records_bb_ids
        ):
            return True
    if isinstance(relation, provm.ProvMembership):
        if (
            next(iter(relation.get_attribute(PROV_ATTR_COLLECTION))) in records_bb_ids
            and next(iter(relation.get_attribute(PROV_ATTR_ENTITY))) in records_bb_ids
        ):
            return True
    return False

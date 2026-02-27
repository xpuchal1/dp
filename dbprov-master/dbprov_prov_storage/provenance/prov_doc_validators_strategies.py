# change - whole file added in this thesis

from abc import ABC, abstractmethod

from prov.model import ProvDocument
from prov.serializers.provrdf import ProvRDFSerializer

from .prov_validators.prov_check.provconstraints import validate


class ProvValidator(ABC):

    @abstractmethod
    def is_valid(self, document: ProvDocument):
        """Check whether a document is valid according to PROV standard

        :param document: Prov document
        :returns: bool: true if the document is valid
        """
        pass


# create later in case of need for better validator which could normalize document
def _is_graph_normalized(document):
    """Check whether a document is in normal form according to PROV standard

    :param document: Prov document
    :returns: bool: true if the document is normalized
    """
    return True


class ProvValidatorExternal(ProvValidator):
    # validator in prov_validators/, taken from https://github.com/pgroth/prov-check/tree/master

    def is_valid(self, document: ProvDocument):
        # write ocument to file, because the external validator expects file containing the provenance on input
        with open("temp_doc_to_validate.rdf", "w+b") as rdf_file:
            serializer = ProvRDFSerializer(document=document)
            serializer.serialize(
                rdf_file, rdf_format="turtle"
            )  # serialization seems nok sometimes

        result = validate("temp_doc_to_validate.rdf")
        return result == "PASS"


class ProvValidatorWithNormalization(ProvValidator):
    # normalize, check by _is_graph_normalized

    def is_valid(self, document: ProvDocument):
        # check validity
        return True


class ProvValidatorWithCanonization(ProvValidator):
    def is_valid(self, document: ProvDocument):
        return True

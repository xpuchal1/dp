from django.urls import path

from . import views

urlpatterns = [
    path("organizations/<organization_id>", views.register, name="registration"),
    path(
        "organizations/<organization_id>/documents/<document_id>",
        views.document,
        name="graphs",
    ),
    path(
        "organizations/<organization_id>/documents/<document_id>/domain-specific",
        views.graph_domain_specific,
        name="domain_specific_part",
    ),
    path(
        "organizations/<organization_id>/documents/<document_id>/backbone",
        views.graph_backbone,
        name="backbone",
    ),
    path("documents/meta/<meta_id>", views.graph_meta, name="meta_prov"),
]

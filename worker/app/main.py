from math import isfinite

import igraph as ig
import leidenalg
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field


app = FastAPI(title="RAG Worker", version="0.1.0")

MAX_NODES = 100_000
MAX_EDGES = 500_000


class CommunityEdge(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source: str = Field(min_length=1, max_length=80)
    target: str = Field(min_length=1, max_length=80)
    weight: float = Field(default=1.0, gt=0)


class CommunityRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    nodes: list[str] = Field(max_length=MAX_NODES)
    edges: list[CommunityEdge] = Field(max_length=MAX_EDGES)
    seed: int = Field(default=42, ge=0)
    resolution: float = Field(default=1.0, gt=0, le=10)


class CommunityAssignment(BaseModel):
    node: str
    community: int


class CommunityResponse(BaseModel):
    algorithm: str
    version: str
    assignments: list[CommunityAssignment]


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"status": "UP", "service": "worker"}


@app.post(
    "/community-detection",
    response_model=CommunityResponse,
    tags=["graph"],
)
def community_detection(
    request: CommunityRequest,
) -> CommunityResponse:
    nodes = sorted(request.nodes)
    if len(nodes) != len(set(nodes)):
        raise HTTPException(422, "nodes must be unique")
    if not all(node and len(node) <= 80 for node in nodes):
        raise HTTPException(422, "node id is invalid")

    node_index = {node: index for index, node in enumerate(nodes)}
    edges: list[tuple[int, int]] = []
    weights: list[float] = []
    for edge in sorted(
        request.edges,
        key=lambda item: (item.source, item.target, item.weight),
    ):
        if (
            edge.source not in node_index
            or edge.target not in node_index
        ):
            raise HTTPException(422, "edge endpoint is unknown")
        if not isfinite(edge.weight):
            raise HTTPException(422, "edge weight must be finite")
        if edge.source == edge.target:
            continue
        edges.append(
            (node_index[edge.source], node_index[edge.target])
        )
        weights.append(edge.weight)

    if not nodes:
        membership: list[int] = []
    elif not edges:
        membership = list(range(len(nodes)))
    else:
        graph = ig.Graph(
            n=len(nodes),
            edges=edges,
            directed=False,
        )
        partition = leidenalg.find_partition(
            graph,
            leidenalg.RBConfigurationVertexPartition,
            weights=weights,
            resolution_parameter=request.resolution,
            seed=request.seed,
            n_iterations=-1,
        )
        membership = partition.membership

    canonical_ids: dict[int, int] = {}
    assignments: list[CommunityAssignment] = []
    for node, community in zip(nodes, membership, strict=True):
        if community not in canonical_ids:
            canonical_ids[community] = len(canonical_ids)
        assignments.append(
            CommunityAssignment(
                node=node,
                community=canonical_ids[community],
            )
        )
    return CommunityResponse(
        algorithm="leidenalg",
        version=leidenalg.version,
        assignments=assignments,
    )

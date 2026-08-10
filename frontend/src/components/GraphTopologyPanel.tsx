import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Link } from "react-router-dom";
import type cytoscape from "cytoscape";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { SourceLocation } from "./SourceLocation";
import { GraphEntityDetailPanel } from "./GraphEntityDetailPanel";
import type {
  GraphEntityDetail,
  GraphRelationship,
  GraphRootType,
  GraphSubgraph,
  GraphSubgraphEdge,
  GraphSubgraphNode,
} from "../types";

interface GraphTopologyPanelProps {
  generation: number;
  rootType: GraphRootType;
  rootId: string;
  hops: 1 | 2;
  onHopsChange: (hops: 1 | 2) => void;
  onBack: () => void;
}

const ENTITY_COLORS: Record<string, string> = {
  PERSON: "#dbeafe",
  ORGANIZATION: "#dcfce7",
  LOCATION: "#fef3c7",
  EVENT: "#fce7f3",
  CONCEPT: "#ede9fe",
};

export function toCytoscapeElements(
  graph: GraphSubgraph,
): cytoscape.ElementDefinition[] {
  return [
    ...graph.nodes.map((node) => ({
      group: "nodes" as const,
      data: {
        id: node.id,
        label: node.name,
        entityType: node.entityType,
        depth: node.depth,
        relationshipCount: node.relationshipCount,
        color: ENTITY_COLORS[node.entityType] ?? "#e2e8f0",
      },
      classes: [node.root ? "root" : "", node.communityKey !== null
        ? "community-member"
        : ""].filter(Boolean).join(" "),
    })),
    ...graph.edges.map((edge) => ({
      group: "edges" as const,
      data: {
        id: edge.id,
        source: edge.sourceEntityId,
        target: edge.targetEntityId,
        label: edge.relationshipType,
        evidenceCount: edge.evidenceCount,
      },
    })),
  ];
}

function nodeLabel(node: GraphSubgraphNode) {
  return `${node.name}，${node.entityType}，${node.mentionCount} 个原文提及，${node.relationshipCount} 条关系`;
}

function edgeLabel(
  edge: GraphSubgraphEdge,
  nodes: Map<string, GraphSubgraphNode>,
) {
  return `${nodes.get(edge.sourceEntityId)?.name ?? "未知实体"}，${edge.relationshipType}，${nodes.get(edge.targetEntityId)?.name ?? "未知实体"}，${edge.evidenceCount} 条证据`;
}

export function GraphTopologyPanel({
  generation,
  rootType,
  rootId,
  hops,
  onHopsChange,
  onBack,
}: GraphTopologyPanelProps) {
  const { expireSession } = useAuth();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const cytoscapeRef = useRef<cytoscape.Core | null>(null);
  const relationshipRequestRef = useRef<AbortController | null>(null);
  const entityRequestRef = useRef<AbortController | null>(null);
  const [graph, setGraph] = useState<GraphSubgraph | null>(null);
  const [entity, setEntity] = useState<GraphEntityDetail | null>(null);
  const [relationship, setRelationship] =
    useState<GraphRelationship | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [relationshipLoading, setRelationshipLoading] = useState(false);
  const [entityLoading, setEntityLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleError = useCallback((caught: unknown, fallback: string) => {
    if (caught instanceof ApiError && caught.status === 401) {
      expireSession();
      return;
    }
    setError(caught instanceof ApiError ? caught.message : fallback);
  }, [expireSession]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setGraph(null);
    setEntity(null);
    setRelationship(null);
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
    const params = new URLSearchParams({
      generation: String(generation),
      rootType,
      rootId,
      hops: String(rootType === "ENTITY" ? hops : 1),
    });
    apiRequest<GraphSubgraph>(
      `/api/v1/admin/graph/subgraph?${params.toString()}`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (!controller.signal.aborted) setGraph(result);
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          handleError(caught, "局部关系图加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [generation, handleError, hops, rootId, rootType]);

  const loadEntity = useCallback(async (entityId: string) => {
    entityRequestRef.current?.abort();
    const controller = new AbortController();
    entityRequestRef.current = controller;
    setEntityLoading(true);
    setError(null);
    try {
      const detail = await apiRequest<GraphEntityDetail>(
        `/api/v1/admin/graph/entities/${entityId}?generation=${generation}`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted) setEntity(detail);
    } catch (caught) {
      if (!controller.signal.aborted) {
        handleError(caught, "实体详情加载失败");
      }
    } finally {
      if (entityRequestRef.current === controller) {
        entityRequestRef.current = null;
        setEntityLoading(false);
      }
    }
  }, [generation, handleError]);

  const loadRelationship = useCallback(async (relationshipId: string) => {
    relationshipRequestRef.current?.abort();
    const controller = new AbortController();
    relationshipRequestRef.current = controller;
    setRelationshipLoading(true);
    setError(null);
    try {
      const detail = await apiRequest<GraphRelationship>(
        `/api/v1/admin/graph/relationships/${relationshipId}?generation=${generation}`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted) setRelationship(detail);
    } catch (caught) {
      if (!controller.signal.aborted) {
        handleError(caught, "关系证据加载失败");
      }
    } finally {
      if (relationshipRequestRef.current === controller) {
        relationshipRequestRef.current = null;
        setRelationshipLoading(false);
      }
    }
  }, [generation, handleError]);

  useEffect(() => () => {
    relationshipRequestRef.current?.abort();
    entityRequestRef.current?.abort();
  }, []);

  useEffect(() => {
    if (!graph || !containerRef.current || graph.edges.length === 0) {
      cytoscapeRef.current?.destroy();
      cytoscapeRef.current = null;
      return;
    }
    let disposed = false;
    let instance: cytoscape.Core | null = null;
    import("cytoscape").then(({ default: createCytoscape }) => {
      if (disposed || !containerRef.current) return;
      instance = createCytoscape({
        container: containerRef.current,
        elements: toCytoscapeElements(graph),
        minZoom: 0.35,
        maxZoom: 2.5,
        style: [
          {
            selector: "node",
            style: {
              "background-color": "data(color)",
              "border-color": "#94a3b8",
              "border-width": 2,
              color: "#172033",
              label: "data(label)",
              "font-size": 11,
              "font-weight": "bold",
              "text-wrap": "ellipsis",
              "text-max-width": "106px",
              "text-valign": "center",
              "text-halign": "center",
              height: 58,
              width: 118,
              shape: "round-rectangle",
            },
          },
          {
            selector: "node.community-member",
            style: { "border-style": "double", "border-width": 3 },
          },
          {
            selector: "node.root",
            style: { "border-color": "#2563eb", "border-width": 5 },
          },
          {
            selector: "node:selected",
            style: { "border-color": "#0f172a", "border-width": 5 },
          },
          {
            selector: "edge",
            style: {
              width: 2,
              "line-color": "#94a3b8",
              "target-arrow-color": "#64748b",
              "target-arrow-shape": "triangle",
              "curve-style": "bezier",
              label: "data(label)",
              color: "#475569",
              "font-size": 9,
              "text-background-color": "#ffffff",
              "text-background-opacity": 0.9,
              "text-background-padding": "2px",
              "text-rotation": "autorotate",
            },
          },
          {
            selector: "edge:selected",
            style: {
              width: 4,
              "line-color": "#2563eb",
              "target-arrow-color": "#2563eb",
            },
          },
        ],
        layout: {
          name: "concentric",
          concentric: (node) => graph.rootType === "ENTITY"
            ? 10 - Number(node.data("depth"))
            : Number(node.data("relationshipCount")),
          levelWidth: () => 1,
          minNodeSpacing: 42,
          padding: 42,
          animate: false,
          fit: true,
        },
      });
      cytoscapeRef.current = instance;
      instance.on("tap", "node", (event) => {
        const id = event.target.id();
        setSelectedNodeId(id);
        setSelectedEdgeId(null);
        setRelationship(null);
        void loadEntity(id);
      });
      instance.on("tap", "edge", (event) => {
        const id = event.target.id();
        setSelectedEdgeId(id);
        setSelectedNodeId(null);
        void loadRelationship(id);
      });
    }).catch((caught: unknown) => {
      if (!disposed) handleError(caught, "关系图渲染器加载失败");
    });
    return () => {
      disposed = true;
      instance?.destroy();
      if (cytoscapeRef.current === instance) cytoscapeRef.current = null;
    };
  }, [graph, handleError, loadEntity, loadRelationship]);

  const nodesById = useMemo(() => new Map(
    graph?.nodes.map((node) => [node.id, node]) ?? [],
  ), [graph]);

  function focusElement(id: string, kind: "node" | "edge") {
    const instance = cytoscapeRef.current;
    if (!instance) return;
    const element = instance.$id(id);
    instance.elements().unselect();
    element.select();
    instance.animate({ center: { eles: element }, duration: 220 });
    if (kind === "node") {
      setSelectedNodeId(id);
      setSelectedEdgeId(null);
      setRelationship(null);
      void loadEntity(id);
    } else {
      setSelectedEdgeId(id);
      setSelectedNodeId(null);
      setEntity(null);
      void loadRelationship(id);
    }
  }

  if (loading) {
    return (
      <div className="graph-topology-loading" aria-live="polite">
        <span className="spinner" aria-hidden="true" />
        正在核验权限并加载局部关系图
      </div>
    );
  }

  if (error && !graph) {
    return (
      <div className="table-state error-state" role="alert">
        <p>{error}</p>
        <button type="button" className="secondary-button" onClick={onBack}>
          返回列表
        </button>
      </div>
    );
  }

  if (!graph || graph.edges.length === 0) {
    return (
      <div className="graph-topology-empty">
        <strong>当前实体没有有效关系 Evidence</strong>
        <p>仅显示通过当前 Revision、ACL 与 Projection 复核的关系。</p>
        <button type="button" className="secondary-button" onClick={onBack}>
          返回列表
        </button>
      </div>
    );
  }

  return (
    <div className="graph-topology-panel">
      <div className="graph-topology-toolbar">
        <div>
          <strong>{graph.rootLabel}</strong>
          <span>
            {graph.nodes.length} 个实体 · {graph.edges.length} 条关系
            {graph.truncated ? " · 已按上限裁剪" : ""}
          </span>
        </div>
        <div className="graph-topology-actions">
          {rootType === "ENTITY" ? (
            <div className="segmented-control" aria-label="关系展开深度">
              <button
                type="button"
                className={hops === 1 ? "active" : ""}
                aria-pressed={hops === 1}
                onClick={() => onHopsChange(1)}
              >1 跳</button>
              <button
                type="button"
                className={hops === 2 ? "active" : ""}
                aria-pressed={hops === 2}
                onClick={() => onHopsChange(2)}
              >2 跳</button>
            </div>
          ) : null}
          <button
            type="button"
            className="secondary-button"
            onClick={() => cytoscapeRef.current?.fit(undefined, 42)}
          >适应画布</button>
          <button
            type="button"
            className="secondary-button"
            onClick={() => {
              cytoscapeRef.current?.reset();
              cytoscapeRef.current?.fit(undefined, 42);
            }}
          >重置视图</button>
          <button type="button" className="text-button" onClick={onBack}>
            返回列表
          </button>
        </div>
      </div>

      {error ? <p className="generation-message" role="status">{error}</p> : null}

      <div className="graph-topology-workspace">
        <div
          ref={containerRef}
          className="graph-topology-canvas"
          role="img"
          aria-label={`${graph.rootLabel} 的局部知识关系图。可使用旁边的节点与关系列表进行键盘操作。`}
        />

        <aside className="graph-topology-index" aria-label="关系图可访问列表">
          <section>
            <h3>节点</h3>
            <div className="graph-topology-list">
              {graph.nodes.map((node) => (
                <button
                  type="button"
                  key={node.id}
                  className={selectedNodeId === node.id ? "selected" : ""}
                  aria-pressed={selectedNodeId === node.id}
                  onClick={() => focusElement(node.id, "node")}
                >
                  <strong>{node.name}</strong>
                  <span>{node.entityType} · 深度 {node.depth}</span>
                  <small>{nodeLabel(node)}</small>
                </button>
              ))}
            </div>
          </section>
          <section>
            <h3>关系</h3>
            <div className="graph-topology-list">
              {graph.edges.map((edge) => (
                <button
                  type="button"
                  key={edge.id}
                  className={selectedEdgeId === edge.id ? "selected" : ""}
                  aria-pressed={selectedEdgeId === edge.id}
                  onClick={() => focusElement(edge.id, "edge")}
                >
                  <strong>{edge.relationshipType}</strong>
                  <span>{edgeLabel(edge, nodesById)}</span>
                </button>
              ))}
            </div>
          </section>
        </aside>
      </div>

      {relationshipLoading ? (
        <div className="inline-state">
          <span className="spinner" aria-hidden="true" />正在核验关系证据
        </div>
      ) : null}

      {entityLoading ? (
        <div className="inline-state">
          <span className="spinner" aria-hidden="true" />正在核验实体详情
        </div>
      ) : null}

      {entity ? (
        <GraphEntityDetailPanel
          detail={entity}
          onClose={() => {
            setEntity(null);
            setSelectedNodeId(null);
            cytoscapeRef.current?.elements().unselect();
          }}
        />
      ) : null}

      {relationship ? (
        <aside className="graph-relationship-drawer" aria-label="关系原文证据">
          <header>
            <div>
              <span>关系 Evidence</span>
              <h3>{relationship.sourceName} — {relationship.relationshipType} → {relationship.targetName}</h3>
              <p>{relationship.description || "暂无补充说明"}</p>
            </div>
            <button
              type="button"
              className="text-button"
              onClick={() => {
                setRelationship(null);
                setSelectedEdgeId(null);
                cytoscapeRef.current?.elements().unselect();
              }}
            >关闭</button>
          </header>
          <div className="graph-relationship-evidence-list">
            {relationship.evidence.map((evidence) => (
              <blockquote key={evidence.id}>
                {evidence.evidenceText}
                <footer>
                  {evidence.documentTitle} · Revision {evidence.revisionNumber} ·{" "}
                  <SourceLocation source={evidence} linkToSource={false} /> ·{" "}
                  <Link to={`/chunks/${evidence.childChunkId}`}>查看 Child</Link>
                </footer>
              </blockquote>
            ))}
          </div>
        </aside>
      ) : null}
    </div>
  );
}

import { useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'

export type GraphConfidence = 'VERIFIED' | 'SINGLE_SOURCE' | 'CONTRADICTED'

export interface GraphCanvasNode {
  id: number
  name: string
  pagerank: number
  community?: number
  inDegree: number
  outDegree: number
}

export interface GraphCanvasEdge {
  from: number
  to: number
  predicate: string
  label?: string
}

export interface GraphCanvasProps {
  nodes: GraphCanvasNode[]
  edges: GraphCanvasEdge[]
  confidence: Record<number, GraphConfidence>
  selectedId: number | null
  onSelect: (id: number | null) => void
}

// Trust colours below mirror src/styles/tokens.css (light theme):
//   --accent: #2f5fd0 (selection outline / highlighted edges)
//   --trust-verified: #1d5f8a (solid 2px border, full opacity)
//   --trust-missing: #5a5f6b (dashed 2px border, 0.55 opacity; used for SINGLE_SOURCE)
//   --trust-contradicted: #8a2f6b (solid 4px border as a heavy "double-look" band,
//     full opacity; cytoscape has no double border style for nodes, so a thick
//     solid band carries the weight instead of colour alone).
// Trust is never colour alone: border width/style and opacity differ per state,
// matching the rule documented in src/components/ui.tsx.

const MIN_NODE_SIZE = 24
const MAX_NODE_SIZE = 64
const DEFAULT_NODE_SIZE = 40

function sizeForPagerank(pagerank: number, min: number, max: number): number {
  if (max === min) return DEFAULT_NODE_SIZE
  const t = (pagerank - min) / (max - min)
  return MIN_NODE_SIZE + t * (MAX_NODE_SIZE - MIN_NODE_SIZE)
}

function edgeDisplayLabel(edge: GraphCanvasEdge): string {
  return edge.label && edge.label.length > 0 ? edge.label : edge.predicate
}

const STYLESHEET: cytoscape.StylesheetStyle[] = [
  {
    selector: 'node',
    style: {
      label: 'data(label)',
      width: 'data(size)',
      height: 'data(size)',
      'background-color': '#e8eefc',
      'border-color': '#2f5fd0',
      'border-width': 2,
      'border-style': 'solid',
      color: '#12151a',
      'font-size': 11,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'ellipsis',
      'text-max-width': '120px',
      opacity: 1,
    },
  },
  {
    selector: 'node[conf = "VERIFIED"]',
    style: {
      'border-width': 2,
      'border-style': 'solid',
      'border-color': '#1d5f8a',
      opacity: 1,
    },
  },
  {
    selector: 'node[conf = "SINGLE_SOURCE"]',
    style: {
      'border-width': 2,
      'border-style': 'dashed',
      'border-color': '#5a5f6b',
      opacity: 0.55,
    },
  },
  {
    selector: 'node[conf = "CONTRADICTED"]',
    style: {
      'border-width': 4,
      'border-style': 'solid',
      'border-color': '#8a2f6b',
      opacity: 1,
    },
  },
  {
    selector: 'node.selected',
    style: {
      'outline-width': 3,
      'outline-style': 'solid',
      'outline-color': '#2f5fd0',
      'outline-offset': 2,
      'outline-opacity': 1,
    },
  },
  {
    selector: 'edge',
    style: {
      label: 'data(label)',
      'font-size': 9,
      color: '#3d4451',
      'curve-style': 'bezier',
      width: 1.5,
      'line-color': '#c6ccd6',
      'target-arrow-shape': 'triangle',
      'target-arrow-color': '#c6ccd6',
      'text-rotation': 'autorotate',
      opacity: 1,
    },
  },
  {
    selector: 'edge.highlighted',
    style: {
      width: 2.5,
      'line-color': '#2f5fd0',
      'target-arrow-color': '#2f5fd0',
      opacity: 1,
    },
  },
  {
    selector: '.dimmed',
    style: {
      opacity: 0.25,
    },
  },
]

function runCoseLayout(cy: cytoscape.Core): void {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  cy.layout({
    name: 'cose',
    animate: !reduceMotion,
    animationDuration: 500,
    fit: true,
    padding: 30,
  }).run()
}

function applySelection(cy: cytoscape.Core, selectedId: number | null): void {
  cy.elements().removeClass('selected highlighted dimmed')
  if (selectedId === null) return
  const selected = cy.getElementById(String(selectedId))
  if (selected.length === 0) return
  selected.addClass('selected')
  selected.connectedEdges().addClass('highlighted')
  cy.elements().not(selected.closedNeighborhood()).addClass('dimmed')
}

export function GraphCanvas({ nodes, edges, confidence, selectedId, onSelect }: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const cyRef = useRef<cytoscape.Core | null>(null)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect
  const selectedIdRef = useRef<number | null>(selectedId)
  selectedIdRef.current = selectedId

  // Init once; element data and selection are applied by the effects below.
  useEffect(() => {
    const container = containerRef.current
    if (container === null) return
    const cy = cytoscape({
      container,
      elements: [],
      style: STYLESHEET,
      layout: { name: 'cose' },
      minZoom: 0.2,
      maxZoom: 2.5,
      userZoomingEnabled: true,
      userPanningEnabled: true,
      boxSelectionEnabled: false,
    })
    cyRef.current = cy

    /*
     * Size the renderer explicitly, now.
     *
     * Cytoscape does not size its own canvas stack until resize() runs, and
     * until then every layer sits at the HTML default of 300x150 no matter how
     * large the container is. Its injected stylesheet only sets `position` on
     * the wrapper and leaves the box entirely to script timing, and the
     * ResizeObserver below is not a reliable place to depend on for the *first*
     * size — it exists for later viewport changes, not for initialisation.
     *
     * Relying on it alone leaves the graph painting into a 300x150 patch inside
     * a much larger area: the layout still runs and `fit: true` still resolves,
     * but against the wrong viewport, and because the wrapper clips its
     * overflow the result is a blank-looking canvas rather than an obviously
     * wrong one. Resizing here makes the first paint correct by construction.
     */
    cy.resize()

    cy.on('tap', 'node', (event: cytoscape.EventObject) => {
      const numericId = event.target.data('numericId') as unknown
      if (typeof numericId === 'number') onSelectRef.current(numericId)
    })
    cy.on('tap', (event: cytoscape.EventObject) => {
      if (event.target === cy) onSelectRef.current(null)
    })

    const observer = new ResizeObserver(() => {
      cy.resize()
    })
    observer.observe(container)

    return () => {
      observer.disconnect()
      cy.destroy()
      cyRef.current = null
    }
  }, [])

  // Rebuild elements when the graph data (or trust states) change.
  useEffect(() => {
    const cy = cyRef.current
    if (cy === null) return
    if (nodes.length === 0) {
      cy.elements().remove()
      return
    }
    const ranks = nodes.map((node) => node.pagerank)
    const min = Math.min(...ranks)
    const max = Math.max(...ranks)
    const nodeElements: cytoscape.ElementDefinition[] = nodes.map((node) => ({
      data: {
        id: String(node.id),
        numericId: node.id,
        label: node.name,
        size: sizeForPagerank(node.pagerank, min, max),
        conf: confidence[node.id] ?? 'SINGLE_SOURCE',
        community: node.community,
        inDegree: node.inDegree,
        outDegree: node.outDegree,
      },
    }))
    const edgeElements: cytoscape.ElementDefinition[] = edges.map((edge, index) => ({
      data: {
        id: `e${index}:${edge.from}->${edge.to}`,
        source: String(edge.from),
        target: String(edge.to),
        label: edgeDisplayLabel(edge),
      },
    }))
    cy.elements().remove()
    cy.add([...nodeElements, ...edgeElements])
    runCoseLayout(cy)
    applySelection(cy, selectedIdRef.current)

    /*
     * Re-size after the layout settles.
     *
     * `fit: true` recomputes the zoom against whatever viewport the renderer
     * currently has, so the resize has to happen *after* the layout resolves or
     * the fit is computed against a stale box. Deferred by one frame because the
     * layout is asynchronous, and a synchronous resize here would run before it.
     */
    requestAnimationFrame(() => {
      const instance = cyRef.current
      if (instance === null) return
      instance.resize()
      // The instance can be torn down between the frame being requested and it
      // running — a fast route change to a different corpus re-runs this effect.
      if (!instance.destroyed()) instance.fit(undefined, 30)
    })
  }, [nodes, edges, confidence])

  // Restyle only when the selection changes (no relayout).
  useEffect(() => {
    const cy = cyRef.current
    if (cy === null) return
    applySelection(cy, selectedId)
  }, [selectedId])

  return (
    <div
      className="graph-canvas"
      role="application"
      aria-label={`Knowledge graph with ${nodes.length} nodes and ${edges.length} edges. Activate a node to inspect it.`}
      style={{ minHeight: '420px', position: 'relative' }}
    >
      <div ref={containerRef} style={{ width: '100%', height: '420px' }} />
      {nodes.length === 0 && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#6b7382',
          }}
        >
          No entities to display
        </div>
      )}
    </div>
  )
}

export default GraphCanvas

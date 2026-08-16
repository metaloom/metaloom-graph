// ---------------------------------------------------------------------------
// Dataset 04 - Supply chain
// ---------------------------------------------------------------------------
// Style : MERGE only, therefore IDEMPOTENT. Running this file twice must not
//         change a single count. Node identity is the business key in the
//         MERGE pattern; every other property is written with ON CREATE SET so
//         a re-run neither duplicates nor rewrites anything. Relationships are
//         MERGEd on the bare pattern (no inline properties) for the same
//         reason.
// Scope  : 136 nodes / 352 relationships
//   Supplier 12, Part 60, Product 20, Warehouse 8, Shipment 30, Category 6
//   SUPPLIES 72, CONTAINS 80, IN_CATEGORY 20, STORED_IN 60,
//   SHIPPED_FROM 30, SHIPPED_TO 30, CARRIES 60
//
// Generation rules
//   SUPPLIES     : part p <- supplier p % 12; parts with p % 5 = 0 get the
//                  second source (7p+1) % 12  -> 60 + 12 edges
//   CONTAINS     : product q -> parts (3q+k) % 60 for k in 0..3
//   IN_CATEGORY  : product q -> category q % 6
//   STORED_IN    : part p -> warehouse p % 8
//   SHIPPED_FROM : shipment s -> warehouse s % 8
//   SHIPPED_TO   : shipment s -> warehouse (s+3) % 8   (never the same one)
//   CARRIES      : shipment s -> parts (2s+k) % 60 for k in 0..1
// ---------------------------------------------------------------------------

// -- suppliers (12) ----------------------------------------------------------
UNWIND range(0, 11) AS sid
WITH sid, ['Germany', 'France', 'Italy', 'Poland', 'Czechia', 'Netherlands'] AS countries
MERGE (s:Supplier {supplierId: sid})
ON CREATE SET s.name    = 'Supplier ' + toString(sid),
              s.country = countries[sid % 6],
              s.rating  = 1 + (sid % 5),
              s.audited = (sid % 3) = 0;

// -- parts (60) --------------------------------------------------------------
UNWIND range(0, 59) AS pid
WITH pid, ['steel', 'aluminium', 'polymer', 'copper'] AS materials
MERGE (p:Part {partId: pid})
ON CREATE SET p.sku      = 'PRT-' + toString(1000 + pid),
              p.material = materials[pid % 4],
              p.weightG  = 25 + ((pid * 13) % 750),
              p.critical = (pid % 7) = 0;

// -- products (20) -----------------------------------------------------------
UNWIND range(0, 19) AS qid
MERGE (q:Product {productId: qid})
ON CREATE SET q.name       = 'Product ' + toString(qid),
              q.priceCents = 4900 + ((qid * 311) % 12000),
              q.launched   = 2020 + (qid % 5);

// -- categories (6) ----------------------------------------------------------
UNWIND [
  {idx: 0, name: 'Power Tools'},
  {idx: 1, name: 'Garden'},
  {idx: 2, name: 'Kitchen'},
  {idx: 3, name: 'Automotive'},
  {idx: 4, name: 'Lighting'},
  {idx: 5, name: 'Measurement'}
] AS cat
MERGE (c:Category {categoryId: cat.idx})
ON CREATE SET c.name = cat.name;

// -- warehouses (8) ----------------------------------------------------------
UNWIND range(0, 7) AS wid
WITH wid, ['Hamburg', 'Lyon', 'Milan', 'Gdansk', 'Brno', 'Rotterdam', 'Bremen', 'Lille'] AS cities
MERGE (w:Warehouse {warehouseId: wid})
ON CREATE SET w.city      = cities[wid],
              w.capacity  = 5000 + (wid * 1250),
              w.automated = (wid % 2) = 0;

// -- shipments (30) ----------------------------------------------------------
UNWIND range(0, 29) AS shid
MERGE (s:Shipment {shipmentId: shid})
ON CREATE SET s.reference = 'SHP-' + toString(20000 + shid),
              s.dispatched = '2025-' + toString(1 + (shid % 12)) + '-08',
              s.pallets   = 1 + (shid % 9);

// -- SUPPLIES: primary source (60) -------------------------------------------
MATCH (p:Part)
MATCH (s:Supplier {supplierId: p.partId % 12})
MERGE (s)-[r:SUPPLIES]->(p)
ON CREATE SET r.leadTimeDays = 3 + (p.partId % 21), r.primary = true;

// -- SUPPLIES: second source for every 5th part (12) -------------------------
MATCH (p:Part)
WHERE (p.partId % 5) = 0
MATCH (s:Supplier {supplierId: ((p.partId * 7) + 1) % 12})
MERGE (s)-[r:SUPPLIES]->(p)
ON CREATE SET r.leadTimeDays = 9 + (p.partId % 17), r.primary = false;

// -- CONTAINS (80) -----------------------------------------------------------
MATCH (q:Product)
UNWIND range(0, 3) AS k
MATCH (p:Part {partId: ((q.productId * 3) + k) % 60})
MERGE (q)-[r:CONTAINS]->(p)
ON CREATE SET r.quantity = 1 + ((q.productId + k) % 6);

// -- IN_CATEGORY (20) --------------------------------------------------------
MATCH (q:Product)
MATCH (c:Category {categoryId: q.productId % 6})
MERGE (q)-[:IN_CATEGORY]->(c);

// -- STORED_IN (60) ----------------------------------------------------------
MATCH (p:Part)
MATCH (w:Warehouse {warehouseId: p.partId % 8})
MERGE (p)-[r:STORED_IN]->(w)
ON CREATE SET r.stock = 40 + ((p.partId * 17) % 460);

// -- SHIPPED_FROM (30) -------------------------------------------------------
MATCH (s:Shipment)
MATCH (w:Warehouse {warehouseId: s.shipmentId % 8})
MERGE (s)-[:SHIPPED_FROM]->(w);

// -- SHIPPED_TO (30) ---------------------------------------------------------
MATCH (s:Shipment)
MATCH (w:Warehouse {warehouseId: (s.shipmentId + 3) % 8})
MERGE (s)-[:SHIPPED_TO]->(w);

// -- CARRIES (60) ------------------------------------------------------------
MATCH (s:Shipment)
UNWIND range(0, 1) AS k
MATCH (p:Part {partId: ((s.shipmentId * 2) + k) % 60})
MERGE (s)-[r:CARRIES]->(p)
ON CREATE SET r.units = 10 + ((s.shipmentId + k) % 40);

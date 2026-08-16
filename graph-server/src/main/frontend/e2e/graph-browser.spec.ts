import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The browser UI against a real server, a real Cypher engine and a real store.
 *
 * The graph is the one the shipped `--demo` flag generates — an air route network of 58 real airports, seeded by
 * `tools/demo-server.sh`, which is the same command a person uses to look at the UI. Every awkward case the browser
 * has to draw is in it because the domain has one: a hub carries two labels, a freight airline carries a label with a
 * space in it, `IN_CITY` is a lightweight edge with no record, two airlines fly the same pair, and one sightseeing
 * flight returns to the airport it left.
 *
 * The counts below are exact because the generator is seeded, so the same size always produces the same graph.
 */

const HUBS = 'MATCH (h:Hub) RETURN h';
/** Heathrow to three named airports — five lines, because one of the pairs is flown by two airlines. */
const FROM_HEATHROW = "MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport)\n"
  + "WHERE a.code = 'LHR' AND b.code IN ['CDG', 'AMS', 'DUB']\nRETURN a, r, b";
const LONDON = "MATCH (a:Airport)-[r:IN_CITY]->(c:City) WHERE c.name = 'London' RETURN a, r, c";

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Graph browser' })).toBeVisible();
});

/** Types a query, runs it, and waits for the server to have answered one way or the other. */
async function run(page: Page, query: string): Promise<void> {
  await page.getByTestId('query-input').fill(query);
  await page.getByTestId('run').click();
  await expect(page.getByTestId('run')).toHaveText('Run');
}

function nodes(page: Page): Locator {
  return page.locator('g.node');
}

/** Matches on the caption and on the title, so a long name truncated in the circle is still findable. */
function nodeNamed(page: Page, caption: string): Locator {
  return page.locator('g.node').filter({ hasText: caption }).first();
}

function links(page: Page): Locator {
  return page.locator('path.link');
}

/**
 * The click target of a line.
 *
 * A drawn line is 1.6 pixels wide, which is not something a user can reliably hit and not something a test can click
 * at all — Playwright aims at the middle of the bounding box, which for a diagonal line is usually beside it. So each
 * line has an invisible 16-pixel copy in its own layer, and that is what receives the click.
 */
function linkHits(page: Page): Locator {
  return page.locator('path.linkHit');
}

/** The computed fill of a node's circle, as the browser resolves it. */
async function fillOf(node: Locator): Promise<string> {
  return node.locator('circle').evaluate(element => getComputedStyle(element).fill);
}

async function swatchOf(locator: Locator): Promise<string> {
  return locator.evaluate(element => getComputedStyle(element).backgroundColor);
}

/** The node's transform once the force layout has stopped moving it. */
async function settled(node: Locator): Promise<string> {
  let previous = '';
  await expect.poll(async () => {
    const current = (await node.getAttribute('transform')) ?? '';
    const stable = current !== '' && current === previous;
    previous = current;
    return stable;
  }, { timeout: 20_000, intervals: [250] }).toBe(true);
  return previous;
}

// ------------------------------------------------------------------ the store, in the sidebar

test.describe('sidebar', () => {
  test('lists the labels and relationship types in the store, with counts', async ({ page }) => {
    // These counts come from a scan of the store, not from a query — the sidebar describes the store, and it has to
    // be right before a single query has run.
    const labels = page.getByTestId('labels');
    await expect(labels.locator('li[data-label="Airport"]')).toContainText('58');
    await expect(labels.locator('li[data-label="City"]')).toContainText('55');
    await expect(labels.locator('li[data-label="Country"]')).toContainText('39');
    // A hub is :Airport:Hub, and is counted under both.
    await expect(labels.locator('li[data-label="Hub"]')).toContainText('6');

    const types = page.getByTestId('relationshipTypes');
    await expect(types.locator('li[data-type="ROUTE_TO"]')).toContainText('361');
    // IN_CITY is a lightweight edge with no relationship record, so a count taken from the record store would miss
    // every one of these.
    await expect(types.locator('li[data-type="IN_CITY"]')).toContainText('58');
    await expect(types.locator('li[data-type="HUB_AT"]')).toContainText('9');

    // Every node in the demo graph is labelled, so the footnote about unlabelled ones must not appear at all.
    await expect(page.getByTestId('unlabelled')).toHaveCount(0);
  });

  test('the summary agrees with the list under it', async ({ page }) => {
    // It did not, once: the summary came from /api/stats, which counts relationship *records*, while the list counts
    // adjacency entries. With 113 lightweight edges in the demo graph that is 370 above a list adding up to 483, and
    // the reader is left to work out which number is lying.
    await expect(page.getByTestId('store-summary')).toContainText('159 nodes');
    await expect(page.getByTestId('store-summary')).toContainText('483 relationships');

    const counts = await page.locator('[data-testid="relationshipTypes"] .count').allTextContents();
    expect(counts.reduce((total, text) => total + Number(text.replace(/\D/g, '')), 0)).toBe(483);
  });

  test('clicking a label runs a query for it', async ({ page }) => {
    await page.getByTestId('labels').locator('li[data-label="Hub"] button').click();
    await expect(page.getByTestId('query-input')).toHaveValue(/MATCH \(n:Hub\)/);
    await expect(nodes(page)).toHaveCount(6);
  });

  test('clicking a relationship type runs a query for it', async ({ page }) => {
    await page.getByTestId('relationshipTypes').locator('li[data-type="HUB_AT"] button').click();
    await expect(page.getByTestId('query-input')).toHaveValue(/\[r:HUB_AT\]/);
    await expect(links(page)).toHaveCount(9);
  });

  test('a label that is not an identifier is quoted before it becomes a query', async ({ page }) => {
    // 'Cargo Carrier' has a space in it. Pasted into a query unquoted it is a syntax error, so the UI has to backtick
    // it — and this is the only place that rule is exercised.
    await page.getByTestId('labels').locator('li[data-label="Cargo Carrier"] button').click();
    await expect(page.getByTestId('query-input')).toHaveValue(/MATCH \(n:`Cargo Carrier`\)/);
    await expect(page.getByTestId('error')).toHaveCount(0);
    await expect(nodes(page)).toHaveCount(1);
    await expect(nodes(page)).toContainText('Beacon');
  });
});

// ------------------------------------------------------------------ declared indexes

/**
 * The one panel that changes the store's *schema* rather than reading it.
 *
 * There is no `CREATE INDEX` in this Cypher subset, so this panel is the only way to declare one without writing
 * Java — which makes it the only place these assertions can be made through the surface a user actually has.
 *
 * Every test here cleans up after itself through the API rather than the UI. The demo store is shared by the whole
 * file and outlives the run (`target/e2e-store`), so a declaration left behind would change what the next run sees.
 */
test.describe('indexes', () => {
  const PAIRS = [['Airport', 'code'], ['Airport', 'international']];

  test.afterEach(async ({ request }) => {
    for (const [label, key] of PAIRS) {
      await request.delete(`api/indexes/${label}/${key}`).catch(() => undefined);
    }
  });

  async function declare(page: Page, label: string, key: string, unique = false): Promise<void> {
    await page.getByTestId('new-index').click();
    await page.getByLabel('Index label').fill(label);
    await page.getByLabel('Index property key').fill(key);
    if (unique) {
      await page.getByRole('checkbox', { name: 'unique' }).check();
    }
    await page.getByTestId('create-index').click();
  }

  test('declares a composite index and lists it', async ({ page }) => {
    await declare(page, 'Airport', 'code');

    const row = page.getByTestId('indexes').locator('li[data-index="Airport.code"]');
    await expect(row).toContainText(':Airport.code');
    await expect(row.locator('.uniqueTag')).toHaveCount(0);
    await expect(page.getByTestId('index-error')).toHaveCount(0);
  });

  test('a unique declaration is enforced on the next write', async ({ page }) => {
    await declare(page, 'Airport', 'code', true);
    await expect(page.getByTestId('indexes').locator('li[data-index="Airport.code"] .uniqueTag'))
      .toHaveText('unique');

    // 'LHR' is already taken, so this is refused — which is why running a CREATE against the shared demo store here
    // is safe: a violation leaves nothing behind.
    await run(page, "CREATE (a:Airport {code: 'LHR', name: 'Duplicate'}) RETURN a");
    await expect(page.getByTestId('error')).toContainText(':Airport.code');
  });

  test('a unique declaration over data that already breaks it is refused, in the panel', async ({ page }) => {
    // Every airport is `international: true`, so this constraint has never been true and declaring it must fail
    // rather than record something untrue. The message names the node already holding the value.
    await declare(page, 'Airport', 'international', true);

    await expect(page.getByTestId('index-error')).toContainText('international');
    await expect(page.getByTestId('indexes').locator('li[data-index="Airport.international"]')).toHaveCount(0);
  });

  test('clicking an index writes a query that uses it', async ({ page }) => {
    await declare(page, 'Airport', 'code');
    await page.getByTestId('indexes').locator('li[data-index="Airport.code"] button').first().click();

    // Written, not run: the probe needs a value only the user has, and running it now would return nothing and read
    // as a broken index.
    await expect(page.getByTestId('query-input')).toHaveValue(/MATCH \(n:Airport \{code: 'value'\}\)/);
    await expect(nodes(page)).toHaveCount(0);
  });

  test('dropping it takes it out of the list', async ({ page }) => {
    await declare(page, 'Airport', 'code');
    page.once('dialog', dialog => void dialog.accept());
    await page.getByTestId('drop-Airport.code').click();

    await expect(page.getByTestId('indexes').locator('li[data-index="Airport.code"]')).toHaveCount(0);
    // And the store still answers the same question, by the slower path.
    await run(page, "MATCH (a:Airport {code: 'LHR'}) RETURN a");
    await expect(nodes(page)).toHaveCount(1);
  });
});

// ------------------------------------------------------------------ cypher

test.describe('cypher', () => {
  test('runs a query and reports what it drew', async ({ page }) => {
    await run(page, FROM_HEATHROW);
    await expect(page.getByTestId('status')).toHaveText(/5 rows in \d+ ms · 4 nodes, 5 relationships/);
    await expect(nodes(page)).toHaveCount(4);
    // Five lines over four nodes: one of these pairs is flown by two airlines.
    await expect(links(page)).toHaveCount(5);
  });

  test('a self-relationship is drawn as a loop rather than a line of no length', async ({ page }) => {
    // The sightseeing flight, which leaves and returns to the same airport.
    await run(page, 'MATCH (a:Airport)-[r:ROUTE_TO]->(a) RETURN a, r');
    await expect(nodes(page)).toHaveCount(1);
    await expect(links(page)).toHaveCount(1);
    expect(await links(page).first().getAttribute('d')).toContain('Q');
  });

  test('a syntax error is shown with the engine caret, not as a failed request', async ({ page }) => {
    await run(page, 'MATCH (a:Airport) WHERE a.runways > > 2 RETURN a');
    const error = page.getByTestId('error');
    await expect(error).toContainText('syntax');
    // The caret line is the part that says *where*, and it only means anything in a monospace block.
    await expect(error.locator('pre')).toContainText('^');
  });

  test('a write reports what it changed rather than zero rows', async ({ page }) => {
    // The demo server runs with --allow-write. A CREATE with no RETURN has no rows and no graph, so without the
    // summary the status line would read "0 rows · 0 nodes on the canvas" — true, and useless.
    await run(page, 'CREATE (:Ephemeral {marker: "e2e"})');
    await expect(page.getByTestId('status')).toContainText('1 node created');

    await run(page, 'MATCH (n:Ephemeral) DETACH DELETE n');
    await expect(page.getByTestId('status')).toContainText('1 node deleted');
  });

  test('a write the language refuses is shown as an error, not as a silent no-op', async ({ page }) => {
    await run(page, 'MATCH (a:Airport)-[:ROUTE_TO]->() WITH a LIMIT 1 DELETE a');
    await expect(page.getByTestId('error')).toContainText('DETACH DELETE');
  });

  test('a result with nothing renderable goes to the table', async ({ page }) => {
    // Otherwise the answer to `RETURN a.code` is an empty canvas, which looks like a failure.
    await run(page, 'MATCH (a:Airport) RETURN a.code AS code ORDER BY code LIMIT 4');
    await expect(page.getByTestId('tab-table')).toHaveClass(/active/);
    const rows = page.locator('table.results tbody tr');
    await expect(rows).toHaveCount(4);
    await expect(rows.first()).toContainText('AKL');
  });

  test('aggregation over the demo graph answers a real question', async ({ page }) => {
    // The point of the demo domain: the queries mean something. Heathrow is the busiest hub in this graph.
    await run(page, 'MATCH (h:Hub)-[:ROUTE_TO]->() RETURN h.code AS hub, count(*) AS routes ORDER BY routes DESC');
    const rows = page.locator('table.results tbody tr');
    await expect(rows).toHaveCount(6);
    await expect(rows.first()).toContainText('LHR');
    await expect(rows.first()).toContainText('46');
  });

  test('a 64-bit id is shown exactly, not rounded', async ({ page }) => {
    // A JavaScript number is a double, so an id near 2^60 loses its low bits. Ids cross as strings for this reason;
    // an id ending in a suspiciously round …000 here is that defect coming back.
    await run(page, 'MATCH (a:Airport) RETURN id(a) AS i ORDER BY i LIMIT 1');
    const text = (await page.locator('table.results tbody tr td').first().textContent())?.trim() ?? '';
    expect(text).toMatch(/^\d{16,}$/);
    expect(BigInt(text) > 1n << 53n).toBe(true);
  });
});

// ------------------------------------------------------------------ nodes

test.describe('nodes', () => {
  test('are drawn with a caption taken from a property', async ({ page }) => {
    await run(page, HUBS);
    await expect(nodes(page)).toHaveCount(6);
    for (const name of ['Heathrow', 'Dubai International', 'Sydney Kingsford Smith']) {
      await expect(nodeNamed(page, name)).toBeVisible();
    }
  });

  test('selecting one shows its labels and properties', async ({ page }) => {
    await run(page, HUBS);
    await nodeNamed(page, 'Heathrow').click();

    const inspector = page.getByTestId('inspector');
    // Heathrow is a hub, so it carries two labels — held inline in the node record.
    await expect(inspector.locator('.chip')).toHaveText(['Airport', 'Hub']);
    await expect(inspector.locator('tr[data-property="code"]')).toContainText('LHR');
    await expect(inspector.locator('tr[data-property="name"]')).toContainText('Heathrow');
    await expect(inspector.locator('tr[data-property="latitude"]')).toContainText('51.5');
    await expect(inspector.locator('tr[data-property="international"]')).toContainText('true');
    // The id is what a user pastes back into a query, so it is shown in full.
    await expect(inspector.locator('tr[data-property="id"] td')).toHaveText(/^\d{16,}$/);
  });

  test('the inspector walks to the other end of a relationship', async ({ page }) => {
    await run(page, FROM_HEATHROW);
    await nodeNamed(page, 'Heathrow').click();
    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.incident li')).toHaveCount(5);

    await inspector.locator('.incident li button').filter({ hasText: 'Schiphol' }).first().click();
    await expect(inspector.locator('tr[data-property="code"]')).toContainText('AMS');
  });
});

// ------------------------------------------------------------------ relationships

test.describe('relationships', () => {
  test('carry their type as a label on the line', async ({ page }) => {
    await run(page, LONDON);
    await expect(page.locator('text.linkLabel')).toHaveCount(2);
    await expect(page.locator('text.linkLabel').first()).toHaveText('IN_CITY');
  });

  test('selecting one shows its properties', async ({ page }) => {
    await run(page, "MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport) WHERE a.code = 'LHR' AND b.code = 'CDG' RETURN a, r, b");
    await linkHits(page).first().click();

    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.chip').first()).toHaveText('ROUTE_TO');
    // London to Paris is about 350 km, and the demo computes its distances from real coordinates.
    await expect(inspector.locator('tr[data-property="distanceKm"]')).toContainText('350');
    await expect(inspector.locator('tr[data-property="flightNumber"]')).toBeVisible();
    await expect(inspector.locator('.endpoints')).toContainText('Heathrow');
    await expect(inspector.locator('.endpoints')).toContainText('Charles de Gaulle');
  });

  test('a lightweight edge is drawn dashed and says why it has no properties', async ({ page }) => {
    // IN_CITY lives entirely in the adjacency list: no relationship record, so no id and nowhere to put a property.
    // An empty property table would look like data loss.
    await run(page, LONDON);
    await expect(links(page).first()).toHaveAttribute('stroke-dasharray', /\d/);
    await linkHits(page).first().click();

    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.chip')).toContainText(['IN_CITY', 'lightweight']);
    await expect(inspector).toContainText('no relationship record');
  });

  test('two relationships between the same pair are drawn apart', async ({ page }) => {
    // Heathrow to Frankfurt is flown by two airlines. Drawn as two straight lines they would be one line on screen,
    // and the user would see a graph that is missing an edge.
    await run(page, "MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport) WHERE a.code = 'LHR' AND b.code = 'FRA' RETURN a, r, b");
    await expect(links(page)).toHaveCount(2);
    const shapes = await links(page).evaluateAll(paths => paths.map(path => path.getAttribute('d') ?? ''));
    expect(shapes[0]).not.toBe(shapes[1]);
    expect(shapes.some(shape => shape.includes('Q'))).toBe(true);
  });

  test('a relationship whose far end was not returned is not drawn', async ({ page }) => {
    // D3 would otherwise invent a vertex for the missing end, putting a node on the canvas the query never returned.
    await run(page, "MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport) WHERE a.code = 'LHR' AND b.code = 'CDG' RETURN r, b");
    await expect(nodes(page)).toHaveCount(1);
    await expect(links(page)).toHaveCount(0);
  });
});

// ------------------------------------------------------------------ colours

test.describe('colours', () => {
  test('are per label, and the same in the sidebar as on the canvas', async ({ page }) => {
    await run(page, LONDON);

    const heathrow = await fillOf(nodeNamed(page, 'Heathrow'));
    const gatwick = await fillOf(nodeNamed(page, 'Gatwick'));
    const london = await fillOf(nodeNamed(page, 'London'));

    expect(heathrow).toBe(gatwick);
    expect(heathrow).not.toBe(london);

    // The legend swatch and the circle are coloured by two different components that share no state — only the hash
    // of the label name. If they ever disagree the legend is lying about the picture.
    expect(await swatchOf(page.locator('li[data-label="Airport"] .swatch'))).toBe(heathrow);
    expect(await swatchOf(page.locator('li[data-label="City"] .swatch'))).toBe(london);
  });

  test('a relationship line matches its type in the legend', async ({ page }) => {
    await run(page, FROM_HEATHROW);
    const stroke = await links(page).first().evaluate(element => getComputedStyle(element).stroke);
    expect(await swatchOf(page.locator('li[data-type="ROUTE_TO"] .swatch'))).toBe(stroke);
  });

  test('a node with two labels takes the colour of the first', async ({ page }) => {
    // Heathrow is :Airport:Hub. One circle can only be one colour, and the inspector shows the rest.
    await run(page, HUBS);
    const heathrow = await fillOf(nodeNamed(page, 'Heathrow'));
    expect(await swatchOf(page.locator('li[data-label="Airport"] .swatch'))).toBe(heathrow);
    expect(await swatchOf(page.locator('li[data-label="Hub"] .swatch'))).not.toBe(heathrow);
  });
});

// ------------------------------------------------------------------ replace and merge

test.describe('replace and merge', () => {
  test('replace shows only the latest result', async ({ page }) => {
    await run(page, HUBS);
    await expect(nodes(page)).toHaveCount(6);
    await run(page, LONDON);
    await expect(nodes(page)).toHaveCount(3);
  });

  test('merge adds to the canvas and keeps what was there', async ({ page }) => {
    await run(page, LONDON);
    await expect(nodes(page)).toHaveCount(3);

    await page.getByTestId('mode-merge').click();
    await run(page, HUBS);
    // Heathrow is in both results and must not be drawn twice: 3 + 6 - 1.
    await expect(nodes(page)).toHaveCount(8);
    await expect(nodeNamed(page, 'Gatwick')).toBeVisible();
    await expect(nodeNamed(page, 'Sydney Kingsford Smith')).toBeVisible();
  });

  test('merge joins up a relationship whose ends came from different queries', async ({ page }) => {
    // The server cannot draw a relationship whose far end is not in the same result, but it does send it. This is
    // what makes it possible to build a picture one query at a time — here, one flight at a time.
    await run(page, "MATCH (a:Airport) WHERE a.code = 'LHR' RETURN a");
    await expect(links(page)).toHaveCount(0);

    await page.getByTestId('mode-merge').click();
    await run(page, "MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport) WHERE a.code = 'LHR' AND b.code = 'CDG' RETURN r, b");
    await expect(nodes(page)).toHaveCount(2);
    await expect(links(page)).toHaveCount(1);
    await expect(page.locator('text.linkLabel').first()).toHaveText('ROUTE_TO');
  });

  test('a node seen twice stays where it is', async ({ page }) => {
    // The reason merge reuses node objects rather than rebuilding them: the layout would otherwise forget every
    // position and the whole picture would jump on each query.
    await run(page, HUBS);
    const heathrow = nodeNamed(page, 'Heathrow');
    await expect(heathrow).toBeVisible();
    // Both positions are read from a settled layout. Comparing against a moving one measures the simulation still
    // running, not the thing this test is about, and fails at whatever threshold is chosen.
    const before = await settled(heathrow);

    await page.getByTestId('mode-merge').click();
    await run(page, "MATCH (a:Airport) WHERE a.code = 'LHR' RETURN a");
    await expect(nodes(page)).toHaveCount(6);
    expect(distance(before, await settled(heathrow))).toBeLessThan(40);
  });

  test('hiding a node takes its relationships with it, and a later query brings them back', async ({ page }) => {
    // The canvas is a view, not the store: hiding is a UI action, and deliberately not a delete even though the
    // query editor beside it can now delete. Running the query again brings the node back.
    await run(page, FROM_HEATHROW);
    await expect(links(page)).toHaveCount(5);
    await nodeNamed(page, 'Heathrow').click();
    await page.getByTestId('hide-node').click();

    await expect(nodes(page)).toHaveCount(3);
    await expect(links(page)).toHaveCount(0);

    await page.getByTestId('mode-merge').click();
    await run(page, "MATCH (a:Airport) WHERE a.code = 'LHR' RETURN a");
    await expect(nodes(page)).toHaveCount(4);
    await expect(links(page)).toHaveCount(5);
  });

  test('clear empties the canvas', async ({ page }) => {
    await run(page, HUBS);
    await page.getByRole('button', { name: 'Clear', exact: true }).click();
    await expect(nodes(page)).toHaveCount(0);
    await expect(page.locator('.canvasEmpty')).toBeVisible();
  });
});

function distance(before: string | null, after: string | null): number {
  const parse = (value: string | null) => {
    const match = /translate\(([-\d.]+),([-\d.]+)\)/.exec(value ?? '');
    return match ? { x: Number(match[1]), y: Number(match[2]) } : null;
  };
  const a = parse(before);
  const b = parse(after);
  if (!a || !b) {
    throw new Error(`no transform to compare: ${before} / ${after}`);
  }
  return Math.hypot(a.x - b.x, a.y - b.y);
}

import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The browser UI against a real server, a real Cypher engine and a real store.
 *
 * The demo graph these assertions name is seeded by DemoServer: four people, two companies, two products, a node with
 * two labels, a node with none, a label that is not a bare identifier, two relationships between one pair, a
 * self-relationship, and three lightweight edges. Every awkward case the UI has to draw is in there on purpose.
 */

const PEOPLE = 'MATCH (n:Person) RETURN n';
const PEOPLE_AND_FRIENDS = 'MATCH (a:Person)-[r:KNOWS]->(b:Person) RETURN a, r, b';

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

/** The computed fill of a node's circle, as the browser resolves it — 'rgb(r, g, b)'. */
async function fillOf(node: Locator): Promise<string> {
  return node.locator('circle').evaluate(element => getComputedStyle(element).fill);
}

// ------------------------------------------------------------------ the store, in the sidebar

test.describe('sidebar', () => {
  test('lists the labels and relationship types in the store, with counts', async ({ page }) => {
    // These counts come from a scan of the store, not from the query — the sidebar describes the store, and it has to
    // be right before a single query has run.
    const labels = page.getByTestId('labels');
    await expect(labels.locator('li[data-label="Person"]')).toContainText('4');
    await expect(labels.locator('li[data-label="Company"]')).toContainText('2');
    await expect(labels.locator('li[data-label="Product"]')).toContainText('2');
    // A node with two labels is counted under both.
    await expect(labels.locator('li[data-label="Employee"]')).toContainText('1');

    const types = page.getByTestId('relationshipTypes');
    await expect(types.locator('li[data-type="KNOWS"]')).toContainText('4');
    await expect(types.locator('li[data-type="WORKS_AT"]')).toContainText('3');
    // Lightweight edges have no relationship record at all, and must still be counted.
    await expect(types.locator('li[data-type="BOUGHT"]')).toContainText('3');

    await expect(page.getByTestId('unlabelled')).toContainText('1 node carries no label');
  });

  test('clicking a label runs a query for it', async ({ page }) => {
    await page.getByTestId('labels').locator('li[data-label="Company"] button').click();
    await expect(page.getByTestId('query-input')).toHaveValue(/MATCH \(n:Company\)/);
    await expect(nodes(page)).toHaveCount(2);
  });

  test('a label that is not an identifier is quoted before it becomes a query', async ({ page }) => {
    // 'Legacy System' has a space in it. Pasted into a query unquoted it is a syntax error, so the UI has to
    // backtick it — and this is the only place that rule is exercised.
    await page.getByTestId('labels').locator('li[data-label="Legacy System"] button').click();
    await expect(page.getByTestId('query-input')).toHaveValue(/MATCH \(n:`Legacy System`\)/);
    await expect(page.getByTestId('error')).toHaveCount(0);
    await expect(nodes(page)).toHaveCount(1);
    await expect(nodes(page)).toContainText('Mainframe');
  });
});

// ------------------------------------------------------------------ cypher

test.describe('cypher', () => {
  test('runs a query and reports what it drew', async ({ page }) => {
    // Four KNOWS relationships over four people, one of which is Dave knowing himself.
    await run(page, PEOPLE_AND_FRIENDS);
    await expect(page.getByTestId('status')).toHaveText(/\d+ rows in \d+ ms · 4 nodes, 4 relationships/);
    await expect(nodes(page)).toHaveCount(4);
    await expect(links(page)).toHaveCount(4);
  });

  test('a self-relationship is drawn as a loop rather than a line of no length', async ({ page }) => {
    await run(page, 'MATCH (a:Person)-[r]->(a) RETURN a, r');
    await expect(nodes(page)).toHaveCount(1);
    await expect(links(page)).toHaveCount(1);
    const shape = await links(page).first().getAttribute('d');
    expect(shape).toContain('Q');
  });

  test('a syntax error is shown with the engine caret, not as a failed request', async ({ page }) => {
    await run(page, 'MATCH (n:Person) WHERE n.age > > 30 RETURN n');
    const error = page.getByTestId('error');
    await expect(error).toContainText('syntax');
    // The caret line is the part that says *where*, and it only means anything in a monospace block.
    await expect(error.locator('pre')).toContainText('^');
  });

  test('a write is refused as read-only', async ({ page }) => {
    await run(page, 'CREATE (n:Person {name: "Mallory"}) RETURN n');
    await expect(page.getByTestId('error')).toContainText('read-only');
  });

  test('a result with nothing renderable goes to the table', async ({ page }) => {
    // Otherwise the answer to `RETURN n.name` is an empty canvas, which looks like a failure.
    await run(page, 'MATCH (n:Person) RETURN n.name AS name ORDER BY name');
    await expect(page.getByTestId('tab-table')).toHaveClass(/active/);
    const rows = page.locator('table.results tbody tr');
    await expect(rows).toHaveCount(4);
    await expect(rows.first()).toContainText('Alice');
  });

  test('a 64-bit id is shown exactly, not rounded', async ({ page }) => {
    // A JavaScript number is a double, so an id near 2^60 loses its low bits. Ids cross as strings for this reason;
    // an id ending in a suspiciously round …000 here is that defect coming back.
    await run(page, 'MATCH (n:Person) RETURN id(n) AS i ORDER BY i LIMIT 1');
    const cell = page.locator('table.results tbody tr td').first();
    const text = (await cell.textContent())?.trim() ?? '';
    expect(text).toMatch(/^\d{16,}$/);
    expect(BigInt(text) > 1n << 53n).toBe(true);
  });
});

// ------------------------------------------------------------------ nodes

test.describe('nodes', () => {
  test('are drawn with a caption taken from a property', async ({ page }) => {
    await run(page, PEOPLE);
    await expect(nodes(page)).toHaveCount(4);
    for (const name of ['Alice', 'Bob', 'Carol', 'Dave']) {
      await expect(nodeNamed(page, name)).toBeVisible();
    }
  });

  test('selecting one shows its labels and properties', async ({ page }) => {
    await run(page, PEOPLE);
    await nodeNamed(page, 'Alice').click();

    const inspector = page.getByTestId('inspector');
    // Alice carries two labels, which the storage format holds inline in the node record.
    await expect(inspector.locator('.chip')).toHaveText(['Person', 'Employee']);
    await expect(inspector.locator('tr[data-property="name"]')).toContainText('Alice');
    await expect(inspector.locator('tr[data-property="age"]')).toContainText('34');
    await expect(inspector.locator('tr[data-property="city"]')).toContainText('Bonn');
    await expect(inspector.locator('tr[data-property="active"]')).toContainText('true');
    // The id is what a user pastes back into a query, so it is shown in full.
    await expect(inspector.locator('tr[data-property="id"] td')).toHaveText(/^\d{16,}$/);
  });

  test('a node with no label says so rather than showing nothing', async ({ page }) => {
    await run(page, 'MATCH (n) WHERE n.name = "Anonymous" RETURN n');
    await nodeNamed(page, 'Anonymous').click();
    await expect(page.getByTestId('inspector').locator('.chip')).toHaveText(['no label']);
  });

  test('the inspector walks to the other end of a relationship', async ({ page }) => {
    await run(page, PEOPLE_AND_FRIENDS);
    await nodeNamed(page, 'Alice').click();
    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.incident li')).toHaveCount(2);
    await inspector.locator('.incident li button').filter({ hasText: 'Bob' }).click();
    await expect(inspector.locator('tr[data-property="name"]')).toContainText('Bob');
  });
});

// ------------------------------------------------------------------ relationships

test.describe('relationships', () => {
  test('carry their type as a label on the line', async ({ page }) => {
    await run(page, 'MATCH (a:Person)-[r:WORKS_AT]->(c:Company) RETURN a, r, c');
    await expect(page.locator('text.linkLabel')).toHaveCount(3);
    await expect(page.locator('text.linkLabel').first()).toHaveText('WORKS_AT');
  });

  test('selecting one shows its properties', async ({ page }) => {
    await run(page, 'MATCH (a:Person)-[r:KNOWS]->(b:Person) WHERE a.name = "Alice" RETURN a, r, b');
    await linkHits(page).first().click();
    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.chip').first()).toHaveText('KNOWS');
    await expect(inspector.locator('tr[data-property="since"]')).toContainText('2019');
    await expect(inspector.locator('.endpoints')).toContainText('Alice');
    await expect(inspector.locator('.endpoints')).toContainText('Bob');
  });

  test('a lightweight edge is drawn dashed and says why it has no properties', async ({ page }) => {
    // It lives entirely in the adjacency list: no relationship record, so no id and nowhere to put a property. An
    // empty property table would look like data loss.
    await run(page, 'MATCH (a)-[r:BOUGHT]->(b) RETURN a, r, b');
    await expect(links(page).first()).toHaveAttribute('stroke-dasharray', /\d/);
    await linkHits(page).first().click();
    const inspector = page.getByTestId('inspector');
    await expect(inspector.locator('.chip')).toContainText(['BOUGHT', 'lightweight']);
    await expect(inspector).toContainText('no relationship record');
  });

  test('two relationships between the same pair are drawn apart', async ({ page }) => {
    // Alice KNOWS Bob and Alice MENTORS Bob. Drawn as two straight lines they would be one line on screen, and the
    // user would see a graph that is missing an edge.
    await run(page, 'MATCH (a:Person)-[r]->(b:Person) WHERE a.name = "Alice" AND b.name = "Bob" RETURN a, r, b');
    await expect(links(page)).toHaveCount(2);
    const shapes = await links(page).evaluateAll(paths => paths.map(path => path.getAttribute('d') ?? ''));
    expect(shapes[0]).not.toBe(shapes[1]);
    expect(shapes.some(shape => shape.includes('Q'))).toBe(true);
  });

  test('a relationship whose far end was not returned is not drawn', async ({ page }) => {
    // D3 would otherwise invent a vertex for the missing end, putting a node on the canvas the query never returned.
    await run(page, 'MATCH (a:Person)-[r:KNOWS]->(b) WHERE a.name = "Alice" RETURN a, r');
    await expect(nodes(page)).toHaveCount(1);
    await expect(links(page)).toHaveCount(0);
  });
});

// ------------------------------------------------------------------ colours

test.describe('colours', () => {
  test('are per label, and the same in the sidebar as on the canvas', async ({ page }) => {
    await run(page, 'MATCH (n) WHERE n:Person OR n:Product RETURN n');

    const alice = await fillOf(nodeNamed(page, 'Alice'));
    const bob = await fillOf(nodeNamed(page, 'Bob'));
    const widget = await fillOf(nodeNamed(page, 'Widget'));

    expect(alice).toBe(bob);
    expect(alice).not.toBe(widget);

    // The legend swatch and the circle are coloured by two different components that share no state — only the hash
    // of the label name. If they ever disagree the legend is lying about the picture.
    const swatch = page.locator('li[data-label="Person"] .swatch');
    expect(await swatch.evaluate(element => getComputedStyle(element).backgroundColor)).toBe(alice);
  });

  test('a relationship line matches its type in the legend', async ({ page }) => {
    await run(page, 'MATCH (a:Person)-[r:WORKS_AT]->(c) RETURN a, r, c');
    const stroke = await links(page).first().evaluate(element => getComputedStyle(element).stroke);
    const swatch = page.locator('li[data-type="WORKS_AT"] .swatch');
    expect(await swatch.evaluate(element => getComputedStyle(element).backgroundColor)).toBe(stroke);
  });

  test('a node with two labels takes the colour of the first', async ({ page }) => {
    await run(page, PEOPLE);
    const alice = await fillOf(nodeNamed(page, 'Alice'));
    const personSwatch = page.locator('li[data-label="Person"] .swatch');
    const employeeSwatch = page.locator('li[data-label="Employee"] .swatch');
    expect(await personSwatch.evaluate(element => getComputedStyle(element).backgroundColor)).toBe(alice);
    expect(await employeeSwatch.evaluate(element => getComputedStyle(element).backgroundColor)).not.toBe(alice);
  });
});

// ------------------------------------------------------------------ replace and merge

test.describe('replace and merge', () => {
  test('replace shows only the latest result', async ({ page }) => {
    await run(page, PEOPLE);
    await expect(nodes(page)).toHaveCount(4);
    await run(page, 'MATCH (n:Company) RETURN n');
    await expect(nodes(page)).toHaveCount(2);
  });

  test('merge adds to the canvas and keeps what was there', async ({ page }) => {
    await run(page, PEOPLE);
    await expect(nodes(page)).toHaveCount(4);

    await page.getByTestId('mode-merge').click();
    await run(page, 'MATCH (n:Company) RETURN n');
    await expect(nodes(page)).toHaveCount(6);
    await expect(nodeNamed(page, 'Alice')).toBeVisible();
    await expect(nodeNamed(page, 'Acme')).toBeVisible();
  });

  test('merge joins up a relationship whose ends came from different queries', async ({ page }) => {
    // The server cannot draw a relationship whose far end is not in the same result, but it does send it. This is
    // what makes it possible to build a picture one query at a time.
    await run(page, 'MATCH (a:Person) WHERE a.name = "Alice" RETURN a');
    await expect(links(page)).toHaveCount(0);

    await page.getByTestId('mode-merge').click();
    await run(page, 'MATCH (a:Person)-[r:KNOWS]->(b) WHERE a.name = "Alice" RETURN r, b');
    await expect(nodes(page)).toHaveCount(2);
    await expect(links(page)).toHaveCount(1);
    await expect(page.locator('text.linkLabel').first()).toHaveText('KNOWS');
  });

  test('a node seen twice stays where it is', async ({ page }) => {
    // The reason merge reuses node objects rather than rebuilding them: the layout would otherwise forget every
    // position and the whole picture would jump on each query.
    await run(page, PEOPLE);
    const alice = nodeNamed(page, 'Alice');
    await expect(alice).toBeVisible();
    const before = await alice.getAttribute('transform');

    await page.getByTestId('mode-merge').click();
    await run(page, 'MATCH (n:Person) WHERE n.name = "Alice" RETURN n');
    await expect(nodes(page)).toHaveCount(4);
    // The simulation is still nudging things, so this is about staying put, not about being pixel-identical.
    expect(distance(before, await alice.getAttribute('transform'))).toBeLessThan(80);
  });

  test('hiding a node takes its relationships with it, and a later query brings them back', async ({ page }) => {
    // The canvas is a view, not the store: hiding is a UI action and the read-only server could not delete anything
    // even if it were asked to.
    await run(page, PEOPLE_AND_FRIENDS);
    await expect(links(page)).toHaveCount(4);
    await nodeNamed(page, 'Bob').click();
    await page.getByTestId('hide-node').click();

    await expect(nodes(page)).toHaveCount(3);
    await expect(links(page)).toHaveCount(2, { timeout: 10_000 });

    await page.getByTestId('mode-merge').click();
    await run(page, 'MATCH (n:Person) WHERE n.name = "Bob" RETURN n');
    await expect(nodes(page)).toHaveCount(4);
    await expect(links(page)).toHaveCount(4);
  });

  test('clear empties the canvas', async ({ page }) => {
    await run(page, PEOPLE);
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

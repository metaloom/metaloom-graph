import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Captures the screenshots the website ships, against the same real server every other e2e test uses.
 *
 * Not part of `npm run e2e`: it is tagged `@screenshots` and run by `npm run screenshots`, because a normal test run
 * should assert things rather than write PNGs into the build directory.
 *
 *   npm run screenshots
 *   ../../../../../metaloom/website/import-graph-screenshots.sh
 *
 * <h3>No visual regression, deliberately</h3>
 *
 * `playwright.config.ts` already names the reason: the force layout is still moving when a test starts clicking. A
 * pixel comparison against a stored baseline would fail on simulation jitter and on nothing else, and the value it
 * would add — noticing that the UI changed — is exactly what a person reviewing the change already knows.
 *
 * So the simulation is stopped before each shot to make the *picture* stable, and nothing is compared. What is
 * checked, on the website side by `check-graph-screenshots.mjs`, is that every expected file exists, is not empty,
 * and is referenced with real alt text.
 */

// The project is an ES module ("type": "module"), so there is no __dirname to resolve against.
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../target/screenshots');

/** Each shot is scoped to a component rather than the page, so browser chrome cannot churn them. */
const VIEWPORT = { width: 1440, height: 900 };

const HUBS = 'MATCH (h:Hub)-[r:ROUTE_TO]->(b:Airport) RETURN h, r, b';

test.describe('@screenshots', () => {
	test.beforeAll(() => {
		mkdirSync(OUT, { recursive: true });
	});

	test.beforeEach(async ({ page }) => {
		await page.setViewportSize(VIEWPORT);
		await page.goto('/');
		await expect(page.getByRole('heading', { name: 'MetaLoom // Graph' })).toBeVisible();
	});

	test('the browser, whole', async ({ page }) => {
		await run(page, HUBS);
		await settle(page);
		await page.screenshot({ path: `${OUT}/browser.png` });
	});

	test('the query editor', async ({ page }) => {
		await run(page, HUBS);
		await page.getByTestId('query-input').screenshot({ path: `${OUT}/query-editor.png` });
	});

	test('the graph view', async ({ page }) => {
		await run(page, HUBS);
		await settle(page);
		await page.locator('.graphHost').screenshot({ path: `${OUT}/graph-view.png` });
	});

	test('the schema sidebar', async ({ page }) => {
		await expect(page.getByTestId('store-summary')).toBeVisible();
		await page.getByTestId('store-summary').screenshot({ path: `${OUT}/schema-sidebar.png` });
	});

	test('the inspector', async ({ page }) => {
		await run(page, HUBS);
		await settle(page);
		// Clicking a node is what opens it, and after settle() the node is where it will stay.
		await page.locator('.graph circle').first().click({ force: true });
		await expect(page.getByTestId('inspector')).toBeVisible();
		await page.getByTestId('inspector').screenshot({ path: `${OUT}/inspector.png` });
	});

	test('the result table', async ({ page }) => {
		await run(page, 'MATCH (a:Airport) RETURN a.code AS code, a.name AS name LIMIT 8');
		await page.getByTestId('tab-table').click();
		await page.locator('.view').screenshot({ path: `${OUT}/result-table.png` });
	});

	test('the index panel', async ({ page }) => {
		// The panel renders both an empty-state paragraph and an (empty) list, so a locator matching either is a
		// strict-mode violation rather than a choice. Wait on the panel itself.
		const panel = page.locator('.panel.indexes');
		await expect(panel).toBeVisible();

		// Declared through the UI first. A picture of the empty state would be a picture of the feature not being
		// used, which is not what the page is illustrating — and the demo server runs with --allow-write, so this is
		// the same path a reader would take.
		await page.getByTestId('new-index').click();
		await page.getByLabel('Index label').fill('Airport');
		await page.getByLabel('Index property key').fill('code');
		await page.getByTestId('create-index').click();
		await expect(panel.getByText('Airport')).toBeVisible();

		await panel.screenshot({ path: `${OUT}/index-panel.png` });
	});
});

async function run(page: Page, query: string): Promise<void> {
	await page.getByTestId('query-input').fill(query);
	await page.getByTestId('run').click();
	await expect(page.getByTestId('run')).toHaveText('Run');
}

/**
 * Stops the force simulation so the picture is the same one twice.
 *
 * d3-force keeps nudging nodes until alpha decays, which is what makes the layout readable and also what makes a
 * screenshot taken at an arbitrary instant differ from the last one. There is no hook for this from outside, so the
 * wait is on the simulation having had time to cool rather than on an event.
 */
async function settle(page: Page): Promise<void> {
	await expect(page.locator('.graph circle').first()).toBeVisible();
	await page.waitForTimeout(2500);
}

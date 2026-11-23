/* SPDX-License-Identifier: Apache-2.0 */
// End-to-end browser qualification of the *compiled TeaVM Java* WebMCP sample.
// Chrome native WebMCP disabled: this qualifies only the pinned official polyfill.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { copyFile, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const unzip = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '../../..');
const polyfill = join(dirname(fileURLToPath(import.meta.resolve('@w3c/webmcp-polyfill'))),
  'polyfill.js');

// The WAR is an output of the pinned TeaVM Gradle build, not a downloaded sample.
test('TeaVM-generated JavaScript registers, discovers, and executes page title through WebMCP',
  { timeout: 30_000 }, async () => {
    const wars = (await readdir(join(projectRoot, 'build', 'libs'))).filter(name => name.endsWith('.war'));
    assert.equal(wars.length, 1, 'expected exactly one generated TeaVM sample WAR');
    const root = await mkdtemp(join(tmpdir(), 'teavm-webmcp-'));
    let browser;
    let server;
    try {
      await unzip('unzip', ['-q', join(projectRoot, 'build', 'libs', wars[0]), '-d', root]);
      const generated = await readFile(join(root, 'js', 'webmcp.js'));
      assert.ok(generated.length > 100, 'generated TeaVM webmcp.js missing or empty in WAR');
      await copyFile(polyfill, join(root, 'webmcp-polyfill.js'));
      const htmlPath = join(root, 'index.html');
      let html = await readFile(htmlPath, 'utf8');
      const script = '<script src="js/webmcp.js"></script>';
      assert.equal(html.split(script).length, 2, 'TeaVM sample script was not packaged');
      html = html.replace(script,
        '<script src="webmcp-polyfill.js"></script>\n  ' + script);
      await writeFile(htmlPath, html);
      server = createServer(async (req, res) => {
        try {
          const url = new URL(req.url, 'http://127.0.0.1');
          const pathname = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
          const file = resolve(root, pathname.replace(/^\/+/, ''));
          if (!file.startsWith(root + sep)) {
            res.writeHead(403).end();
            return;
          }
          const bytes = await readFile(file);
          const mime = extname(file) === '.html' ? 'text/html; charset=utf-8'
            : extname(file) === '.js' ? 'application/javascript; charset=utf-8'
              : 'application/octet-stream';
          res.writeHead(200, { 'content-type': mime });
          res.end(bytes);
        } catch {
          res.writeHead(404).end();
        }
      });
      await new Promise((ok, fail) => {
        server.once('error', fail);
        server.listen(0, '127.0.0.1', ok);
      });
      const origin = `http://127.0.0.1:${server.address().port}`;
      browser = await chromium.launch({ headless: true, args: ['--disable-features=WebMCP'] });
      const page = await browser.newPage();
      const errors = [];
      const httpFailures = [];
      page.on('pageerror', error => errors.push(error.stack || error.message));
      page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
      page.on('response', response => { if (response.status() >= 400) httpFailures.push(`${response.status()} ${response.url()}`); });
      await page.goto(origin, { waitUntil: 'load' });
      assert.equal(await page.evaluate(() => globalThis.isSecureContext), true);
      try {
        await page.waitForFunction(() =>
          document.getElementById('status')?.textContent?.startsWith('m3.page_title result:'),
        null, { timeout: 12_000 });
      } catch (error) {
        const state = await page.evaluate(() => ({
          status: document.getElementById('status')?.textContent,
          main: typeof globalThis.main, modelContext: !!document.modelContext,
          secure: globalThis.isSecureContext,
          onload: document.body.getAttribute('onload'),
          scripts: [...document.scripts].map(script => script.src)
        }));
        throw new Error(`TeaVM browser runtime did not produce a tool result: ${JSON.stringify({ state, errors, httpFailures })}`, { cause: error });
      }
      const status = await page.locator('#status').innerText();
      const prefix = 'm3.page_title result: ';
      assert.ok(status.startsWith(prefix), status);
      assert.deepEqual(JSON.parse(status.slice(prefix.length)),
        { title: 'TeaVM WebMCP read-only fixture' });
      const names = await page.evaluate(async () =>
        (await document.modelContext.getTools()).map(tool => tool.name));
      assert.equal(names.filter(name => name === 'm3.page_title').length, 1);
      assert.deepEqual(errors, []);
      await page.close();
    } finally {
      await browser?.close();
      await new Promise(done => server?.close(done) ?? done());
      await rm(root, { recursive: true, force: true });
    }
  });

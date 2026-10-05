// Push the dailyweather local commit chain to Qianzln/daily-weather through the
// GitHub Git Data API (this environment's egress proxy blocks the git protocol to
// github.com:443, but api.github.com is reachable — same approach the wui project
// uses, but a fully independent copy: its own default repo, its own UA, and no
// dependency on wui's files).
//
// Differences from a plain `git push`:
//  - Replays the real commit chain and re-supplies each commit's exact
//    author/committer identity + timestamps, so every SHA is reproduced. Blobs are
//    read from the object database (`git cat-file blob`), never the working tree,
//    so line endings can't leak CRLF in transit.
//  - Handles an EMPTY target repo (no main yet): replays the full local chain and
//    CREATEs the main ref instead of fast-forward PATCHing it.
//  - Splits a single commit's tree into chunks of <=100 entries (the git/trees API
//    per-request cap) by building on the previous chunk's tree as base_tree.
//
// Env:
//   GH_TOKEN  (required) repo-scoped token; never printed, redacted from all output
//   GH_REPO   (optional, default Qianzln/daily-weather)
//   GH_FROM   (optional) base sha; defaults to remote main, or local chain root for a fresh repo
//   GH_TO     (optional) tip sha; defaults to local HEAD

import { execFileSync } from 'node:child_process';

const token = process.env.GH_TOKEN;
const repo = process.env.GH_REPO || 'Qianzln/dailyweather';
if (!token) {
  console.error('missing GH_TOKEN');
  process.exit(2);
}

const API = 'https://api.github.com';
const H = {
  'Authorization': `Bearer ${token}`,
  'Accept': 'application/vnd.github+json',
  'User-Agent': 'dailyweather-gh-push',
};
const redact = (s) => String(s).replaceAll(token, '***');

async function req(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: H,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) {
    console.error(`${method} ${url.replace(API, '')} -> ${res.status}: ${redact(text).slice(0, 400)}`);
    throw new Error(`http ${res.status}`);
  }
  return text ? JSON.parse(text) : {};
}

const GIT_BIN = process.env.GIT_BIN || 'git';
const gitBuf = (...args) => execFileSync(GIT_BIN, args, { maxBuffer: 1 << 28 });
const gitStr = (...args) => gitBuf(...args).toString('utf8');

function toIso(epoch, tz) {
  const sign = tz[0] === '-' ? -1 : 1;
  const offMin = sign * (Number(tz.slice(1, 3)) * 60 + Number(tz.slice(3, 5)));
  const local = new Date((Number(epoch) + offMin * 60) * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return (
    `${local.getUTCFullYear()}-${p(local.getUTCMonth() + 1)}-${p(local.getUTCDate())}` +
    `T${p(local.getUTCHours())}:${p(local.getUTCMinutes())}:${p(local.getUTCSeconds())}${tz.slice(0, 3)}:${tz.slice(3)}`
  );
}

function meta(sha) {
  const raw = gitStr('cat-file', 'commit', sha);
  const idx = raw.indexOf('\n\n');
  const head = raw.slice(0, idx);
  const message = raw.slice(idx + 2);
  const lines = head.split('\n');
  const tree = lines.find((l) => l.startsWith('tree ')).slice(5).trim();
  const parents = lines.filter((l) => l.startsWith('parent ')).map((l) => l.slice(7).trim());
  const parseId = (s) => {
    const m = s.match(/^(.*) <(.*)> (\d+) ([+-]\d{4})$/);
    if (!m) throw new Error(`cannot parse ident: ${s}`);
    return { name: m[1], email: m[2], date: toIso(m[3], m[4]) };
  };
  return {
    tree,
    parents,
    author: parseId(lines.find((l) => l.startsWith('author ')).slice(7)),
    committer: parseId(lines.find((l) => l.startsWith('committer ')).slice(10)),
    message,
  };
}

/** Files touched going from `parent` (or the repo root when parent is null) to `sha`. */
function changes(parent, sha) {
  const isRoot = !parent;
  const args = isRoot
    ? ['diff-tree', '--root', '-r', '--no-commit-id', '--name-status', sha]
    : ['diff-tree', '-r', '--no-commit-id', '--name-status', parent, sha];
  const out = gitStr(...args).trim();
  if (!out) return [];
  return out.split('\n').map((l) => {
    const parts = l.split('\t');
    return { status: parts[0][0], path: parts[parts.length - 1] };
  });
}

/** Build a tree, chunking entries to stay under the 100-entry / 4.75MB per-request cap. */
async function postTree(baseTree, items) {
  let cur = baseTree;
  for (let i = 0; i < items.length; i += 100) {
    const chunk = items.slice(i, i + 100);
    const r = await req('POST', `${API}/repos/${repo}/git/trees`, { base_tree: cur, tree: chunk });
    cur = r.sha;
  }
  return cur;
}

// --- resolve base --------------------------------------------------------------------
let remoteMain = null;
try {
  remoteMain = (await req('GET', `${API}/repos/${repo}/git/ref/heads/main`)).object?.sha ?? null;
} catch {
  remoteMain = null; // main does not exist yet (fresh or empty repo)
}

const toSha = (process.env.GH_TO || gitStr('rev-parse', 'HEAD').trim()).trim();
const forceAll = process.env.GH_FORCE_ALL === '1'; // replay full local chain + force main

let chain;
let baseTree;
let forceUpdate = false;
if (forceAll) {
  chain = gitStr('rev-list', '--reverse', toSha).trim().split('\n').filter(Boolean);
  baseTree = null;
  forceUpdate = true;
} else if (remoteMain) {
  const fromSha = (process.env.GH_FROM || remoteMain).trim();
  if (fromSha !== remoteMain) {
    console.error(`refusing: remote main (${remoteMain.slice(0, 8)}) != base (${fromSha.slice(0, 8)})`);
    process.exit(3);
  }
  try {
    execFileSync(GIT_BIN, ['merge-base', '--is-ancestor', fromSha, toSha], { stdio: 'ignore' });
  } catch {
    console.error(`refusing: ${fromSha.slice(0, 8)} is not an ancestor of ${toSha.slice(0, 8)}`);
    process.exit(4);
  }
  chain = gitStr('rev-list', '--reverse', `${fromSha}..${toSha}`).trim().split('\n').filter(Boolean);
  baseTree = (await req('GET', `${API}/repos/${repo}/git/commits/${fromSha}`)).tree.sha;
} else {
  // Fresh/empty repo: the git backend isn't initialised, so git Data API blobs 409 with
  // "Git Repository is empty". Bootstrap it with a throwaway Contents write (which also
  // creates main), then replay the FULL local chain and force-update main over the
  // bootstrap commit so main ends up exactly equal to the local chain.
  chain = gitStr('rev-list', '--reverse', toSha).trim().split('\n').filter(Boolean);
  baseTree = null;
  forceUpdate = true;
  try {
    // Omit `branch`: on a brand-new repo the default branch doesn't exist yet, so a
    // branch-scoped write 404s; a default-branch write is what initialises the git
    // backend (it creates main + the bootstrap commit).
    await req('POST', `${API}/repos/${repo}/contents/.dw-init`, {
      message: 'bootstrap git backend for dailyweather push',
      content: '',
    });
    console.log('bootstrapped git backend via Contents API');
  } catch (e) {
    // 409 "already exists" / "Git Repository is empty" cleared means git is now usable.
    if (!/empty|exists|409/i.test(String(e))) {
      console.error('bootstrap write failed: ' + e.message);
      process.exit(7);
    }
  }
}

console.log(`repo=${repo}  remote main=${remoteMain ? remoteMain.slice(0, 8) : '(fresh)'}  tip=${toSha.slice(0, 8)}  chain=${chain.length}`);
if (!chain.length) {
  console.error('empty chain — nothing to push');
  process.exit(5);
}

// --- replay --------------------------------------------------------------------------
let parentSha = null;
let curBaseTree = baseTree;
const blobCache = new Map();
let allMatch = true;

for (const sha of chain) {
  const m = meta(sha);
  const cs = changes(parentSha, sha); // parentSha null => this is the root commit
  const treeItems = [];
  for (const c of cs) {
    if (c.status === 'D') {
      treeItems.push({ path: c.path, mode: '100644', type: 'blob', sha: null });
      continue;
    }
    const blobSha = gitStr('rev-parse', `${sha}:${c.path}`).trim();
    let apiSha = blobCache.get(blobSha);
    if (!apiSha) {
      const content = gitBuf('cat-file', 'blob', blobSha);
      const r = await req('POST', `${API}/repos/${repo}/git/blobs`, {
        content: content.toString('base64'),
        encoding: 'base64',
      });
      apiSha = r.sha;
      if (apiSha !== blobSha) {
        console.warn(`  ! blob SHA mismatch for ${c.path}: local=${blobSha.slice(0, 8)} api=${apiSha.slice(0, 8)}`);
        allMatch = false;
      }
      blobCache.set(blobSha, apiSha);
    }
    const mode = gitStr('ls-tree', sha, '--', c.path).trim().split(/\s+/)[0] || '100644';
    treeItems.push({ path: c.path, mode, type: 'blob', sha: apiSha });
  }

  const treeSha = await postTree(curBaseTree, treeItems);
  if (treeSha !== m.tree) {
    console.warn(`  ! tree SHA mismatch for ${sha.slice(0, 8)}: local=${m.tree.slice(0, 8)} api=${treeSha.slice(0, 8)}`);
    allMatch = false;
  }

  const commitBody = {
    message: m.message,
    tree: treeSha,
    parents: parentSha ? [parentSha] : [],
    author: m.author,
    committer: m.committer,
  };
  const commit = await req('POST', `${API}/repos/${repo}/git/commits`, commitBody);
  const same = commit.sha === sha;
  if (!same) allMatch = false;
  const subject = gitStr('log', '-1', '--format=%s', sha).trim().slice(0, 44);
  console.log(`  ${sha.slice(0, 8)} -> ${commit.sha.slice(0, 8)} ${same ? 'SHA一致' : 'SHA不一致'}  files=${cs.length}  ${subject}`);

  parentSha = commit.sha;
  curBaseTree = treeSha;
}

if (!allMatch) {
  console.error('\nrefusing to move/create the ref: at least one SHA did not reproduce.');
  process.exit(6);
}

await req('PATCH', `${API}/repos/${repo}/git/refs/heads/main`, { sha: parentSha, force: forceUpdate });
console.log(`\n${forceUpdate ? 'force-updated' : 'updated'} main -> ${parentSha}`);
console.log(`LOCAL =${toSha}`);
console.log(`MATCH =${parentSha === toSha}`);

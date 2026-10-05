// Upload the dailyweather release APK to the Qianzln/dailyweather GitHub Releases
// through the API (github.com:443 is egress-blocked here; api.github.com / uploads
// are reachable). Independent of the wui project's release script.
//
// Idempotent: if the release for the tag exists it is reused; an asset with the same
// name is skipped on re-run.
//
// Env: GH_TOKEN, GH_REPO (default Qianzln/dailyweather), GH_TAG, GH_APK, GH_NOTES (optional file path)
import { readFileSync } from 'node:fs';

const token = process.env.GH_TOKEN;
const repo = process.env.GH_REPO || 'Qianzln/dailyweather';
const tag = process.env.GH_TAG;
const apkPath = process.env.GH_APK;
if (!token || !tag || !apkPath) {
  console.error('missing GH_TOKEN / GH_TAG / GH_APK');
  process.exit(2);
}

const API = 'https://api.github.com';
const UPLOADS = 'https://uploads.github.com';
const H = { Authorization: `Bearer ${token}`, 'User-Agent': 'dailyweather-release', 'Accept': 'application/vnd.github+json' };
const redact = (s) => String(s).replaceAll(token, '***');

async function req(method, url, body, headers) {
  const res = await fetch(url, { method, headers: { ...H, ...headers }, body });
  const text = await res.text();
  if (!res.ok) {
    console.error(`${method} ${url.replace(API, '').replace(UPLOADS, '')} -> ${res.status}: ${redact(text).slice(0, 400)}`);
    throw new Error(`http ${res.status}`);
  }
  return text ? JSON.parse(text) : {};
}

const body = process.env.GH_NOTES ? readFileSync(process.env.GH_NOTES, 'utf8').trim() : '';
const apk = readFileSync(apkPath);
// ASCII asset name keeps the download URL clean; Chinese name goes in the release instead.
const assetName = `daily-weather-${tag.replace(/^v/, '')}.apk`;
console.log(`repo=${repo} tag=${tag} asset=${assetName} bytes=${apk.length}`);

let release = null;
const probe = await fetch(`${API}/repos/${repo}/releases/tags/${tag}`, { headers: H });
if (probe.status === 200) {
  release = await probe.json();
  console.log(`release for ${tag} already exists (id=${release.id}) — reusing`);
} else if (probe.status !== 404) {
  console.error(`probe failed: ${probe.status}`);
  process.exit(3);
}

if (!release) {
  release = await req('POST', `${API}/repos/${repo}/releases`, JSON.stringify({
    tag_name: tag,
    name: `每日天气 v${tag.replace(/^v/, '')}`,
    body,
    draft: false,
    prerelease: false,
  }));
  console.log(`created release id=${release.id} tag=${release.tag_name}`);
}

const existing = (release.assets || []).find((a) => a.name === assetName);
if (existing) {
  console.log(`asset ${assetName} already present: state=${existing.state} size=${existing.size}`);
} else {
  const up = await req(
    'POST',
    `${UPLOADS}/repos/${repo}/releases/${release.id}/assets?name=${encodeURIComponent(assetName)}`,
    apk,
    { 'Content-Type': 'application/vnd.android.package-archive' },
  );
  console.log(`uploaded asset: state=${up.state} size=${up.size}`);
}

const after = await req('GET', `${API}/repos/${repo}/releases/tags/${tag}`);
console.log(`\nrelease ${after.tag_name}  html_url=${after.html_url}`);
for (const a of after.assets) {
  console.log(`  ${a.name}  ${a.size} B  ${a.browser_download_url}`);
}

/**
 * Generates the 16/48/128 px PNG icons with no image dependencies — a tiny
 * hand-rolled PNG encoder. Produces a rounded accent tile with a light "§"
 * glyph block in the centre. Run via `npm run icons`.
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'icons');

const BG = [108, 140, 255, 255]; // accent
const FG = [255, 255, 255, 255]; // glyph
const TRANSPARENT = [0, 0, 0, 0];

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(data.length);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
}

/** Draw a pixel grid: rounded tile + a simple § made of two filled bars. */
function pixel(x, y, size) {
  const r = size * 0.18; // corner radius
  const inset = (p) => p < r || p > size - r;
  // Rounded corners → transparent.
  if (inset(x) && inset(y)) {
    const cx = x < size / 2 ? r : size - r;
    const cy = y < size / 2 ? r : size - r;
    if ((x - cx) ** 2 + (y - cy) ** 2 > r * r) return TRANSPARENT;
  }
  // Glyph: a vertical bar + two horizontal serifs, evoking "§".
  const m = size * 0.34;
  const w = Math.max(1, size * 0.1);
  const inBarX = x > size / 2 - w / 2 && x < size / 2 + w / 2 && y > m && y < size - m;
  const inTop = y > m && y < m + w && x > m && x < size - m;
  const inBottom = y > size - m - w && y < size - m && x > m && x < size - m;
  if (inBarX || inTop || inBottom) return FG;
  return BG;
}

function encodePng(size) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  let pos = 0;
  for (let y = 0; y < size; y += 1) {
    raw[pos] = 0; // filter: none
    pos += 1;
    for (let x = 0; x < size; x += 1) {
      const [r, g, b, a] = pixel(x, y, size);
      raw[pos] = r;
      raw[pos + 1] = g;
      raw[pos + 2] = b;
      raw[pos + 3] = a;
      pos += 4;
    }
  }

  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type RGBA
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync(OUT_DIR, { recursive: true });
for (const size of [16, 48, 128]) {
  writeFileSync(resolve(OUT_DIR, `icon${size}.png`), encodePng(size));
  console.log(`wrote icon${size}.png`);
}

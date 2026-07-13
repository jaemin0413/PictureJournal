import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';

const signature = Buffer.from('89504e470d0a1a0a', 'hex');
const crcTable = Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
  return value >>> 0;
});
const crc32 = (value) => {
  let crc = 0xffffffff;
  for (const byte of value) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
};

export function inspectPngBytes(bytes) {
  if (bytes.length < 33 || !bytes.subarray(0, 8).equals(signature)) throw new Error('PNG signature is invalid.');

  let offset = 8;
  let ihdr;
  let sawIend = false;
  const idat = [];
  while (offset < bytes.length) {
    if (offset + 12 > bytes.length) throw new Error('PNG chunk header is truncated.');
    const length = bytes.readUInt32BE(offset);
    const type = bytes.subarray(offset + 4, offset + 8).toString('ascii');
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    if (dataEnd + 4 > bytes.length) throw new Error(`PNG ${type} chunk is truncated.`);
    const data = bytes.subarray(dataStart, dataEnd);
    const expectedCrc = bytes.readUInt32BE(dataEnd);
    if (crc32(bytes.subarray(offset + 4, dataEnd)) !== expectedCrc) throw new Error(`PNG ${type} CRC is invalid.`);
    if (type === 'IHDR') {
      if (ihdr || length !== 13) throw new Error('PNG IHDR is invalid.');
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        compression: data[10],
        filter: data[11],
        interlace: data[12],
      };
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      if (length !== 0) throw new Error('PNG IEND is invalid.');
      sawIend = true;
      offset = dataEnd + 4;
      break;
    }
    offset = dataEnd + 4;
  }
  if (!ihdr || !sawIend || offset !== bytes.length || idat.length === 0) throw new Error('PNG structure is incomplete.');
  if (ihdr.width < 2 || ihdr.height < 2) throw new Error('PNG dimensions are too small for UI evidence.');
  if (ihdr.bitDepth !== 8 || ihdr.compression !== 0 || ihdr.filter !== 0 || ihdr.interlace !== 0) {
    throw new Error('PNG uses an unsupported encoding for deterministic verification.');
  }

  const channels = new Map([[0, 1], [2, 3], [4, 2], [6, 4]]).get(ihdr.colorType);
  if (!channels) throw new Error(`PNG color type ${ihdr.colorType} is unsupported.`);
  const rowBytes = ihdr.width * channels;
  const inflated = inflateSync(Buffer.concat(idat));
  if (inflated.length !== (rowBytes + 1) * ihdr.height) throw new Error('PNG decoded byte length is invalid.');

  const decoded = Buffer.alloc(rowBytes * ihdr.height);
  const paeth = (a, b, c) => {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
  };
  for (let row = 0; row < ihdr.height; row += 1) {
    const sourceOffset = row * (rowBytes + 1);
    const filter = inflated[sourceOffset];
    if (filter > 4) throw new Error(`PNG row filter ${filter} is invalid.`);
    for (let column = 0; column < rowBytes; column += 1) {
      const raw = inflated[sourceOffset + 1 + column];
      const target = row * rowBytes + column;
      const left = column >= channels ? decoded[target - channels] : 0;
      const up = row > 0 ? decoded[target - rowBytes] : 0;
      const upperLeft = row > 0 && column >= channels ? decoded[target - rowBytes - channels] : 0;
      const predictor = filter === 0 ? 0
        : filter === 1 ? left
          : filter === 2 ? up
            : filter === 3 ? Math.floor((left + up) / 2)
              : paeth(left, up, upperLeft);
      decoded[target] = (raw + predictor) & 0xff;
    }
  }

  const visiblePixel = (pixelOffset) => {
    if (ihdr.colorType === 0) {
      const gray = decoded[pixelOffset];
      return Buffer.from([gray, gray, gray, 255]);
    }
    if (ihdr.colorType === 2) {
      return Buffer.from([decoded[pixelOffset], decoded[pixelOffset + 1], decoded[pixelOffset + 2], 255]);
    }
    const alpha = decoded[pixelOffset + channels - 1];
    const red = ihdr.colorType === 4 ? decoded[pixelOffset] : decoded[pixelOffset];
    const green = ihdr.colorType === 4 ? red : decoded[pixelOffset + 1];
    const blue = ihdr.colorType === 4 ? red : decoded[pixelOffset + 2];
    return Buffer.from([
      Math.round((red * alpha) / 255),
      Math.round((green * alpha) / 255),
      Math.round((blue * alpha) / 255),
      alpha,
    ]);
  };
  const firstPixel = visiblePixel(0);
  let nonUniform = false;
  for (let pixelOffset = channels; pixelOffset < decoded.length; pixelOffset += channels) {
    if (!visiblePixel(pixelOffset).equals(firstPixel)) {
      nonUniform = true;
      break;
    }
  }
  if (!nonUniform) throw new Error('PNG is visually uniform and does not prove a rendered UI.');
  return { status: 'passed', width: ihdr.width, height: ihdr.height, colorType: ihdr.colorType, nonUniform };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const path = process.argv[2];
  if (!path) throw new Error('Usage: node scripts/verify-png.mjs <png-path>');
  console.log(JSON.stringify({ path, ...inspectPngBytes(readFileSync(path)) }));
}

export const HEATMAP_COLOR_LUT = createHeatmapColorLut();

export function createNormalizationLut(bytes: Uint8Array, size: number) {
  let min = 255;
  let max = 0;
  const histogram = new Uint32Array(256);

  for (let index = 0; index < size; index += 1) {
    const value = bytes[index];

    if (value < min) {
      min = value;
    }
    if (value > max) {
      max = value;
    }
    histogram[value] += 1;
  }

  const normalized = new Uint8Array(256);

  if (max <= min) {
    return normalized;
  }

  let displayMax = max;
  const nonSaturatedCount = Math.max(0, size - histogram[min] - histogram[max]);
  if (nonSaturatedCount >= 32) {
    const targetRank = Math.max(1, Math.ceil(nonSaturatedCount * 0.995));
    let rank = 0;
    for (let value = min + 1; value < max; value += 1) {
      rank += histogram[value];
      if (rank >= targetRank) {
        displayMax = value;
        break;
      }
    }
  }
  displayMax = Math.max(min + 1, Math.min(max, displayMax));
  const range = displayMax - min;

  for (let value = min; value <= 255; value += 1) {
    const ratio = (value - min) / range;
    normalized[value] = Math.min(255, Math.round(ratio ** 0.8 * 255));
  }

  return normalized;
}

function createHeatmapColorLut() {
  const lut = new Uint8ClampedArray(256 * 4);

  for (let value = 0; value < 256; value += 1) {
    const ratio = value / 255;
    const color = jetHeatmapColor(ratio);
    const index = value * 4;

    lut[index] = color.r;
    lut[index + 1] = color.g;
    lut[index + 2] = color.b;
    lut[index + 3] = Math.round(95 + 110 * ratio);
  }

  return lut;
}

function jetHeatmapColor(ratio: number) {
  const stops = [
    { at: 0, r: 0, g: 0, b: 150 },
    { at: 0.2, r: 0, g: 95, b: 255 },
    { at: 0.42, r: 0, g: 255, b: 255 },
    { at: 0.62, r: 80, g: 255, b: 80 },
    { at: 0.78, r: 255, g: 235, b: 0 },
    { at: 0.9, r: 255, g: 120, b: 0 },
    { at: 1, r: 190, g: 0, b: 0 },
  ];

  const nextStopIndex = stops.findIndex((stop) => ratio <= stop.at);
  const nextStop = stops[Math.max(1, nextStopIndex === -1 ? stops.length - 1 : nextStopIndex)];
  const prevStop = stops[stops.indexOf(nextStop) - 1];
  const localRatio = (ratio - prevStop.at) / (nextStop.at - prevStop.at);

  return {
    r: Math.round(prevStop.r + (nextStop.r - prevStop.r) * localRatio),
    g: Math.round(prevStop.g + (nextStop.g - prevStop.g) * localRatio),
    b: Math.round(prevStop.b + (nextStop.b - prevStop.b) * localRatio),
  };
}

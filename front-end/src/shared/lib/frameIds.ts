export function compareFrameIds(left: string, right: string) {
  try {
    const leftId = BigInt(left);
    const rightId = BigInt(right);
    return leftId === rightId ? 0 : leftId > rightId ? 1 : -1;
  } catch {
    return left.localeCompare(right, undefined, { numeric: true });
  }
}

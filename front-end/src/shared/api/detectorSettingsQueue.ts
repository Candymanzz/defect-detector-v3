const pending = new Map<number, Promise<unknown>>();

export async function waitForDetectorSettingsWrites(cameraId: number) {
  await pending.get(cameraId)?.catch(() => undefined);
}

/** Serialize partial writes and profile resets, including across editor remounts. */
export function writeDetectorSettings<T>(cameraId: number, write: () => Promise<T>): Promise<T> {
  const previous = pending.get(cameraId) ?? Promise.resolve();
  const next = previous.catch(() => undefined).then(write);
  pending.set(cameraId, next);
  const cleanup = () => { if (pending.get(cameraId) === next) pending.delete(cameraId); };
  void next.then(cleanup, cleanup);
  return next;
}

import { useEffect, useState } from "react";
import { writeLocalStorage } from "../../shared/lib/lib";

export function usePersistedState(key, initialValue) {
  const [value, setValue] = useState(initialValue);

  useEffect(() => {
    writeLocalStorage(key, value);
  }, [key, value]);

  return [value, setValue];
}

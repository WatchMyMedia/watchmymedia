import { platform } from '../../platform/current'

/** This TV, as About and Account & devices name it: "Samsung QN65QN90D · Tizen 8.0"; "Samsung TV" in the browser preview. */
export function thisTvLine(): string {
  return platform.describe()
}

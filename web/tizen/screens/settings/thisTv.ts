import { tizenVersion, tvModel } from '../../app/platform'

/** This TV, as About and Account & devices name it: "Samsung QN65QN90D · Tizen 8.0"; "Samsung TV" in the browser preview. */
export function thisTvLine(): string {
  const version = tizenVersion()
  return [`Samsung ${tvModel() ?? 'TV'}`, version && `Tizen ${version}`].filter(Boolean).join(' · ')
}

import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { SecurityHub } from './security/Hub'
import { SignInProtectionFlow } from './security/SignInFlow'

/**
 * Settings › Security (PLAN-ADMIN-SETTINGS.md §5.6): the hub says where this
 * browser is and holds both protections; turning sign-in protection on is
 * a flow that ends by trying the check.
 */
export function SecuritySettings() {
  const page = useSettingsPage()
  if (page?.kind === 'flow') return <SignInProtectionFlow step={page.params.step} />
  return (
    <ServerOnly tab="security">
      <SecurityHub />
    </ServerOnly>
  )
}

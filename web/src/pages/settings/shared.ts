import { useAuth } from '../../authContext'
import type { AdminLimit } from '../../lib/adminAway'

export function useSettingsAccess(): { admin: boolean; adminLimit?: AdminLimit } {
  const auth = useAuth()
  const isAdmin = auth.user?.isAdmin ?? false
  return { admin: isAdmin && auth.adminAllowed, adminLimit: isAdmin && !auth.adminAllowed ? auth.adminLimit ?? 'network' : undefined }
}

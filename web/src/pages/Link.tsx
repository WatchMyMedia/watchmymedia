import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { linkRedirect } from '../lib/youSettings'

/**
 * /link, which a TV's QR code and the phone and tablet apps' "Open the link
 * page" open with ?code=…: Link a TV now lives in Settings
 * (PLAN-ADMIN-SETTINGS.md §5.10), so this goes to its second step, Check
 * it's yours, with the code in the address. Someone signed out signs in here
 * first, as before (the sign-in page says why), and arrives there after.
 */
export function LinkDevice({ code }: { code?: string }) {
  const navigate = useNavigate()
  useEffect(() => {
    void navigate({ href: linkRedirect(code), replace: true })
  }, [code, navigate])
  return null
}

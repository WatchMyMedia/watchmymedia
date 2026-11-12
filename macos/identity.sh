# Sourced by build-app.sh and make-dmg.sh. Picks the code-signing identity:
# CODESIGN_IDENTITY if set, else the first "Developer ID Application"
# certificate in the keychain, else "-" (ad hoc: runs locally, but
# Gatekeeper asks for a right-click Open and nothing can be notarised).
codesign_identity() {
  if [ -n "${CODESIGN_IDENTITY:-}" ]; then
    echo "$CODESIGN_IDENTITY"
    return
  fi
  security find-identity -v -p codesigning 2>/dev/null \
    | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1 | grep . || echo "-"
}

# sign <path>: hardened runtime and a timestamp with a real identity; ad hoc
# signing takes neither. CODESIGN_KEYCHAIN, when set, is the only keychain
# searched for the identity (CI's throwaway keychain), so signing does not
# depend on the user's keychain search list.
sign() {
  if [ "$IDENTITY" = "-" ]; then
    codesign --force --sign - "$@"
  else
    codesign --force --sign "$IDENTITY" ${CODESIGN_KEYCHAIN:+--keychain "$CODESIGN_KEYCHAIN"} --options runtime --timestamp "$@"
  fi
}

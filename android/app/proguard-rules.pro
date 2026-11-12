# R8 keep rules for release builds (app/build.gradle.kts), beyond the rules the libraries ship.
# Keep only what is loaded by name, saying why: blanket keeps would undo the obfuscation
# Play asks for. Check a release build on an emulator after changing them (android/AGENTS.md).

# The Cast SDK and Cast Connect create these by reflection from the class names in
# AndroidManifest.xml's <meta-data> (OPTIONS_PROVIDER_CLASS_NAME and
# RECEIVER_OPTIONS_PROVIDER_CLASS_NAME), which R8 does not read. Their own rules keep the
# classes' names, but in R8's full mode a rule with no members keeps no constructor, and the
# SDKs call the no-argument one.
-keep class media.watchmy.client.CastOptionsProvider { public <init>(); }
-keep class media.watchmy.client.CastReceiverOptionsProvider { public <init>(); }

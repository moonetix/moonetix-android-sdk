# Moonetix Offerwall SDK - consumer R8/ProGuard rules.
# The SDK uses no reflection, no serialization and no JavaScript interfaces.
# The Activity is referenced from the merged manifest (AAPT2 keeps it too);
# the explicit rule below guards against custom manifest processing.
-keep class com.moonetix.offerwall.MoonetixActivity
# Keep readable error codes in host crash reports and logs.
-keepnames class com.moonetix.offerwall.MoonetixError$Code

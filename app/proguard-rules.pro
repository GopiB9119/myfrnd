# R8 code shrinking and resource shrinking are enabled for release builds.
# No application-specific keep rules are needed: the app uses no reflection, and components
# declared in the manifest are kept automatically.
# Keep each release's mapping.txt (the AAB embeds it for Play Console) to read crash stack traces.

<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# ImportTrimmer Changelog

## [1.0.1] - 2026-09-12

- Release closed files promptly after background import checks, reducing unnecessary memory use during long editing sessions.

## [1.0.0] - 2026-09-09

- Detect Java imports that transition from used to unused in open editors.
- Offer ask, automatic, and on-request cleanup modes with project-level settings.
- Use native IDE notifications with Remove, Keep, and Settings actions.
- Perform fresh semantic validation before each selective, undoable removal.
- Preserve unaffected imports, comments, formatting, and source text.
- Compatible with IntelliJ IDEA 2026.2.2.

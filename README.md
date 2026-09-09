# Import Trimmer

Import Trimmer is a local IntelliJ IDEA plugin that watches open Java editors. After it has reliably observed an import being used, it offers to remove that import if later semantic analysis finds it unused. Imports already unused at the first observation are left alone.

The plugin is Java-only. Its implementation is Kotlin, and its analyzer/edit-planner boundary is ready for another language adapter, but no Kotlin source cleanup or Kotlin IDE-plugin dependency is included.

## Compatibility

This build targets IntelliJ IDEA Ultimate 2026.2.2 exactly:

- Product version: `IU-2026.2.2`
- Build: `262.10315.125`
- Runtime/toolchain: Java 25
- Kotlin: 2.3.20
- IntelliJ Platform Gradle Plugin: 2.18.1
- Gradle wrapper: 9.6.1

The exact `since-build` and `until-build` are deliberate. Compatibility with later 2026.2 patches is not claimed until it is verified.

## Install

Install [ImportTrimmer-1.0.0-SNAPSHOT.zip](build/distributions/ImportTrimmer-1.0.0-SNAPSHOT.zip) through **Settings | Plugins | gear icon | Install Plugin from Disk**. Do not unpack the ZIP.

## Behavior

The default mode is **Ask before removing**. A standard IDEA notification balloon appears at the bottom-right without taking focus or covering edited code. **Remove** performs one selective, undoable command; **Keep**, closing the notification, navigation, or the configured timeout leaves the file unchanged.

Settings under **Settings | Editor | Import Trimmer**:

| Setting | Default | Range |
| --- | --- | --- |
| Enabled | Yes | On/off |
| Mode | Ask before removing | Ask, automatic, manual |
| Analysis debounce | 750 ms | 250-3000 ms |
| Notification timeout | 10 s | 3-60 s |

**Remove automatically** uses the same fresh semantic validation and exact edit plan without showing a notification. Undo restores the import and rebaselines tracking, so automatic mode does not immediately remove it again.

**Manual only** keeps transition history but produces no unsolicited UI or edits. Invoke **Review newly unused Java imports** through Find Action. The action is available in every mode and can revisit an eligible dismissed episode.

Disabling the plugin or changing its mode cancels pending work, closes the active notification, and establishes a new baseline. Automatic mode therefore does not process a prior backlog.

## Example

Initially:

```java
import java.util.Map;
import java.util.List;
import java.util.Set;

class Example {
    List<String> names;
    Set<String> tags;
}
```

After deleting `List<String> names;` and accepting the suggestion:

```java
import java.util.Map;
import java.util.Set;

class Example {
    Set<String> tags;
}
```

The initially unused `Map` import stays, and `Map` remains before `Set`.

## Preservation and conservative skips

The plugin deletes only the accepted PSI-derived occurrence ranges. It does not optimize, sort, reformat, add, shorten, collapse, or convert imports. Remaining import spelling, order, grouping, comments, package text, and class body stay unchanged. A trailing import comment becomes a comment line.

Analysis is deferred for syntax errors, uncommitted PSI, unresolved in-file Java references, unavailable indices, duplicate import ambiguity, module-import syntax, and comments embedded before an import semicolon. A missed suggestion is expected in these cases. If an import becomes used and unused entirely between successful debounced analyses, no transition is observed and no suggestion is made.

IDEA's Optimize Imports on the Fly, Actions on Save, commit optimization, formatters, and other plugins can independently modify imports. Import Trimmer does not change those settings and guarantees only its own edit.

## Development

Use JDK 25. On this machine the matching runtime is included with IDEA 2026.2.2:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\jbr'
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
```

The installable ZIP is produced at `build/distributions/ImportTrimmer-1.0.0-SNAPSHOT.zip`. Architecture decisions and validation evidence are in [docs/architecture.md](docs/architecture.md) and [docs/validation.md](docs/validation.md).

## Demo recording checklist

1. Disable IDEA's own automatic import optimization for a controlled demonstration.
2. Open `src/test/testData/DemoBefore.java` and wait for the initial baseline.
3. Delete the `List<String> names;` line.
4. Show that the notification appears at the bottom-right and typing remains in the editor.
5. Let the notification time out and show that the file remains unchanged.
6. Invoke **Review newly unused Java imports**, accept, and show that only `List` disappears.
7. Undo once to restore the import, then edit normally and confirm there is no immediate re-removal.
8. Repeat briefly in automatic and manual-only modes.

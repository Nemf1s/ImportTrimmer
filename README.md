# Import Trimmer

Import Trimmer watches the Java files you edit. When an import that was previously used becomes unused, it offers to remove it without sorting or optimizing the remaining imports.

Imports that were already unused when the file was opened are left unchanged.

## Compatibility

Compatible with IntelliJ IDEA Ultimate 2026.2.2. Currently, supports only Java source files.

## Install

[Install Import Trimmer from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34181-import-trimmer).

## Behavior

The default mode is **Ask before removing**. When an import becomes unused, the plugin displays a standard IDEA notification:

- **Remove** removes the suggested import as a single undoable change.
- **Keep**, closing the notification, navigating away, or letting it expire leaves the file unchanged.
- **Settings** opens the Import Trimmer configuration.

Settings under **Settings | Editor | Import Trimmer**:

| Setting | Default | Range |
| --- | --- | --- |
| Enabled | Yes | On/off |
| Mode | Ask before removing | Ask, automatic, manual |
| Analysis debounce | 750 ms | 250-3000 ms |
| Notification timeout | 10 s | 3-60 s |

**Remove automatically** uses the same fresh semantic validation and exact edit plan without showing a notification. Undo restores the import and rebaselines tracking, so automatic mode does not immediately remove it again.

**Only when requested** keeps transition history but produces no unsolicited UI or edits. Invoke **Review newly unused Java imports** through Find Action. The action is available in every mode, performs fresh committed analysis, and can revisit an eligible dismissed episode. Presentation waits while completion, a live template, or import-block editing is active.

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

## Safety and preservation

Import Trimmer removes only the imports included in an accepted suggestion. It does not run **Optimize Imports**, rearrange imports, reformat the file, or modify unrelated source code.

When Java analysis is incomplete or ambiguous—for example, while the file contains syntax errors or unresolved references—the plugin takes no action. Some short-lived used-to-unused transitions can be missed between debounced analyses; this is intentionally preferred over removing an import based on uncertain information.

IDEA settings such as **Optimize Imports on the Fly**, Actions on Save, formatters, and other plugins may modify imports independently. Import Trimmer does not change those settings.

For implementation details and the complete list of conservatively skipped cases, see [Architecture](docs/architecture.md).

## Development

Building the plugin requires JDK 25.

## License

Copyright 2026 Artem Latyshev

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

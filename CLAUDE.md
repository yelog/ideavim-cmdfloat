# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IdeaVim-CmdFloat is an IntelliJ Platform plugin that provides a floating command-line overlay for IdeaVim users. It intercepts `:`, `/`, and `?` triggers to display a modern overlay near the caret instead of using the status bar.

**Key characteristics:**
- Plugin ID: `com.yelog.ideavim.cmdfloat`
- Requires IdeaVim 2.10.0+
- Minimum IDE version: 2024.3.6 (build 231+)
- Language: Kotlin with JVM 21 toolchain
- Uses IntelliJ Platform Plugin Template architecture

## Build and Test Commands

```bash
# Launch sandbox IDE with plugin installed
./gradlew runIde

# Run all tests (JUnit4 + IntelliJ Platform TestFramework)
./gradlew test

# Run a specific test class
./gradlew test --tests "com.yelog.ideavim.cmdfloat.MyPluginTest"

# Run a single test method
./gradlew test --tests "com.yelog.ideavim.cmdfloat.MyPluginTest.testProjectService"

# Build plugin ZIP for distribution
./gradlew buildPlugin
# Output: build/distributions/

# Full verification with Kover coverage
./gradlew check

# Clean build artifacts
./gradlew clean

# Run UI tests with Robot Server plugin
./gradlew runIdeForUiTests
```

## High-Level Architecture

### Core Components Flow

```
User Input → CmdlineOverlayKeyDispatcher → CmdlineOverlayManager → IdeaVimFacade → IdeaVim
                        ↓
            CmdlineOverlayPanel (UI)
                        ↓
            CommandHistory + Completions
```

### Key Classes and Responsibilities

**CmdlineOverlayKeyDispatcher** (`overlay/CmdlineOverlayKeyDispatcher.kt`)
- Intercepts global keyboard events via `KeyEventDispatcher`
- Detects `:`, `/`, `?` and `Ctrl-R =` triggers
- Implements smart suppression logic to avoid conflicts with:
  - Single-character commands (`f`, `t`, `r`, etc.)
  - Extended search modes (vim-flash, sneak, leap)
  - Pending operator commands
- Tracks state: `previousKeyAwaitsCharArgument`, `inExtendedSearchMode`, `awaitingExpressionTrigger`

**CmdlineOverlayManager** (`overlay/CmdlineOverlayManager.kt`)
- Lifecycle manager for the overlay popup
- Maintains separate command/search/expression history (20 items each)
- Handles overlay positioning, focus management, and popup lifecycle
- Orchestrates interaction between UI panel, IdeaVim facade, and completion systems
- Key methods:
  - `handleTrigger(mode: OverlayMode)` - shows overlay for command/search/expression modes
  - `collectSearchWords()` - extracts search completions with syntax highlighting
  - `showOverlay()` - creates and displays JBPopup with CmdlineOverlayPanel

**IdeaVimFacade** (`overlay/IdeaVimFacade.kt`)
- **Critical**: Reflection-based adapter to IdeaVim's internal APIs
- Handles API evolution across IdeaVim versions by trying multiple class/method names
- Key patterns:
  - Caches reflection lookups (`handleKeyMethodCache`, `vimEditorCreationCache`)
  - Provides fallback to IDE event queue when reflection fails
  - Safe optional unwrapping for Kotlin-Java interop
- Main functions:
  - `replay()` / `replayExpression()` - sends keystrokes to IdeaVim
  - `previewSearch()` / `cancelSearchPreview()` - incremental search with highlighting
  - `isAwaitingCharArgument()` - detects if IdeaVim expects character input
  - `readGlobalVariable*()` - reads `.ideavimrc` configuration

**CmdlineOverlayPanel** (`overlay/CmdlineOverlayPanel.kt`)
- Swing UI component for the floating overlay
- Manages input field, completion list, and status indicators
- Handles history navigation (Up/Down), completion selection (Tab/Shift-Tab, Ctrl-N/P)
- Displays match counts and current index for searches
- Callbacks: `onSubmit`, `onCancel`, `onSearchPreview`, `onCommandPatternPreview`

**Completion System** (`overlay/*Completion.kt`)
- `ActionCommandCompletion` - IdeaVim action commands (`:set`, `:map`, etc.)
- `OptionCommandCompletion` - IdeaVim options (`:set number`, etc.)
- `ExCommandCompletion` - Ex commands (`:w`, `:q`, `:s/`, etc.)
- `FuzzyMatcher` - implements fuzzy search algorithm for completions

**CmdlineOverlaySettings** (`overlay/CmdlineOverlaySettings.kt`)
- Singleton that reads all `g:cmdfloat_*` variables via `IdeaVimFacade`
- Provides typed accessors: `highlightCompletionsEnabled()`, `searchCompletionLineLimit()`, `isDefaultTriggerEnabled(mode)`, `singleCharArgumentKeys()`, `extendedSearchKeys()`
- Handles legacy per-mode disable variables (`cmdfloat_disable_default_command`, `cmdfloat_disable_default_search`, `cmdfloat_disable_default_search_backward`) for backward compatibility

**CmdlineOverlayKeymap** (`overlay/CmdlineOverlayKeymap.kt`)
- Singleton that resolves completion navigation keybindings from `g:cmdfloat_completion_prev_keys` / `g:cmdfloat_completion_next_keys`
- Default bindings: previous = Up/Ctrl-P/Shift-Tab, next = Down/Ctrl-N/Tab
- Contains `KeyStrokeParser` which parses Vim-style key notation (e.g., `<C-p>`, `<S-Tab>`, `ctrl+n`) into `javax.swing.KeyStroke` objects

**Cache Layer** (`cache/`)
- `DocumentWordCache` - caches extracted words from documents for search completion
- `CompletionIndex` - maintains indexed completions to avoid re-computation on each keystroke

**Debouncer** (`util/Debouncer.kt`)
- Delays execution of operations (like completion filtering) until input stabilizes
- Used to reduce unnecessary computation during rapid typing

### IdeaVim API Integration Strategy

The plugin must work across multiple IdeaVim versions (2.10.0+) where internal APIs change frequently. **Always use reflection with fallbacks**:

1. **Load classes with multiple name attempts:**
```kotlin
private val commandStateClass: Class<*>? = loadClass(
    "com.maddyhome.idea.vim.command.CommandState",
    "com.maddyhome.idea.vim.state.CommandState",
)
```

2. **Cache reflection results:**
```kotlin
private val handleKeyMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
```

3. **Provide IDE event queue fallback:**
When `handleKey()` reflection fails, fall back to `IdeEventQueue.postEvent()` for command replay.

4. **Never assume API stability:**
Always wrap reflection calls in `runCatching { }` and log failures appropriately.

### Configuration System

Users configure the plugin via IdeaVim global variables in `.ideavimrc`:

- `g:cmdfloat_disable_default_trigger` - disable `:`, `/`, `?` interception
- `g:cmdfloat_completion_prev_keys` / `g:cmdfloat_completion_next_keys` - custom navigation keys
- `g:cmdfloat_highlight_completions` - toggle syntax highlighting in completions
- `g:cmdfloat_search_completion_line_limit` - disable completions for large files
- `g:cmdfloat_single_char_argument_keys` - keys that expect single char (default: `['f', 't', 'F', 'T', 'r', 'm', '\'', '`', '@', 'q', 'z', 'Z', 'g']`)
- `g:cmdfloat_extended_search_keys` - keys that start multi-char search (default: `['s', 'S']`)

Read these via `IdeaVimFacade.readGlobalVariable*()` methods.

### Plugin Lifecycle

1. **Startup** (`startup/CmdlineOverlayStartupActivity.kt`):
   - Registered as `postStartupActivity` in `plugin.xml`
   - Installs `CmdlineOverlayKeyDispatcher` to `KeyboardFocusManager`
   - Only activates if IdeaVim is available and project is not in LightEdit mode

2. **Service** (`services/CmdlineOverlayService.kt`):
   - Project-level service holding `CmdlineOverlayManager` instance
   - Registered via `@Service` annotation

3. **Actions** (`actions/CmdfloatOverlayActions.kt`):
   - Three actions: `cmdfloat.command`, `cmdfloat.search`, `cmdfloat.search_backward`
   - Allow users to bind custom keymaps in `.ideavimrc` (e.g., `nmap ; <Action>(cmdfloat.command)`)

## Testing

**Test Framework**: JUnit4 + IntelliJ Platform TestFramework (`BasePlatformTestCase`)

```kotlin
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {
    fun testProjectService() {
        val projectService = project.service<CmdlineOverlayService>()
        assertNotNull(projectService)
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
```

Test data files go in `src/test/testData/`.

## Code Style

For code style guidelines, see `AGENTS.md` which contains:
- Formatting rules (4 spaces, ~120 char line length)
- Kotlin idioms and naming conventions
- Type safety guidelines
- Commit message format (conventional commits)

## Important Implementation Details

### Search Preview System

The plugin implements live search preview using IdeaVim's incremental search:
- Stores original search state before preview (`SearchPreviewState`)
- Calls `SearchHighlightsHelper.updateIncsearchHighlights()` via reflection
- Restores state on cancel, commits on submit
- Moves caret to first match during preview

### Visual Mode Range Handling

When triggering command mode (`:`) from visual mode:
- Automatically adds `'<,'>` range prefix to command
- Preserves visual selection for command execution
- Reselects visual range via `IdeaVimFacade.reselectLastVisualSelection()` after replay

### Expression Register (`Ctrl-R =`)

Special handling for expression input in insert mode:
- Tracks `expressionReplayNeedsCtrlR` state to avoid double `Ctrl-R` on replay
- Calls `IdeaVimFacade.beginExpressionInput()` to start expression mode
- Supports reading Vim registers (e.g., `@0`, `@"`, `@+`) via `readRegister()`

### Syntax Highlighting in Completions

When `g:cmdfloat_highlight_completions` is enabled:
- Extracts `TextAttributes` from editor's `HighlighterIterator` and `MarkupModel`
- Samples foreground color, font type, background, effects at word offsets
- Prefers highlighted attributes over default when deduplicating words
- Falls back to plain rendering if disabled (for performance)

## Common Pitfalls

1. **IdeaVim API Changes**: Always use reflection with fallbacks. Test with multiple IdeaVim versions.

2. **Thread Safety**: UI operations must run on EDT. Use `ApplicationManager.getApplication().invokeLater()` for callbacks.

3. **Focus Management**: Use `IdeFocusManager` for editor focus restoration, not `Component.requestFocus()`.

4. **Editor Lifecycle**: Always check `editor.isDisposed` before operations.

5. **Overlay Suppression**: When falling back to IDE event queue, call `suppressOverlayFor(300)` to prevent overlay re-triggering on replayed keys.

6. **LightEdit Mode**: Plugin automatically disables in LightEdit mode (no project context).

7. **Char Argument Tracking**: Must track keys like `f`, `t`, `r` that await a character argument to avoid intercepting the following key.

8. **Extended Search Mode**: Must suppress overlay for configurable timeout (10s default) when multi-char search commands like `s` (vim-flash) are triggered.

## Publishing

Requires environment variables:
- `CERTIFICATE_CHAIN` - plugin signing certificate
- `PRIVATE_KEY` - signing private key
- `PRIVATE_KEY_PASSWORD` - key password
- `PUBLISH_TOKEN` - JetBrains Marketplace token

Run: `./gradlew publishPlugin`

## Dependency Versions

Managed via `gradle/libs.versions.toml`:
- Kotlin: 2.2.0
- IntelliJ Platform Gradle Plugin: 2.10.2
- Target Platform: IntelliJ IDEA Community 2024.3.6
- Gradle: 9.0.0
- JVM Toolchain: 21

Bundled dependency: IdeaVim plugin (specified in `gradle.properties` as `platformPlugins`).

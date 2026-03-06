<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaVim-CmdFloat Changelog

## 0.1.0

### Features
- feat: Add file path completion for commands like `:e`, `:vs`, `:sp`, `:tabe`, `:r`, `:w`
- feat: Support relative path navigation with `..` in file completion
- feat: Show file type icons in completion list matching project view
- feat: Add history search filter with `!` prefix in command/search mode
- feat: Support disabling default shortcut key interception via `let g:cmdfloat_disable_default_trigger = 1`
- feat: Support Vim registers like `c-r0` in the floating window

### Performance
- perf: Add comprehensive performance optimizations
- perf: Add TTL-based cache for IdeaVim global variable reads

### Fixes
- fix(file-path-completion): Require space after command to trigger file completion (e.g. `:e ` triggers file completion, `:e` shows command completions)
- fix(file-path-completion): Preserve user relative path input format in display
- fix(file-path-completion): Use project-relative paths for correct file resolution
- fix(file-completion): Dispatch keys to focused component instead of editor
- fix(file-completion): Strip leading mode prefix from payload to avoid double colon
- fix(file-completion): Add space between command and file path on selection
- fix(file-completion): Use path prefix for executable path instead of relativeBase
- fix: Overlay blocked after using `:actionlist` — KEY_RELEASED events from overlay panel and replay path no longer pollute extended-search-mode state (#18)
- fix: Avoid triggering the overlay during multi-character searches (e.g. flash search)

### Documentation

- docs: Add cache layer, testing and code style sections to AGENTS.md

## 0.0.10

- ci: Release is now triggered by pushing a version tag (e.g. `v0.0.10`) instead of a branch push

## 0.0.9

- fix: Overlay blocked after using `:actionlist` — KEY_RELEASED events from overlay panel and replay path no longer pollute extended-search-mode state (#18)

## 0.0.8

- fix: Avoid triggering the overlay during multi-character searches (e.g. flash search)
- feat: Allow configuring keys that suppress overlay during pending search input

## 0.0.7

- feat: Support Vim registers like `c-r0` in the floating window
- feat: Support disabling default shortcut key interception

## 0.0.6

- feat: When the number of lines exceeds the value set by `let g:cmdfloat_search_completion_line_limit = 3000`, disable real-time search as well as the total count and index query features
- feat: Support custom shortcuts for Search/Command
- fix: New shortcut Ctr-R = blocks access to selection register "+ in insert mode #17

## 0.0.5

- feat: Added expression floating window, triggered in insert mode by pressing `Ctrl-R =`
- fix: Deprecated method RangeHighlighter.getTextAttributes() is invoked in CmdlineOverlayManagerKt.applyRangeHighlighterAttributes(...)

## 0.0.4

- feat: Support highlighting in the search completion list
- feat: Support custom shortcuts in the completion list
- feat: Deduplication in the search completion list is now case sensitive
- fix: In visual mode, replacement search candidates come from the selected area
- feat: Support controlling search execution in files exceeding 3000 lines through `let g:cmdfloat_search_completion_line_limit = 3000`

## 0.0.3

- fix: Floating window style simplified, uses theme colors, compatible with theme switching
- fix: Floating window and completion list use the same popup layer, expanded when needed
- ci: Add plugin icon

## 0.0.2

- fix: Incorrect triggering of Search/CmdLine when executing operations such as `r:`, `f:`, `T/`, etc.

## 0.0.1

### Added

- Support displaying input and execution of Search and CmdLine via floating window
- CmdLine supports completion options
- Search supports showing all current words
- Search supports displaying the total number of matches and the current index
- The completion box supports shortcut keys by default: up/down arrows, Tab/Shift+Tab, ctrl+n/ctrl+p

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

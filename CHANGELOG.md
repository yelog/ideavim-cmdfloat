<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaVim-CmdFloat Changelog

## 0.0.5

- feat: Added expression floating window, triggered in insert mode by pressing `Ctrl-R =`

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

# IdeaVim-CmdFloat

![Build](https://github.com/yelog/ideavim-cmdfloat/workflows/Build/badge.svg)

A modern command-line overlay for IdeaVim users that makes `:`, `/`, and `?` feel quicker and easier to use.

<!-- Plugin description -->
`IdeaVim-CmdFloat` provides an editor-themed command-line overlay for IdeaVim. When it detects triggers such as `:`, `/`, or `?`, it shows a visual panel with instant focus, provides quick history navigation, and replays the command back to IdeaVim. The overlay is automatically disabled when IdeaVim is unavailable or the IDE runs in LightEdit mode to avoid conflicts.
<!-- Plugin description end -->

<table>
  <tr>
    <th>Search/Ex Command Float</th>
    <th>Search Completion</th>
  </tr>
  <tr>
    <td>
      <img src="https://github.com/user-attachments/assets/12c73a66-4274-45e9-9acd-24815a66d667" />
    </td>
    <td>
      <img src="https://github.com/user-attachments/assets/1690c669-111f-4dc2-9868-1ed84a8e8b14" />
    </td>
  </tr>
  <tr>
    <th>CmdLine Actions</th>
    <th>CmdLine Completion</th>
  </tr>
  <tr>
    <td>
      <img src="https://github.com/user-attachments/assets/61bff15f-f576-4087-bcdb-da95f07f5064" />
    </td>
    <td>
      <img src="https://github.com/user-attachments/assets/569db5df-341d-4480-8eba-22767c64e2d5" />
    </td>
  </tr>
</table>

## Key Features
- Intercepts `:`, `/`, and `?` to display a command or search overlay near the caret instead of at the status bar.
- Stores the most recent 20 commands or searches; use `Up`/`Down` to cycle through history and `Esc` to cancel.
- Matches the current editor theme by sampling foreground, background, and border colors automatically.
- Falls back to posting events through the IDE queue if IdeaVim APIs change, so command playback still works.
- Skips initialization in headless, LightEdit, or non-Normal mode editors to reduce accidental triggers.

## Usage

Search for the plugin name `Vim CmdFloat` in the plugin marketplace, then install it.

- Make sure IdeaVim is installed and the editor is in Normal mode.
- Press `:` for Ex commands or `/` and `?` for search; the overlay appears automatically.
- Press `Enter` to submit. The command is replayed in IdeaVim; press `Esc` to close without executing.
- Use `Up`/`Down` to traverse history. Typing new text resets the history cursor.

### Custom Keybindings

The overlay uses `Up`/`Down`, `<C-p>`/`<C-n>`, and `<S-Tab>`/`<Tab>` by default to navigate search/command suggestions. To override these shortcuts, define global variables in your `.ideavimrc`:

```vim
let g:cmdfloat_suggestion_prev_keys = ['Up', '<C-p>', '<S-Tab>']
let g:cmdfloat_suggestion_next_keys = ['Down', '<C-n>', '<Tab>']
```

Both lists accept Vim-style tokens such as `<C-n>` or simple descriptors like `ctrl+n`. When the variables are omitted, `Up`/`Down` remain the active bindings.

### Highlight Rendering

Suggestion rows reuse the editor highlight palette to emphasize matches. This runs by default; set a global flag in `.ideavimrc` to disable the extra highlighting when you need a cheaper rendering path:

```vim
let g:cmdfloat_highlight_suggestions = 0
```

Any truthy value (`1`, `on`, `true`, `yes`) keeps the extra styling enabled; falsy values turn it off while preserving the match indicators for your search query.

## Requirements
- JetBrains IDE 2024.3.6 or newer.
- IdeaVim 2.10.0 or newer. The overlay disables itself if IdeaVim is missing.
- Only active in standard project windows; LightEdit mode is ignored.

## Installation
- **JetBrains Marketplace**: `Settings/Preferences` > `Plugins` > `Marketplace` > search for `Vim CmdFloat` > Install.
- **Manual install**: download the [latest release](https://github.com/yelog/ideavim-cmdfloat/releases/latest), then go to `Settings/Preferences` > `Plugins` > `gear icon` > `Install Plugin from Disk...` and select the ZIP.
- **Local build**: run `./gradlew buildPlugin` and install the generated ZIP from `build/distributions`.

## Build and Debug from Source
- `./gradlew runIde`: launch a sandbox IDE for local debugging.
- `./gradlew test`: execute the IntelliJ Platform integration tests.
- On macOS, consider disabling the IDE "Press and Hold" behavior to mirror Vim key timing.

## FAQ
- **The overlay does not appear.** Confirm the editor is in IdeaVim Normal mode and IdeaVim is updated to a supported version.
- **The command did not execute.** The plugin falls back to the IDE event queue. If it still fails, search for `IdeaVim command overlay` in the IDE logs for diagnostics.

---
Built on top of the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

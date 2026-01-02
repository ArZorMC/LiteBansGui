# ⚖️ LiteBansGUI

A guided, permission-driven **GUI frontend for LiteBans** — built for structured moderation, consistent punishments, and clean audit trails.

Designed for modern Paper servers with MiniMessage-powered GUIs and chat-based reason prompts.

---

## 🔧 Features
- 🧭 **Guided punishment flow** — Category → Severity → Reason → Confirm
- 📚 **Category-based moderation**
    - Fully configurable punishment categories
    - Optional granular severity-level permissions
- ✍️ **Chat-based reason input**
    - Cancel and no-reason keywords
    - Configurable timeout
- 🔕 **Silent punishment toggle**
    - Permission-aware
    - Configurable default behavior
- 🪟 **Punishment History Viewer**
    - Paginated history (Bans / Mutes / Warns / Kicks)
    - Permission-gated filters
    - Clear ACTIVE / INACTIVE / REINSTATED / REVERTED status indicators
- 🖱️ **Shift-click history actions** (permission gated)
    - SHIFT + LEFT → Pardon
    - SHIFT + RIGHT → Reinstate
    - Reason + confirmation required
- 🧾 **Audit-safe history handling**
    - Original removal data preserved
    - Reissues append metadata instead of overwriting
- 🎨 **Fully configurable UI**
    - Menu layouts, slots, icons, and fillers via `layout.yml`
    - All text via MiniMessage in `messages.yml`
- 🛠️ **In-game layout editor** (admin-only)
- 🔄 Reload config, layout, and messages without restarting

---

## 📦 Requirements
- **Minecraft 1.21+**
- **Paper** or compatible fork (Purpur, Pufferfish, etc.)
- **LiteBans** (required)

> ⚠️ This plugin is a **GUI frontend** — LiteBans must be installed and enabled.

---

## 🚫 Compatibility Notes
- Designed specifically for **LiteBans**
- Uses LiteBans’ database and command system
- Not compatible with other punishment plugins

> LiteBansGUI does not replace LiteBans — it enhances it.

---

## 🧩 Commands

| Command            | Description                              | Permission            |
|--------------------|------------------------------------------|-----------------------|
| `/punish <player>` | Open the guided punishment GUI           | `litebansgui.use`     |
| `/punishreload`    | Reload config, layout, and messages      | `litebansgui.reload`  |
| `/punisheditor`    | Open the in-game layout editor           | `litebansgui.editor`  |

> 📝 All permission errors and feedback are handled via `messages.yml`.

---

## 🔐 Permissions

LiteBansGUI uses **GUI-level permissions** to control **what staff can see, click, and execute**.

Permissions dynamically shape the moderation experience:
- Categories and severities can be hidden or locked
- History filters and actions are permission-gated
- Denied interactions provide visual and sound feedback

> ⚠️ LiteBansGUI permissions are the **source of truth** — LiteBans command permissions are not relied on.

---

### Core Access
| Node | Description | Default |
|-----|------------|---------|
| `litebansgui.use` | Use `/punish` and enter the moderation flow | OP |
| `litebansgui.reload` | Reload config, layout, and messages | OP |
| `litebansgui.editor` | Use the in-game layout editor | OP |

---

### Categories & Severity Levels
Controls **which punishments** a staff member may issue.

**Patterns**
- `litebansgui.category.<categoryId>`
- `litebansgui.category.<categoryId>.level.<levelId>`

**Examples**
- `litebansgui.category.spamming`
- `litebansgui.category.griefing.level.3`

**Behavior**
- Missing category permission → category is hidden or locked
- Missing level permission → severity is hidden or denied
- Categories with only one level skip the severity menu automatically

---

### Punishment History
| Node | Description | Default |
|-----|-------------|---------|
| `litebansgui.history` | View punishment history | OP |
| `litebansgui.history.filter.*` | Access all history filters | OP |
| `litebansgui.history.filter.bans` | View ban history | OP |
| `litebansgui.history.filter.mutes` | View mute history | OP |
| `litebansgui.history.filter.warns` | View warn history | OP |
| `litebansgui.history.filter.kicks` | View kick history | OP |

---

### History Actions (Shift-Click)
| Node | Action | Default |
|-----|--------|---------|
| `litebansgui.history.pardon` | Pardon an active punishment | OP |
| `litebansgui.history.reinstate` | Reinstate a reverted punishment | OP |
| `litebansgui.history.action.*` | All history actions | OP |

Actions require:
- Proper permission
- Reason input
- Confirmation step

Expired punishments cannot be reinstated.

---

### Denied Interaction Behavior
Denied buttons are handled visually and audibly, configurable via `config.yml`:

```yaml
permissions:
  deny-appearance: LOCKED | HIDE | REPLACE
  deny-click-sound:
    enabled: true
```
This allows servers to choose whether denied actions are:

- **Shown as locked** — visible but unclickable, with denied feedback
- **Hidden entirely** — removed from the menu
- **Replaced with filler items** — maintains layout symmetry without exposing the action

---

> ⚠️ **OPs inherit all permissions.**  
> Use a permissions plugin (e.g., LuckPerms) or `/deop` for accurate testing.

---

## 🧠 Punishment Flow Overview

1. **Select Category**
2. **Select Severity**
3. **Enter Reason** (chat prompt)
4. **Confirm**
   - Optional silent toggle
   - Full summary preview
5. **Dispatch to LiteBans**

All permission checks are enforced by **LiteBansGUI before execution**.

---

## 🪟 Punishment History

- View complete punishment history for a target
- Filter by type (ALL / BANS / MUTES / WARNS / KICKS)
- Shift-click actions:
  - **Pardon** active punishments
  - **Reinstate** reverted punishments (if not expired)
- Audit-safe behavior:
  - Original removal data is preserved
  - Reissues append metadata instead of overwriting

---

## 📁 Configuration Files

- **`config.yml`**
  - Core behavior settings
  - Reason input rules (timeouts, cancel/none keywords)
  - Permission UI behavior (deny appearance & sounds)

- **`layout.yml`**
  - Menu sizes and slot positions
  - Icon materials and filler items
  - Fully editable via the in-game layout editor

- **`messages.yml`**
  - All player-facing text
  - MiniMessage formatting
  - Colors, gradients, hover effects, and action hints

> 📝 **Rule of thumb:**  
> Structure & behavior → `config.yml` / `layout.yml`  
> Text & visuals → `messages.yml`

---

## 🧩 Design Philosophy

- **GUI-first moderation** — no memorizing commands
- **Permission-driven authority** — staff only see what they can use
- **Audit-safe by default** — no destructive history edits
- **Predictable enforcement** — permissions are checked before execution
- **Minimal coupling** — works across LiteBans versions without tight API dependence

---

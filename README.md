# Bayaz [![Build Status](https://circleci.com/gh/libera-programming/bayaz.svg?style=svg)](https://circleci.com/gh/libera-programming/bayaz) [![codecov](https://codecov.io/gh/libera-programming/bayaz/branch/main/graph/badge.svg?token=QM2ZYNW4KX)](https://codecov.io/gh/libera-programming/bayaz)

<p align=center>
  <img src="https://static.wikia.nocookie.net/firstlaw/images/2/2e/Bayaz-GraphicNovel.jpg/revision/latest?cb=20140307222848"
       height=200>
  </img>
<p>

> Bayaz looks maybe sixty, heavily built, with green eyes, a strong face, deeply lined, and a close-cropped grey beard around his mouth. He is entirely bald, with a tanned pate. He's neither handsome nor majestic, but there's something stern and wise about him. An assurance, an air of command. A man used to giving orders, and to being obeyed.

## Features
- [X] Configuration
  - [X] Granular feature flags to enable/disable specific functionality
- [X] Moderation
  - [ ] Admin registration/maintenance
  - [X] Admins list output
  - [X] Quiet/unquiet
    - [X] Store reason
  - [X] Ban/unban
    - [X] Store reason
  - [X] Kickban
    - [X] Store reason
  - [X] Kick
    - [X] Store reason
  - [X] Warn
    - [X] Store reason
  - [X] Admin history view for each nick
  - [ ] Timers for mode resets
    - [ ] Timers persist across bot restarts
  - [ ] Nick/host status lookup (ban/quiet)
  - [X] Host/nick tracking
    - [X] Use tracking db for admin operations
    - [ ] Query common hosts and nicks by pattern
  - [ ] +m mode (channel gets +m, ops get +o, everyone gets +v; we can then manually -v people)
  - [X] `!ops` command to ping all admins
  - [ ] Spam detection
    - [ ] Automatic quiet after X messages in Y seconds
    - [ ] Automatic quiet after pinging more than N nicks in one message
    - [ ] Automatic quiet after using certain words
    - [ ] Automatic ignore after receiving X DMs in Y seconds
- [X] URL lookup
  - [X] Title lookup
  - [X] Domain resolution
  - [X] Content type
  - [X] Content length (human readable)
  - [ ] Youtube (Title, duration)
  - [ ] Github (Short name, description, lang, stars, followers)
  - [ ] Twitter (Tweet, retweets, likes)
  - [X] Protections
    - [X] Socket timeout
    - [X] Download timeout
    - [X] Max streaming download size
    - [X] Max redirect amount
    - [X] Graceful parsing of partial and invalid HTML
    - [X] Graceful parsing of junk in headers
    - [X] Strip out whitespace
    - [X] Limit output to fixed amount
- [X] Protections
  - [X] Ignore messages from self

## User documentation
### Public operations
| Command | Aliases | Usage    | Action                               |
|---------|---------|----------|--------------------------------------|
| `bayaz` |         | `!bayaz` | Share a link to bayaz's source repo. |
| `ops`   |         | `!ops`   | Output a list of channel ops.        |

### Admin operations
| Command   | Aliases   | Usage                   | Action                                            |
|-----------|-----------|-------------------------|---------------------------------------------------|
| `warn`    | `w`       | `!w <nick> [reason]`    | Show a public warning to the nick.                |
| `quiet`   | `q`,`+q`  | `!q <target> [reason]`  | Set mode +q on the target.                        |
| `unquiet` | `uq`,`-q` | `!uq <target> [reason]` | Set mode -q on the target.                        |
| `ban`     | `b`,`+b`  | `!b <target> [reason]`  | Set mode +b on the target.                        |
| `unban`   | `ub`,`-b` | `!ub <target> [reason]` | Set mode -b on the target.                        |
| `kick`    | `k`       | `!k <nick> [reason]`    | Remove the nick from the channel.                 |
| `kickban` | `kb`      | `!kb <nick> [reason]`   | Remove the nick from the channel and set mode +b. |
| `history` | `h`       | `!h <nick>`             | Output the admin history for a nick.              |

## TODO
* This title doesn't render properly: https://access.redhat.com/security/cve/cve-2022-0492

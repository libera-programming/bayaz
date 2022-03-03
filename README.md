# Bayaz [![Build Status](https://circleci.com/gh/libera-programming/bayaz.svg?style=svg)](https://circleci.com/gh/libera-programming/bayaz) [![codecov](https://codecov.io/gh/libera-programming/bayaz/branch/main/graph/badge.svg?token=QM2ZYNW4KX)](https://codecov.io/gh/libera-programming/bayaz)

<p align=center>
  <img src="https://static.wikia.nocookie.net/firstlaw/images/2/2e/Bayaz-GraphicNovel.jpg/revision/latest?cb=20140307222848"
       height=200>
  </img>
<p>

> Bayaz looks maybe sixty, heavily built, with green eyes, a strong face, deeply lined, and a close-cropped grey beard around his mouth. He is entirely bald, with a tanned pate. He's neither handsome nor majestic, but there's something stern and wise about him. An assurance, an air of command. A man used to giving orders, and to being obeyed.

## Features
- [X] Moderation
  - [ ] Admin registration/maintenance
  - [X] Help output
  - [X] Admins list output
  - [X] Quiet/unquiet
    - [ ] Store reason
  - [X] Ban/unban
    - [ ] Store reason
  - [X] Kickban
    - [ ] Store reason
  - [X] Kick
    - [ ] Store reason
  - [X] Warn
  - [ ] Timers for mode resets
    - [ ] Timers persist across bot restarts
  - [ ] Nick/host status lookup (ban/quiet)
  - [X] Host/nick stalking
    - [ ] Query common hosts and nicks by pattern
  - [ ] +m mode (channel gets +m, ops get +o, everyone gets +v; we can then manually -v people)
  - [X] `!ops` command to ping all admins
  - [ ] Spam detection
    - [ ] Automatic quiet after X messages in Y seconds
    - [ ] Automatic quiet after pinging more than N nicks in one message
    - [ ] Automatic quiet after using certain words
    - [ ] Automatic ignore after receiving X DMs in Y seconds
  - [ ] Channel logging
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

# Bayaz [![Build Status](https://travis-ci.org/libera-programming/bayaz.svg?branch=master)](https://travis-ci.org/libera-programming/bayaz) [![codecov](https://codecov.io/gh/libera-programming/bayaz/branch/master/graph/badge.svg)](https://codecov.io/gh/libera-programming/bayaz)

<p align=center>
  <img src="https://static.wikia.nocookie.net/firstlaw/images/2/2e/Bayaz-GraphicNovel.jpg/revision/latest?cb=20140307222848"
       height=200>
  </img>
<p>

> Bayaz looks maybe sixty, heavily built, with green eyes, a strong face, deeply lined, and a close-cropped grey beard around his mouth. He is entirely bald, with a tanned pate. He's neither handsome nor majestic, but there's something stern and wise about him. An assurance, an air of command. A man used to giving orders, and to being obeyed.

## Features
- [ ] Moderation
  - [ ] Admin registration/maintenance
  - [X] Quiet/unquiet
    - [ ] Store reason
  - [X] Ban/unban
    - [ ] Store reason
  - [X] Kickban
    - [ ] Store reason
  - [ ] Timers for mode resets
    - [ ] Timers persist across bot restarts
  - [ ] Nick/host status lookup (ban/quiet)
  - [ ] Host/nick stalking
    - [ ] Query common hosts and nicks by pattern
  - [ ] +m mode (channel gets +m, ops get +o, everyone gets +v; we can then manually -v people)
  - [ ] `!ops` command to ping all admins
  - [ ] Spam detection
    - [ ] Automatic quiet after X messages in Y seconds
    - [ ] Automatic quiet after pinging more than N nicks in one message
    - [ ] Automatic quiet after using certain words
    - [ ] Automatic ignore after receiving X DMs in Y seconds
  - [ ] Channel logging
- [ ] URL lookup
  - [ ] Title lookup
  - [ ] Domain resolution
  - [ ] Youtube (Title, duration)
  - [ ] Github (Short name, description, lang, stars, followers)
  - [ ] Twitter (Tweet, retweets, likes)
- [ ] Protection
  - [ ] Ignore messages from self
  - [ ] Limit all output to fixed amount

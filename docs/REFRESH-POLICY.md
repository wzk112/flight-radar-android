# Current refresh policy

- Normal flight polling: 30 seconds minimum; previous shorter preferences migrate to 30 seconds. OpenSky quota scheduling can require longer intervals.
- Ordinary network failures: 15, 30, then 60 seconds between retries, also respecting the source request gate.
- HTTP 429: separate persisted counter; 60, 120, 240, 480, then 900 seconds. Successful flight responses reset the counter.
- Retry-After supports seconds and HTTP dates. Server waiting times longer than the local backoff cap remain binding.
- HTTP 401/403: authorization warning and at least five minutes before retry; slowing down does not fix missing authorization.
- Cooldown rejections do not increment failure counters. Source deadlines survive restart and are shared across pages and range changes.
- Public adsb.lol limits are dynamic; 30 seconds is an application policy, not a provider guarantee.

# Notes for anyone (or any agent) working on this repo

## Nothing personal in the repo
No emails, credentials, tokens, robot serials, account or pet ids, Wi-Fi names, IP addresses,
locations, timezones, or the owner's daily routine. Test data and screenshots use made-up values.
Real values live only on the phone. Scan before every commit:

```sh
git grep -n -I -i -E '([0-9]{1,3}\.){3}[0-9]{1,3}|@(gmail|yahoo|outlook|icloud)\.|LRE-[0-9]|/Users/' -- .
```

## Conventions
- App text is short: one or two word labels and states, not explanatory sentences.
- One vocabulary for the robot: Active, Sleeping, Off, Offline.
- Everything that talks to Whisker lives in `app/src/main/java/dev/nish/nessa/whisker/` and mirrors
  [pylitterbot](https://github.com/natekspencer/pylitterbot), so upstream fixes are easy to copy.
- Logic is written tests-first as plain Kotlin (no Android imports) so it runs on the JVM.
- Anything that changes the real robot's schedule saves a checkpoint first and reads the robot back.
  Tell the owner before running a test that touches the real robot.

## Build
```sh
./gradlew testDebugUnitTest assembleDebug
```
Design: `docs/DESIGN.md`. Plans: `docs/plans/`.

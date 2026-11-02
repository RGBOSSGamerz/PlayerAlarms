# PlayerAlarms

An addon for AUTISM Client that alerts you when other players join the server or come into render distance. It is built for base finding and for staying AFK at your base.

## Features

- Configurable alert sounds
- Chat notifications
- Sighting log
- Discord webhook notifications (off by default)
- AFK safety disconnect (off by default)

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API
- AUTISM Client

## Install

1. Download the jar from the Releases page.
2. Put it in your `mods` folder next to the AUTISM Client jar.
3. Start the game and enable PlayerAlarms in the client.

## Privacy

Your Discord webhook URL is stored only in your local game config. Never share that URL, and never commit your game's config or log folders to a repository. The sighting log is saved locally on your computer.

## Build

```powershell
.\gradlew.bat build --no-daemon
```

The jar is created in `build/libs/`.

## License

GPL-3.0. See the `LICENSE` file.

## Credits

- Built for AUTISM Client.
- Inspired by the PlayerAlarms module in Trouser Streak.

Copyright (C) 2026 RGBOSSGamerz
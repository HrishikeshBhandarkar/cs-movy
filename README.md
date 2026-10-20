# Movy — CloudStream Plugin

Unofficial [CloudStream](https://github.com/recloudstream/cloudstream) provider for [movy.sx](https://www.movy.sx), with search, home rows, movies, TV series, seasons/episodes, multiple qualities and subtitles.

## Install

1. Open CloudStream → **Settings** → **Extensions** → **Add repository**.
2. Paste this URL:

   ```
   https://raw.githubusercontent.com/HrishikeshBhandarkar/cs-movy/main/repo.json
   ```

3. Add the repository, then install the **Movy** plugin.

Direct `cloudstreamrepo://` link:
`cloudstreamrepo://raw.githubusercontent.com/HrishikeshBhandarkar/cs-movy/main/repo.json`

## Features

- Search powered by the TMDB proxy
- Home rows: trending, top rated, streaming services, upcoming TV and 4K
- Movies and TV series with all seasons/episodes
- Multiple source servers with up to 2160p quality
- Subtitle support
- Demo Netflix-style content advisory badge in the player
- No account or login required

## Development

Build the plugin locally:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat MovyProvider:make --no-daemon
```

The installable plugin is written to `MovyProvider/build/MovyProvider.cs3`.

Run the unit tests:

```powershell
.\gradlew.bat MovyProvider:testDebugUnitTest --no-daemon
```

Pushing to `main` runs the GitHub Action, which rebuilds the plugin and publishes `MovyProvider.cs3` and `plugins.json` to the `builds` branch.

## Disclaimer

This project is unofficial and not affiliated with CloudStream or movy.sx. It does not host or distribute any media; all content is provided by third-party services. Use it for educational purposes only and at your own risk.

## Credits

- [CloudStream](https://github.com/recloudstream/cloudstream)
- [CloudStream plugin template](https://github.com/recloudstream/TestPlugins)

## License

MIT — see [LICENSE](LICENSE).

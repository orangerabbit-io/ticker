# Ticker for Android

An Android port of [achannarasappa/ticker](https://github.com/achannarasappa/ticker), the terminal stock ticker — reimagined as a native mobile app. It reads the same `.ticker.yaml` configuration file as the original CLI, so your watchlist, positions, and groups carry over unchanged.

## Features

- **Real-time quotes** for stocks and cryptocurrencies
- **Multi-lot positions** — track purchases across multiple lots with cost basis and gains
- **Groups** — organize symbols into named groups, swiped through as pages
- **Portfolio summary** — aggregate value, change, and day movement
- **Quote sources**: Yahoo Finance (equities) and Coinbase (crypto)
- **Foreground polling** with a 30-second minimum refresh interval

## Building

Run the test suite (requires Nix):

```sh
nix develop -c ./gradlew :shared:jvmTest
```

CI builds a debug APK on every push and pull request.

## Installing

- Download the APK from CI workflow artifacts, or grab a release APK from the releases page.
- Install by sideloading the APK directly or via an F-Droid client.
- Works on GrapheneOS and other de-Googled Android distributions — no Google Play Services (GMS) required.

## Privacy

Ticker has no analytics, crash reporting, or advertising SDKs. It contacts only the quote data sources:

- `query1.finance.yahoo.com` and `query2.finance.yahoo.com` — quote data
- `finance.yahoo.com` and `consent.yahoo.com` — Yahoo session/consent flow
- `api.coinbase.com` — cryptocurrency quote data
- `raw.githubusercontent.com` — sample configuration lookup

Nothing else. No data leaves your device beyond these requests.

## License

GPL-3.0

# WiFi Scanner

An Android app for monitoring power outages. It detects whether power supply is available by scanning for selected WiFi networks and/or observing the device's charging state, then sends notifications via a Telegram bot.

## How It Works

The app runs as a foreground service and supports two scanning modes:

- **Network** -- Combines WiFi scanning with power state monitoring. When the device is online and charging, it relies on power state changes to trigger scans. When not charging, it runs periodic WiFi scans and sends webhook heartbeats to Home Assistant.
- **Power** -- Relies solely on the device's charging state to determine online/offline status. No WiFi scanning is performed.

When a state change is detected (online/offline), the app sends a notification to a configured Telegram chat via a bot.

## Features

- Foreground service with wake lock for reliable background operation
- Automatic restart on task removal and device reboot
- Configurable scan interval
- Telegram bot notifications on power state changes
- Home Assistant webhook integration
- Scan throttle protection (respects Android's 4 scans per 2 minutes limit)
- Scheduled blackout reminders

## Setup

### Prerequisites

- Android device running API 33+ (Android 13+)
- A Telegram bot (create one via [@BotFather](https://t.me/BotFather))
- (Optional) Home Assistant instance with a webhook configured

### Configuration

Add the following properties to your `local.properties` file in the project root:

```properties
# Telegram Bot (required for notifications)
bot.api.key=YOUR_TELEGRAM_BOT_TOKEN
chat.id=YOUR_TELEGRAM_CHAT_ID
admin.chat.id=YOUR_ADMIN_CHAT_ID

# Home Assistant Webhook (optional)
webhook.base.url=https://your-home-assistant-url
webhook.id=YOUR_WEBHOOK_ID
```

| Property | Required | Description |
|---|---|---|
| `bot.api.key` | Yes | Telegram bot API token from BotFather |
| `chat.id` | Yes | Telegram chat ID to send outage notifications to |
| `admin.chat.id` | Yes | Telegram chat ID for admin/private notifications |
| `webhook.base.url` | No | Base URL of your Home Assistant instance |
| `webhook.id` | No | Home Assistant webhook ID for heartbeat pings |

> **Note:** `local.properties` is gitignored and never committed. The values are injected into `BuildConfig` at build time.

## Building

Open the project in Android Studio, ensure `local.properties` is configured, and build normally.

## Screenshot

![Screenshot_20230202_120126](https://user-images.githubusercontent.com/4967255/216308059-0a12f43e-7e10-415d-8e69-1e0a4a69e7a8.png)
